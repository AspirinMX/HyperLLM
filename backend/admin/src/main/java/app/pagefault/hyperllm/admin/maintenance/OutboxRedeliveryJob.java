package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Re-appends settlements the gateway could not confirm were written.
 *
 * <p>The settle script leaves a copy of the payload and adds the event to a pending index in the
 * same atomic step that moves the money, so a process that dies between the deduction and the queue
 * still leaves a trace behind. This job turns that trace back into a row; the insert is keyed by the
 * event id, so re-adding an event the gateway did manage to write is a no-op.
 *
 * <p>An entry whose payload has already expired cannot be recovered here. It is dropped from the
 * index and logged, and the fold-position worklist is what surfaces it later.
 */
@Slf4j
@Component
public class OutboxRedeliveryJob {

    private final StringRedisTemplate redisTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final long graceSeconds;
    private final int batchSize;

    public OutboxRedeliveryJob(
            StringRedisTemplate redisTemplate,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            @Value("${hyperllm.outbox.redelivery-grace-seconds:60}") long graceSeconds,
            @Value("${hyperllm.outbox.redelivery-batch:500}") int batchSize
    ) {
        this.redisTemplate = redisTemplate;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.graceSeconds = graceSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${hyperllm.outbox.redelivery-delay:PT30S}")
    public void redeliver() {
        long cutoff = Instant.now().getEpochSecond() - graceSeconds;
        Set<String> pending = redisTemplate.opsForZSet()
                .rangeByScore(GatewayRedisKeys.settlePending(), 0, cutoff * 1000d, 0, batchSize);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (String eventId : pending) {
            redeliverOne(eventId);
        }
    }

    private void redeliverOne(String eventId) {
        String payload = redisTemplate.opsForValue().get(GatewayRedisKeys.settleEvent(eventId));
        if (payload == null || payload.isBlank()) {
            log.error("settlement payload expired before it could be re-appended event={}", eventId);
            redisTemplate.opsForZSet().remove(GatewayRedisKeys.settlePending(), eventId);
            return;
        }
        try {
            OutboxEvent row = new OutboxEvent(
                    UUID.fromString(eventId),
                    "billing",
                    readOrgId(payload),
                    readEventType(payload),
                    payload,
                    OffsetDateTime.now(ZoneOffset.UTC));
            // A row that already exists means the gateway did write it; either way it is delivered.
            outboxEventRepository.insertIfAbsent(List.of(row));
            redisTemplate.opsForZSet().remove(GatewayRedisKeys.settlePending(), eventId);
        } catch (RuntimeException ex) {
            log.warn("re-appending settlement failed event={}", eventId, ex);
        }
    }

    private String readOrgId(String payload) {
        return readField(payload, "orgId");
    }

    private String readEventType(String payload) {
        return readField(payload, "eventType");
    }

    private String readField(String payload, String field) {
        try {
            return objectMapper.readTree(payload).path(field).asText("UNKNOWN");
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }
}
