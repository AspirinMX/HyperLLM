package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.BillingAlertRulesRecord;
import app.pagefault.hyperllm.domain.model.billing.BillingAlertRule;
import app.pagefault.hyperllm.domain.model.enums.AlertType;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.repository.BillingAlertRuleRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.BILLING_ALERT_RULES;

@Repository
@RequiredArgsConstructor
public class JooqBillingAlertRuleRepository implements BillingAlertRuleRepository {

    private final DSLContext dsl;

    @Override
    public void insert(BillingAlertRule rule) {
        dsl.insertInto(BILLING_ALERT_RULES)
                .set(BILLING_ALERT_RULES.ID, rule.id())
                .set(BILLING_ALERT_RULES.ORG_ID, rule.orgId())
                .set(BILLING_ALERT_RULES.PROJECT_ID, rule.projectId())
                .set(BILLING_ALERT_RULES.ALERT_TYPE, rule.alertType().name())
                .set(BILLING_ALERT_RULES.THRESHOLD_MICROS, rule.thresholdMicros())
                .set(BILLING_ALERT_RULES.ENDPOINT_IDS, PgJooqSupport.toUuidArray(rule.endpointIds()))
                .set(BILLING_ALERT_RULES.COOLDOWN_MINUTES, rule.cooldownMinutes())
                .set(BILLING_ALERT_RULES.LAST_TRIGGERED_AT, rule.lastTriggeredAt())
                .set(BILLING_ALERT_RULES.STATUS, rule.status().name())
                .set(BILLING_ALERT_RULES.DELETED_AT, rule.deletedAt())
                .set(BILLING_ALERT_RULES.VERSION, rule.version())
                .set(BILLING_ALERT_RULES.CREATED_AT, rule.createdAt())
                .set(BILLING_ALERT_RULES.UPDATED_AT, rule.updatedAt())
                .execute();
    }

    @Override
    public Optional<BillingAlertRule> findById(UUID id) {
        return dsl.selectFrom(BILLING_ALERT_RULES)
                .where(BILLING_ALERT_RULES.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<BillingAlertRule> listActiveByOrg(UUID orgId) {
        return dsl.selectFrom(BILLING_ALERT_RULES)
                .where(BILLING_ALERT_RULES.ORG_ID.eq(orgId))
                .and(BILLING_ALERT_RULES.STATUS.eq(ToggleStatus.ACTIVE.name()))
                .and(BILLING_ALERT_RULES.DELETED_AT.isNull())
                .orderBy(BILLING_ALERT_RULES.CREATED_AT.asc())
                .fetch(this::toDomain);
    }

    @Override
    public List<BillingAlertRule> listActivePage(int limit, int offset) {
        return dsl.selectFrom(BILLING_ALERT_RULES)
                .where(BILLING_ALERT_RULES.STATUS.eq(ToggleStatus.ACTIVE.name()))
                .and(BILLING_ALERT_RULES.DELETED_AT.isNull())
                .orderBy(BILLING_ALERT_RULES.CREATED_AT.asc())
                .limit(limit)
                .offset(offset)
                .fetch(this::toDomain);
    }

    @Override
    public boolean touchLastTriggered(UUID id, OffsetDateTime triggeredAt) {
        int updated = dsl.update(BILLING_ALERT_RULES)
                .set(BILLING_ALERT_RULES.LAST_TRIGGERED_AT, triggeredAt)
                .set(BILLING_ALERT_RULES.UPDATED_AT, triggeredAt)
                .where(BILLING_ALERT_RULES.ID.eq(id))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean update(BillingAlertRule next, long expectedVersion) {
        int updated = dsl.update(BILLING_ALERT_RULES)
                .set(BILLING_ALERT_RULES.PROJECT_ID, next.projectId())
                .set(BILLING_ALERT_RULES.ALERT_TYPE, next.alertType().name())
                .set(BILLING_ALERT_RULES.THRESHOLD_MICROS, next.thresholdMicros())
                .set(BILLING_ALERT_RULES.ENDPOINT_IDS, PgJooqSupport.toUuidArray(next.endpointIds()))
                .set(BILLING_ALERT_RULES.COOLDOWN_MINUTES, next.cooldownMinutes())
                .set(BILLING_ALERT_RULES.STATUS, next.status().name())
                .set(BILLING_ALERT_RULES.VERSION, BILLING_ALERT_RULES.VERSION.plus(1))
                .set(BILLING_ALERT_RULES.UPDATED_AT, next.updatedAt())
                .where(BILLING_ALERT_RULES.ID.eq(next.id()))
                .and(BILLING_ALERT_RULES.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt) {
        int updated = dsl.update(BILLING_ALERT_RULES)
                .set(BILLING_ALERT_RULES.DELETED_AT, deletedAt)
                .set(BILLING_ALERT_RULES.VERSION, BILLING_ALERT_RULES.VERSION.plus(1))
                .set(BILLING_ALERT_RULES.UPDATED_AT, deletedAt)
                .where(BILLING_ALERT_RULES.ID.eq(id))
                .and(BILLING_ALERT_RULES.VERSION.eq(expectedVersion))
                .and(BILLING_ALERT_RULES.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    private BillingAlertRule toDomain(BillingAlertRulesRecord r) {
        return new BillingAlertRule(
                r.getId(),
                r.getOrgId(),
                r.getProjectId(),
                PgJooqSupport.toEnum(AlertType.class, r.getAlertType()),
                r.getThresholdMicros(),
                PgJooqSupport.fromUuidArray(r.getEndpointIds()),
                r.getCooldownMinutes(),
                r.getLastTriggeredAt(),
                PgJooqSupport.toEnum(ToggleStatus.class, r.getStatus()),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
