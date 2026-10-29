package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.BonusGrantsRecord;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.GrantSource;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.BONUS_GRANTS;

@Repository
@RequiredArgsConstructor
public class JooqBonusGrantRepository implements BonusGrantRepository {

    private final DSLContext dsl;

    @Override
    public void insert(BonusGrant grant) {
        dsl.insertInto(BONUS_GRANTS)
                .set(BONUS_GRANTS.ID, grant.id())
                .set(BONUS_GRANTS.ORG_ID, grant.orgId())
                .set(BONUS_GRANTS.GRANT_SOURCE, grant.grantSource().name())
                .set(BONUS_GRANTS.VOUCHER_ID, grant.voucherId())
                .set(BONUS_GRANTS.REDEMPTION_CODE_ID, grant.redemptionCodeId())
                .set(BONUS_GRANTS.APPLICABLE_MODELS, PgJooqSupport.toTextArray(grant.applicableModels()))
                .set(BONUS_GRANTS.INITIAL_MICROS, grant.initialMicros())
                .set(BONUS_GRANTS.REMAINING_MICROS, grant.remainingMicros())
                .set(BONUS_GRANTS.EXPIRES_AT, grant.expiresAt())
                .set(BONUS_GRANTS.STATUS, grant.status().name())
                .set(BONUS_GRANTS.VERSION, grant.version())
                .set(BONUS_GRANTS.CREATED_AT, grant.createdAt())
                .set(BONUS_GRANTS.UPDATED_AT, grant.updatedAt())
                .execute();
    }

