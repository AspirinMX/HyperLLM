package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append only transport table. There is deliberately no update operation: an event row is written
 * once and later removed by the watermark purge, never amended.
 */
public interface OutboxEventRepository {

    /** @return true when inserted, false when the event id was already present. */
    boolean insertIfAbsent(OutboxEvent event);

    /** @return rows actually inserted; existing ids are skipped rather than failing the batch. */
    int insertIfAbsent(List<OutboxEvent> events);

    /** Whether an event id is still present, i.e. it has not been covered by the purge watermark. */
    boolean exists(UUID eventId);

    /** Consumers' delivery watermark, empty before the first confirmation. */
    Optional<UUID> watermark();

    /** When the watermark last moved; used to decide that delivery has stalled. */
    Optional<java.time.OffsetDateTime> watermarkUpdatedAt();

    /** Advances the watermark; it never moves backwards. */
    void advanceWatermark(UUID eventId);

    /** Deletes up to {@code limit} rows whose id is at or below the watermark. */
    int deleteUpTo(UUID watermark, int limit);

    int deleteOlderThan(OffsetDateTime cutoff);
}
