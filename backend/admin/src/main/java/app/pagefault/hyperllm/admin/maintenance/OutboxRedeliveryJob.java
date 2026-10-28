package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.admin.consumer.LedgerFolder;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Advances outstanding settlements through replay, delivery repair, adjustment and write-off. */
@Slf4j
@Component
public class OutboxRedeliveryJob {

    private static final Duration REPAIR_LINK = Duration.ofMinutes(5);
    private static final Duration ADJUST = Duration.ofMinutes(15);
    private static final Duration WRITE_OFF = Duration.ofMinutes(30);
    private static final Duration RETRY = Duration.ofSeconds(30);

    private final SettlementWorklist worklist;
    private final OutboxEventRepository outboxEventRepository;
    private final LedgerFolder ledgerFolder;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration grace;
    private final int batchSize;

    public OutboxRedeliveryJob(
            SettlementWorklist worklist,
            OutboxEventRepository outboxEventRepository,
            LedgerFolder ledgerFolder,
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${hyperllm.outbox.redelivery-grace-seconds:60}") long graceSeconds,
            @Value("${hyperllm.outbox.redelivery-batch:500}") int batchSize
    ) {
        this.worklist = worklist;
        this.outboxEventRepository = outboxEventRepository;
        this.ledgerFolder = ledgerFolder;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.grace = Duration.ofSeconds(graceSeconds);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${hyperllm.outbox.redelivery-delay:PT30S}")
    public void redeliver() {
        advance(OffsetDateTime.now(ZoneOffset.UTC));
    }

    // Supplying the time directly lets tests exercise every age boundary without sleeps.
    public void advance(OffsetDateTime now) {
        Set<String> due = worklist.due(now.minus(grace), batchSize);
        if (due == null) {
            return;
        }
        for (String id : due) {
            try {
                advanceOne(id, now);
            } catch (RuntimeException ex) {
                log.warn("Settlement reconciliation will retry event={}", id, ex);
                defer(id, now.plus(RETRY));
            }
        }
    }

    private void advanceOne(String id, OffsetDateTime now) {
        String payload = worklist.payload(id);
        SettlementWorklist.Owner owner = worklist.owner(id);
        BillingMoneyEvent event = readEvent(id, payload);
        if (owner == null && event != null) {
            owner = new SettlementWorklist.Owner(event.orgId(), event.occurredAt());
        }
        if (owner == null) {
            log.error("P1 closing a legacy settlement without recoverable ownership metadata event={}", id);
            worklist.resolve(id);
            return;
        }
        UUID eventId = UUID.fromString(id);
        OffsetDateTime occurredAt = owner.occurredAt();
        if (ledgerFolder.resolved(eventId, occurredAt)) {
            worklist.resolve(id);
            return;
        }
        if (due(occurredAt, WRITE_OFF, now)) {
            if (ledgerFolder.writeOff(owner.orgId(), eventId, occurredAt)) {
                meterRegistry.counter("ledger_degraded_resolved_total", "level", "L4").increment();
                log.error("P1 wrote off unconfirmed settlement org={} event={}", owner.orgId(), eventId);
            }
            worklist.resolve(id);
            return;
        }

        boolean enteredDelivery = ledgerFolder.enteredDelivery(eventId);
        if (due(occurredAt, ADJUST, now)) {
            if (!enteredDelivery && sameEvent(event, owner) && worklist.provesLiveDebit(id)) {
                var result = ledgerFolder.repair(event);
                if (result != LedgerFolder.RepairResult.IN_DELIVERY) {
                    if (result == LedgerFolder.RepairResult.ADJUSTED) {
                        meterRegistry.counter("ledger_degraded_resolved_total", "level", "L3").increment();
                        log.warn("P2 booked missing settlement from live debit org={} event={}", owner.orgId(), eventId);
                    }
                    worklist.resolve(id);
                    return;
                }
            }
            defer(id, occurredAt.plus(WRITE_OFF));
            return;
        }

        if (due(occurredAt, REPAIR_LINK, now)) {
            if (enteredDelivery) {
                repairDelivery(eventId, owner.orgId());
            }
            defer(id, occurredAt.plus(ADJUST));
            return;
        }

        if (!enteredDelivery && sameEvent(event, owner)) {
            outboxEventRepository.insertIfAbsent(new OutboxEvent(
                    eventId, "billing", owner.orgId().toString(), "CONSUME", payload, occurredAt));
        }
        defer(id, occurredAt.plus(REPAIR_LINK));
    }

    private void repairDelivery(UUID eventId, UUID orgId) {
        var payload = ledgerFolder.recordedPayload(eventId);
        if (payload.isEmpty()) {
            log.error("P1 settlement lies below purge watermark. Check billing consumer offsets for org={} event={}",
                    orgId, eventId);
            return;
        }
        kafkaTemplate.send(KafkaTopics.CDC_BILLING, orgId.toString(), payload.orElseThrow())
                .orTimeout(5, TimeUnit.SECONDS).whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        log.error("P1 billing partition replay failed for org={} event={}", orgId, eventId, ex);
                    }
                });
    }

    private BillingMoneyEvent readEvent(String id, String payload) {
        if (payload == null) {
            return null;
        }
        try {
            BillingMoneyEvent event = objectMapper.readValue(payload, BillingMoneyEvent.class);
            if (event.eventType() == BillingMoneyEvent.EventType.CONSUME && event.eventId() != null
                    && id.equals(event.eventId().toString()) && event.orgId() != null && event.occurredAt() != null) {
                return event;
            }
        } catch (JsonProcessingException ex) {
            log.warn("Cannot read the Redis copy for settlement {}", id, ex);
        }
        return null;
    }

    private void defer(String id, OffsetDateTime at) {
        worklist.defer(id, at.minus(grace));
    }

    private static boolean sameEvent(BillingMoneyEvent event, SettlementWorklist.Owner owner) {
        return event != null && owner.orgId().equals(event.orgId()) && owner.occurredAt().isEqual(event.occurredAt());
    }

    private static boolean due(OffsetDateTime at, Duration age, OffsetDateTime now) {
        return !now.isBefore(at.plus(age));
    }
}