    @Override
    public Optional<BonusGrant> findById(UUID id) {
        return dsl.selectFrom(BONUS_GRANTS)
                .where(BONUS_GRANTS.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<BonusGrant> listConsumableByOrgAndModel(UUID orgId, String modelCode, OffsetDateTime at) {
        return dsl.selectFrom(BONUS_GRANTS)
                .where(BONUS_GRANTS.ORG_ID.eq(orgId))
                .and(BONUS_GRANTS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .and(BONUS_GRANTS.EXPIRES_AT.isNull().or(BONUS_GRANTS.EXPIRES_AT.gt(at)))
                .and(BONUS_GRANTS.APPLICABLE_MODELS.isNull().or(containsModel(BONUS_GRANTS.APPLICABLE_MODELS, modelCode)))
                .orderBy(BONUS_GRANTS.EXPIRES_AT.asc().nullsLast(), BONUS_GRANTS.ID.asc())
                .fetch(this::toDomain);
    }

    @Override
    public List<BonusGrant> listActiveByOrg(UUID orgId) {
        return dsl.selectFrom(BONUS_GRANTS)
                .where(BONUS_GRANTS.ORG_ID.eq(orgId))
                .and(BONUS_GRANTS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .orderBy(BONUS_GRANTS.EXPIRES_AT.asc().nullsLast(), BONUS_GRANTS.ID.asc())
                .fetch(this::toDomain);
    }

    @Override
    public boolean decrementMicros(UUID grantId, long micros, OffsetDateTime updatedAt) {
        int updated = dsl.update(BONUS_GRANTS)
                .set(BONUS_GRANTS.REMAINING_MICROS, BONUS_GRANTS.REMAINING_MICROS.minus(micros))
                .set(BONUS_GRANTS.STATUS, DSL.when(BONUS_GRANTS.REMAINING_MICROS.eq(micros),
                        EntitlementStatus.EXHAUSTED.name()).otherwise(BONUS_GRANTS.STATUS))
                .set(BONUS_GRANTS.VERSION, BONUS_GRANTS.VERSION.plus(1))
                .set(BONUS_GRANTS.UPDATED_AT, updatedAt)
                .where(BONUS_GRANTS.ID.eq(grantId))
                .and(BONUS_GRANTS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .and(BONUS_GRANTS.REMAINING_MICROS.ge(micros))
                .execute();
        return updated == 1;
    }

    @Override
    public Optional<EntitlementStatus> foldConsumption(UUID orgId, UUID grantId, long micros, OffsetDateTime at) {
        return dsl.update(BONUS_GRANTS)
                .set(BONUS_GRANTS.REMAINING_MICROS, BONUS_GRANTS.REMAINING_MICROS.minus(micros))
                .set(BONUS_GRANTS.STATUS, DSL.when(BONUS_GRANTS.STATUS.eq(EntitlementStatus.ACTIVE.name())
                        .and(BONUS_GRANTS.REMAINING_MICROS.eq(micros)), EntitlementStatus.EXHAUSTED.name())
                        .otherwise(BONUS_GRANTS.STATUS))
                .set(BONUS_GRANTS.VERSION, BONUS_GRANTS.VERSION.plus(1))
                .set(BONUS_GRANTS.UPDATED_AT, DSL.greatest(BONUS_GRANTS.UPDATED_AT, DSL.val(at)))
                .where(BONUS_GRANTS.ID.eq(grantId).and(BONUS_GRANTS.ORG_ID.eq(orgId)))
                .and(BONUS_GRANTS.STATUS.in(EntitlementStatus.ACTIVE.name(), EntitlementStatus.EXPIRED.name()))
                .and(BONUS_GRANTS.REMAINING_MICROS.ge(micros))
                .returningResult(BONUS_GRANTS.STATUS)
                .fetchOptional(record -> EntitlementStatus.valueOf(record.value1()));
    }

    @Override
    public List<BonusGrant> listExpiredActive(OffsetDateTime at, int limit) {
        return dsl.selectFrom(BONUS_GRANTS)
                .where(BONUS_GRANTS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .and(BONUS_GRANTS.EXPIRES_AT.isNotNull())
                .and(BONUS_GRANTS.EXPIRES_AT.lessOrEqual(at))
                .orderBy(BONUS_GRANTS.EXPIRES_AT.asc(), BONUS_GRANTS.ID.asc())
                .limit(limit)
                .fetch(this::toDomain);
    }

    @Override
    public boolean expire(UUID grantId, OffsetDateTime updatedAt) {
        return dsl.update(BONUS_GRANTS)
                .set(BONUS_GRANTS.STATUS, EntitlementStatus.EXPIRED.name())
                .set(BONUS_GRANTS.VERSION, BONUS_GRANTS.VERSION.plus(1))
                .set(BONUS_GRANTS.UPDATED_AT, updatedAt)
                .where(BONUS_GRANTS.ID.eq(grantId))
                .and(BONUS_GRANTS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .execute() == 1;
    }

    @Override
    public boolean revoke(UUID grantId, long expectedVersion, OffsetDateTime updatedAt) {
        int updated = dsl.update(BONUS_GRANTS)
                .set(BONUS_GRANTS.STATUS, EntitlementStatus.REVOKED.name())
                .set(BONUS_GRANTS.VERSION, BONUS_GRANTS.VERSION.plus(1))
                .set(BONUS_GRANTS.UPDATED_AT, updatedAt)
                .where(BONUS_GRANTS.ID.eq(grantId))
                .and(BONUS_GRANTS.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    @Override
    public long sumRemainingMicros(UUID orgId) {
        Long sum = dsl.select(DSL.coalesce(DSL.sum(BONUS_GRANTS.REMAINING_MICROS), DSL.inline(0L)))
                .from(BONUS_GRANTS)
                .where(BONUS_GRANTS.ORG_ID.eq(orgId))
                .and(BONUS_GRANTS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .fetchOne(0, Long.class);
        return sum == null ? 0L : sum;
    }

    @Override
    public PageResponse<BonusGrant> listByOrg(UUID orgId, PageQuery pageQuery) {
        return PgJooqSupport.fetchPage(
                dsl, BONUS_GRANTS, BONUS_GRANTS.ORG_ID.eq(orgId),
                BONUS_GRANTS.CREATED_AT.desc(), pageQuery, this::toDomain
        );
    }

    private static Condition containsModel(Field<String[]> column, String modelCode) {
        return DSL.field("{0} @> ARRAY[{1}]::text[]", Boolean.class, column, DSL.inline(modelCode)).isTrue();
    }

    private BonusGrant toDomain(BonusGrantsRecord r) {
        return new BonusGrant(
                r.getId(),
                r.getOrgId(),
                PgJooqSupport.toEnum(GrantSource.class, r.getGrantSource()),
                r.getVoucherId(),
                r.getRedemptionCodeId(),
                PgJooqSupport.fromTextArray(r.getApplicableModels()),
                r.getInitialMicros(),
                r.getRemainingMicros(),
                r.getExpiresAt(),
                PgJooqSupport.toEnum(EntitlementStatus.class, r.getStatus()),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
