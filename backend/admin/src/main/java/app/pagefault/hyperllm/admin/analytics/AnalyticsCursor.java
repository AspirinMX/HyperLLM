package app.pagefault.hyperllm.admin.analytics;

import app.pagefault.hyperllm.common.id.UuidV7;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static app.pagefault.hyperllm.domain.jooq.Tables.ANALYTICS_HOURS;
import static app.pagefault.hyperllm.domain.jooq.Tables.ANALYTICS_INGEST_POSITIONS;
import static app.pagefault.hyperllm.domain.jooq.Tables.ANALYTICS_REJECTS;

/**
 * PostgreSQL side of the report pipeline: committed delivery positions, the hourly work queue and
 * the records of facts that could not be accepted.
 *
 * <p>Positions are written in the same transaction that marks the hours dirty, after ClickHouse has
 * accepted the rows. The Kafka acknowledgement follows, so a crash before the commit simply replays
 * the batch; a crash after it re-inserts the same facts, which the rollup deduplicates.
 */
@Component
@RequiredArgsConstructor
public class AnalyticsCursor {

    private static final org.jooq.Field<Long> NEXT = ANALYTICS_INGEST_POSITIONS.NEXT_OFFSET;

    private final DSLContext dsl;

    public record Reject(int partition, long offset, String requestId, String errorClass, String detail) {
    }

    @Transactional
    public void advance(Map<Integer, Long> nextOffsets, Set<OffsetDateTime> hours, List<Reject> rejects) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Map.Entry<Integer, Long> entry : new LinkedHashMap<>(nextOffsets).entrySet()) {
            dsl.insertInto(ANALYTICS_INGEST_POSITIONS)
                    .set(ANALYTICS_INGEST_POSITIONS.KAFKA_PARTITION, entry.getKey())
                    .set(NEXT, entry.getValue())
                    .set(ANALYTICS_INGEST_POSITIONS.UPDATED_AT, now)
                    .onConflict(ANALYTICS_INGEST_POSITIONS.KAFKA_PARTITION)
                    .doUpdate()
                    .set(NEXT, org.jooq.impl.DSL.field("greatest({0}, {1})", Long.class,
                            NEXT, org.jooq.impl.DSL.val(entry.getValue())))
                    .set(ANALYTICS_INGEST_POSITIONS.UPDATED_AT, now)
                    .execute();
        }
        for (OffsetDateTime hour : hours) {
            dsl.insertInto(ANALYTICS_HOURS)
                    .set(ANALYTICS_HOURS.HOUR_START, hour)
                    .set(ANALYTICS_HOURS.DIRTY, true)
                    .onConflict(ANALYTICS_HOURS.HOUR_START)
                    .doUpdate()
                    .set(ANALYTICS_HOURS.DIRTY, true)
                    .execute();
        }
        for (Reject reject : rejects) {
            dsl.insertInto(ANALYTICS_REJECTS)
                    .set(ANALYTICS_REJECTS.ID, UuidV7.next())
                    .set(ANALYTICS_REJECTS.KAFKA_PARTITION, reject.partition())
                    .set(ANALYTICS_REJECTS.KAFKA_OFFSET, reject.offset())
                    .set(ANALYTICS_REJECTS.REQUEST_ID, reject.requestId())
                    .set(ANALYTICS_REJECTS.ERROR_CLASS, reject.errorClass())
                    .set(ANALYTICS_REJECTS.DETAIL, reject.detail())
                    .set(ANALYTICS_REJECTS.CREATED_AT, now)
                    .onConflictDoNothing()
                    .execute();
        }
    }

    /**
     * Takes ownership of the oldest dirty hours by clearing their flag before the computation. An
     * event that arrives while the hour is being rolled up sets the flag again, so the next run
     * recomputes it and the reader never sees a half-applied version.
     */
    @Transactional
    public List<OffsetDateTime> claimDirtyHours(int limit) {
        List<OffsetDateTime> hours = dsl.fetch(
                        "select hour_start from analytics_hours where dirty "
                                + "order by hour_start limit ? for update skip locked", limit)
                .into(OffsetDateTime.class);
        if (!hours.isEmpty()) {
            dsl.update(ANALYTICS_HOURS)
                    .set(ANALYTICS_HOURS.DIRTY, false)
                    .where(ANALYTICS_HOURS.HOUR_START.in(hours))
                    .execute();
        }
        return hours;
    }

    public Map<Integer, Long> positions() {
        Map<Integer, Long> positions = new LinkedHashMap<>();
        dsl.select(ANALYTICS_INGEST_POSITIONS.KAFKA_PARTITION, NEXT)
                .from(ANALYTICS_INGEST_POSITIONS)
                .orderBy(ANALYTICS_INGEST_POSITIONS.KAFKA_PARTITION)
                .fetch()
                .forEach(row -> positions.put(((Number) row.get(0)).intValue(),
                        ((Number) row.get(1)).longValue()));
        return positions;
    }

    public long nextVersion() {
        org.jooq.Record row = dsl.fetchOne("select nextval('analytics_rollup_version_seq')");
        if (row == null || row.get(0) == null) {
            throw new IllegalStateException("The rollup version sequence returned nothing");
        }
        return ((Number) row.get(0)).longValue();
    }

    public void published(OffsetDateTime hour, long version) {
        dsl.update(ANALYTICS_HOURS)
                .set(ANALYTICS_HOURS.PUBLISHED_VERSION, version)
                .set(ANALYTICS_HOURS.LAST_ROLLED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(ANALYTICS_HOURS.HOUR_START.eq(hour))
                .execute();
    }

    public void markDirty(OffsetDateTime hour) {
        dsl.update(ANALYTICS_HOURS)
                .set(ANALYTICS_HOURS.DIRTY, true)
                .where(ANALYTICS_HOURS.HOUR_START.eq(hour))
                .execute();
    }

    public List<Map<String, Object>> recentRejects(int limit) {
        return dsl.selectFrom(ANALYTICS_REJECTS)
                .orderBy(ANALYTICS_REJECTS.CREATED_AT.desc())
                .limit(limit)
                .fetch()
                .map(row -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("partition", row.getKafkaPartition());
                    value.put("offset", row.getKafkaOffset());
                    value.put("requestId", row.getRequestId());
                    value.put("errorClass", row.getErrorClass());
                    value.put("detail", row.getDetail());
                    value.put("createdAt", row.getCreatedAt());
                    return value;
                });
    }

}
