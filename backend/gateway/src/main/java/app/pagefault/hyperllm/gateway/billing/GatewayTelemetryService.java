package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.common.messaging.LlmRequestEvent;
import app.pagefault.hyperllm.gateway.outbox.OutboxQueue;
import app.pagefault.hyperllm.gateway.outbox.PendingMoneyEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Hands the two kinds of telemetry to their transports.
 *
 * <p>A money event is appended to the outbox queue, never sent to Kafka directly: the ledger is fed
 * by reading the table's write-ahead log, so the row is the message. Request telemetry carries no
 * money and keeps its direct path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayTelemetryService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxQueue outboxQueue;

    public void emitSettlementAndRequestEvent(BillingMoneyEvent settlement, LlmRequestEvent requestEvent) {
        if (settlement != null && changesSomething(settlement)) {
            enqueueSettlement(settlement);
        }
        if (requestEvent != null) {
            sendAsync(KafkaTopics.LLM_REQUEST_EVENTS, requestEvent.orgId().toString(), requestEvent);
        }
    }

    /**
     * Any of the three buckets moving is enough to require a ledger row; a charge covered entirely
     * by pack units changes nothing else and still has to be booked.
     */
    private static boolean changesSomething(BillingMoneyEvent event) {
        return event.cashDeltaMicros() != 0L
                || event.bonusDeltaMicros() != 0L
                || event.packDeltaUnits() != 0L;
    }

    private void enqueueSettlement(BillingMoneyEvent settlement) {
        try {
            String payload = objectMapper.writeValueAsString(settlement);
            boolean queued = outboxQueue.offer(new PendingMoneyEvent(
                    settlement.eventId(),
                    settlement.orgId().toString(),
                    settlement.eventType().name(),
                    payload,
                    settlement.occurredAt()));
            if (!queued) {
                log.error("outbox queue full, settlement not queued event={} org={}",
                        settlement.eventId(), settlement.orgId());
            }
        } catch (Exception ex) {
            log.error("cannot serialise settlement event={}", settlement.eventId(), ex);
        }
    }

    private void sendAsync(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, key, json).whenComplete((res, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish {} event to Kafka topic {}", key, topic, ex);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to serialize Kafka event for topic {}", topic, ex);
        }
    }
}
