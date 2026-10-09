package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;

import java.time.OffsetDateTime;

public interface OutboxEventRepository {

    /** @return true when inserted, false when the event id was already present. */
    boolean insertIfAbsent(OutboxEvent event);

    int deleteOlderThan(OffsetDateTime cutoff);
}
