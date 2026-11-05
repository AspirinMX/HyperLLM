package app.pagefault.hyperllm.admin.alert;

import app.pagefault.hyperllm.common.id.UuidV7;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.NOTIFICATION_DELIVERIES;

/** Durable queue of alert notifications, shared by every admin instance. */
@Component
@RequiredArgsConstructor
public class AlertDeliveryStore {

    public record Delivery(
            UUID id,
            UUID ruleId,
            UUID triggerId,
            UUID orgId,
            UUID endpointId,
            String alertType,
            UUID projectId,
            String subject,
            String payloadJson,
            String status,
            int attempts,
            OffsetDateTime nextAttemptAt,
            String lastErrorClass
    ) {
    }

    private static final org.jooq.Field<OffsetDateTime> NEXT = NOTIFICATION_DELIVERIES.NEXT_ATTEMPT_AT;

    private final DSLContext dsl;

    public void enqueue(UUID ruleId, UUID triggerId, UUID orgId, UUID endpointId, String alertType,
                        UUID projectId, String subject, String payloadJson, OffsetDateTime now) {
        dsl.insertInto(NOTIFICATION_DELIVERIES)
                .set(NOTIFICATION_DELIVERIES.ID, UuidV7.next())
                .set(NOTIFICATION_DELIVERIES.RULE_ID, ruleId)
                .set(NOTIFICATION_DELIVERIES.TRIGGER_ID, triggerId)
                .set(NOTIFICATION_DELIVERIES.ORG_ID, orgId)
                .set(NOTIFICATION_DELIVERIES.ENDPOINT_ID, endpointId)
                .set(NOTIFICATION_DELIVERIES.ALERT_TYPE, alertType)
                .set(NOTIFICATION_DELIVERIES.PROJECT_ID, projectId)
                .set(NOTIFICATION_DELIVERIES.SUBJECT, subject)
                .set(NOTIFICATION_DELIVERIES.PAYLOAD_JSON, org.jooq.JSONB.valueOf(payloadJson))
                .set(NOTIFICATION_DELIVERIES.STATUS, "PENDING")
                .set(NOTIFICATION_DELIVERIES.ATTEMPTS, 0)
                .set(NEXT, now)
                .set(NOTIFICATION_DELIVERIES.CREATED_AT, now)
                .set(NOTIFICATION_DELIVERIES.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute();
    }

    /**
     * Claims a disjoint set of due deliveries by pushing their lock forward in one transaction.
     * Another instance skips the rows whose lock is still in the future.
     */
    @Transactional
    public List<UUID> claimDue(int batch, OffsetDateTime now) {
        List<UUID> ids = dsl.select(NOTIFICATION_DELIVERIES.ID)
                .from(NOTIFICATION_DELIVERIES)
                .where(NOTIFICATION_DELIVERIES.STATUS.in("PENDING", "RETRY"))
                .and(NEXT.le(now))
                .and(NOTIFICATION_DELIVERIES.LOCKED_UNTIL.isNull()
                        .or(NOTIFICATION_DELIVERIES.LOCKED_UNTIL.lt(now)))
                .orderBy(NEXT)
                .limit(batch)
                .forUpdate()
                .skipLocked()
                .fetch(NOTIFICATION_DELIVERIES.ID);
        if (!ids.isEmpty()) {
            dsl.update(NOTIFICATION_DELIVERIES)
                    .set(NOTIFICATION_DELIVERIES.LOCKED_UNTIL, now.plusMinutes(2))
                    .where(NOTIFICATION_DELIVERIES.ID.in(ids))
                    .execute();
        }
        return ids;
    }

    public Optional<Delivery> find(UUID id) {
        return dsl.selectFrom(NOTIFICATION_DELIVERIES)
                .where(NOTIFICATION_DELIVERIES.ID.eq(id))
                .fetchOptional(this::toDelivery);
    }

    public void markSent(UUID id, int attempts, OffsetDateTime at) {
        dsl.update(NOTIFICATION_DELIVERIES)
                .set(NOTIFICATION_DELIVERIES.STATUS, "SENT")
                .set(NOTIFICATION_DELIVERIES.ATTEMPTS, attempts)
                .set(NOTIFICATION_DELIVERIES.LOCKED_UNTIL, (OffsetDateTime) null)
                .set(NOTIFICATION_DELIVERIES.LAST_ERROR_CLASS, (String) null)
                .set(NOTIFICATION_DELIVERIES.LAST_ERROR_DETAIL, (String) null)
                .set(NOTIFICATION_DELIVERIES.SENT_AT, at)
                .set(NOTIFICATION_DELIVERIES.UPDATED_AT, at)
                .where(NOTIFICATION_DELIVERIES.ID.eq(id))
                .execute();
    }

    public void markFailure(UUID id, int attempts, String status, OffsetDateTime nextAttemptAt,
                            String errorClass, String errorDetail) {
        dsl.update(NOTIFICATION_DELIVERIES)
                .set(NOTIFICATION_DELIVERIES.STATUS, status)
                .set(NOTIFICATION_DELIVERIES.ATTEMPTS, attempts)
                .set(NEXT, nextAttemptAt == null ? OffsetDateTime.now(ZoneOffset.UTC).plusDays(3650) : nextAttemptAt)
                .set(NOTIFICATION_DELIVERIES.LOCKED_UNTIL, (OffsetDateTime) null)
                .set(NOTIFICATION_DELIVERIES.LAST_ERROR_CLASS, errorClass)
                .set(NOTIFICATION_DELIVERIES.LAST_ERROR_DETAIL,
                        errorDetail == null ? null : errorDetail.substring(0, Math.min(512, errorDetail.length())))
                .set(NOTIFICATION_DELIVERIES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(NOTIFICATION_DELIVERIES.ID.eq(id))
                .execute();
    }

    public List<Delivery> list(String status, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        if (status == null || status.isBlank()) {
            return dsl.selectFrom(NOTIFICATION_DELIVERIES)
                    .orderBy(NOTIFICATION_DELIVERIES.CREATED_AT.desc())
                    .limit(bounded)
                    .fetch(this::toDelivery);
        }
        return dsl.selectFrom(NOTIFICATION_DELIVERIES)
                .where(NOTIFICATION_DELIVERIES.STATUS.eq(status))
                .orderBy(NOTIFICATION_DELIVERIES.CREATED_AT.desc())
                .limit(bounded)
                .fetch(this::toDelivery);
    }

    public boolean retry(UUID id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = dsl.update(NOTIFICATION_DELIVERIES)
                .set(NOTIFICATION_DELIVERIES.STATUS, "PENDING")
                .set(NOTIFICATION_DELIVERIES.ATTEMPTS, 0)
                .set(NEXT, now)
                .set(NOTIFICATION_DELIVERIES.LOCKED_UNTIL, (OffsetDateTime) null)
                .set(NOTIFICATION_DELIVERIES.LAST_ERROR_CLASS, (String) null)
                .set(NOTIFICATION_DELIVERIES.LAST_ERROR_DETAIL, (String) null)
                .set(NOTIFICATION_DELIVERIES.UPDATED_AT, now)
                .where(NOTIFICATION_DELIVERIES.ID.eq(id))
                .and(NOTIFICATION_DELIVERIES.STATUS.in("RETRY", "DEAD"))
                .execute();
        return updated == 1;
    }

    private Delivery toDelivery(Record row) {
        return new Delivery(
                row.get(NOTIFICATION_DELIVERIES.ID),
                row.get(NOTIFICATION_DELIVERIES.RULE_ID),
                row.get(NOTIFICATION_DELIVERIES.TRIGGER_ID),
                row.get(NOTIFICATION_DELIVERIES.ORG_ID),
                row.get(NOTIFICATION_DELIVERIES.ENDPOINT_ID),
                row.get(NOTIFICATION_DELIVERIES.ALERT_TYPE),
                row.get(NOTIFICATION_DELIVERIES.PROJECT_ID),
                row.get(NOTIFICATION_DELIVERIES.SUBJECT),
                String.valueOf(row.get(NOTIFICATION_DELIVERIES.PAYLOAD_JSON)),
                row.get(NOTIFICATION_DELIVERIES.STATUS),
                row.get(NOTIFICATION_DELIVERIES.ATTEMPTS),
                row.get(NEXT),
                row.get(NOTIFICATION_DELIVERIES.LAST_ERROR_CLASS));
    }

}
