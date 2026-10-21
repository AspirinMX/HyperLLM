package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the outbox table bounded by deleting what has already been delivered.
 *
 * <p>Deletion is storage housekeeping, not delivery: the connector reads the write-ahead log, so a
 * deleted row has already left the table as a change. That is why there is no retention period to
 * tune - the watermark of the consumer is the only boundary that matters.
 *
 * <p>When the watermark stops moving, delivery has stalled and the table would grow without bound.
 * Past the stall deadline rows are removed by age and the incident is logged at error level, because
 * the signal that matters is "the watermark stopped", not "the table is large".
 */
@Slf4j
@Component
public class OutboxPurgeJob {

    private final OutboxEventRepository outboxEventRepository;
    private final int batchRows;
    private final int stallHours;

    public OutboxPurgeJob(
            OutboxEventRepository outboxEventRepository,
            @Value("${hyperllm.outbox.purge-batch-rows:5000}") int batchRows,
            @Value("${hyperllm.outbox.watermark-stall-hours:24}") int stallHours
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.batchRows = batchRows;
        this.stallHours = stallHours;
    }

    @Scheduled(fixedDelayString = "${hyperllm.outbox.purge-delay:PT30S}")
    public void purge() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<UUID> watermark = outboxEventRepository.watermark();
        watermark.ifPresent(this::deleteUpTo);

        Optional<OffsetDateTime> updatedAt = outboxEventRepository.watermarkUpdatedAt();
        boolean stalled = updatedAt.isEmpty() || updatedAt.get().isBefore(now.minusHours(stallHours));
        if (!stalled) {
            return;
        }
        // Delivery stopped. Rows are still removable by age because the connector reads the log, not
        // the table, but the incident is what gets reported, not the table size.
        int removed = outboxEventRepository.deleteOlderThan(now.minusHours(stallHours));
        if (removed > 0) {
            log.error("outbox watermark stalled for more than {}h, removed {} rows by age", stallHours, removed);
        } else if (watermark.isEmpty()) {
            log.warn("outbox watermark has never advanced");
        }
    }

    private void deleteUpTo(UUID watermark) {
        int total = 0;
        while (true) {
            int removed = outboxEventRepository.deleteUpTo(watermark, batchRows);
            total += removed;
            if (removed < batchRows) {
                break;
            }
        }
        if (total > 0) {
            log.debug("purged {} delivered outbox rows up to {}", total, watermark);
        }
    }
}
