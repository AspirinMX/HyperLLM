package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.OUTBOX_EVENTS;
import static app.pagefault.hyperllm.domain.jooq.Tables.OUTBOX_WATERMARK;

@Repository
@RequiredArgsConstructor
public class JooqOutboxEventRepository implements OutboxEventRepository {

    private final DSLContext dsl;

    @Override
    public boolean insertIfAbsent(OutboxEvent event) {
        return insertStatement(event).execute() == 1;
    }

    @Override
    public int insertIfAbsent(List<OutboxEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }
        List<Query> statements = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            statements.add(insertStatement(event));
        }
        int[] affected = dsl.batch(statements).execute();
        int inserted = 0;
        for (int rowCount : affected) {
            inserted += Math.max(rowCount, 0);
        }
        return inserted;
    }

    @Override
    public boolean exists(UUID eventId) {
        return dsl.fetchExists(dsl.selectOne().from(OUTBOX_EVENTS).where(OUTBOX_EVENTS.ID.eq(eventId)));
    }

    @Override
    public Optional<UUID> watermark() {
        return dsl.select(OUTBOX_WATERMARK.LAST_EVENT_ID)
                .from(OUTBOX_WATERMARK)
                .where(OUTBOX_WATERMARK.ID.isTrue())
                .fetchOptional(OUTBOX_WATERMARK.LAST_EVENT_ID);
    }

    @Override
    public Optional<OffsetDateTime> watermarkUpdatedAt() {
        return dsl.select(OUTBOX_WATERMARK.UPDATED_AT)
                .from(OUTBOX_WATERMARK)
                .where(OUTBOX_WATERMARK.ID.isTrue())
                .fetchOptional(OUTBOX_WATERMARK.UPDATED_AT);
    }

    @Override
    public void advanceWatermark(UUID eventId) {
        dsl.update(OUTBOX_WATERMARK)
                .set(OUTBOX_WATERMARK.LAST_EVENT_ID, eventId)
                .set(OUTBOX_WATERMARK.UPDATED_AT, OffsetDateTime.now())
                .where(OUTBOX_WATERMARK.ID.isTrue()
                        .and(OUTBOX_WATERMARK.LAST_EVENT_ID.isNull()
                                .or(OUTBOX_WATERMARK.LAST_EVENT_ID.lessThan(eventId))))
                .execute();
    }

    @Override
    public int deleteUpTo(UUID watermark, int limit) {
        List<UUID> doomed = dsl.select(OUTBOX_EVENTS.ID)
                .from(OUTBOX_EVENTS)
                .where(OUTBOX_EVENTS.ID.lessOrEqual(watermark))
                .orderBy(OUTBOX_EVENTS.ID)
                .limit(limit)
                .fetch(OUTBOX_EVENTS.ID);
        if (doomed.isEmpty()) {
            return 0;
        }
        return dsl.deleteFrom(OUTBOX_EVENTS).where(OUTBOX_EVENTS.ID.in(doomed)).execute();
    }

    @Override
    public int deleteOlderThan(OffsetDateTime cutoff) {
        return dsl.deleteFrom(OUTBOX_EVENTS)
                .where(OUTBOX_EVENTS.CREATED_AT.lt(cutoff))
                .execute();
    }

    private Query insertStatement(OutboxEvent event) {
        return dsl.insertInto(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.ID, event.id())
                .set(OUTBOX_EVENTS.AGGREGATETYPE, event.aggregateType())
                .set(OUTBOX_EVENTS.AGGREGATEID, event.aggregateId())
                .set(OUTBOX_EVENTS.TYPE, event.type())
                .set(OUTBOX_EVENTS.PAYLOAD, PgJooqSupport.toJsonb(event.payload()))
                .set(OUTBOX_EVENTS.CREATED_AT, event.createdAt())
                .onConflictDoNothing();
    }
}
