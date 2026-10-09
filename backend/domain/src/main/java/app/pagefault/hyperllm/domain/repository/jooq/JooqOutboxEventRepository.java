package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.OutboxEventsRecord;
import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

import static app.pagefault.hyperllm.domain.jooq.Tables.OUTBOX_EVENTS;

@Repository
@RequiredArgsConstructor
public class JooqOutboxEventRepository implements OutboxEventRepository {

    private final DSLContext dsl;

    @Override
    public boolean insertIfAbsent(OutboxEvent event) {
        return dsl.insertInto(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.ID, event.id())
                .set(OUTBOX_EVENTS.AGGREGATETYPE, event.aggregateType())
                .set(OUTBOX_EVENTS.AGGREGATEID, event.aggregateId())
                .set(OUTBOX_EVENTS.TYPE, event.type())
                .set(OUTBOX_EVENTS.PAYLOAD, PgJooqSupport.toJsonb(event.payload()))
                .set(OUTBOX_EVENTS.CREATED_AT, event.createdAt())
                .onConflictDoNothing()
                .execute() == 1;
    }

    @Override
    public int deleteOlderThan(OffsetDateTime cutoff) {
        return dsl.deleteFrom(OUTBOX_EVENTS)
                .where(OUTBOX_EVENTS.CREATED_AT.lt(cutoff))
                .execute();
    }
}
