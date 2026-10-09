package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.OrgModelPricingOverridesRecord;
import app.pagefault.hyperllm.domain.model.billing.OrgModelPricingOverride;
import app.pagefault.hyperllm.domain.model.enums.OverrideMode;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.repository.OrgModelPricingOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.ORG_MODEL_PRICING_OVERRIDES;

@Repository
@RequiredArgsConstructor
public class JooqOrgModelPricingOverrideRepository implements OrgModelPricingOverrideRepository {

    private final DSLContext dsl;

    @Override
    public void insert(OrgModelPricingOverride override) {
        dsl.insertInto(ORG_MODEL_PRICING_OVERRIDES)
                .set(ORG_MODEL_PRICING_OVERRIDES.ID, override.id())
                .set(ORG_MODEL_PRICING_OVERRIDES.ORG_ID, override.orgId())
                .set(ORG_MODEL_PRICING_OVERRIDES.VIRTUAL_MODEL_ID, override.virtualModelId())
                .set(ORG_MODEL_PRICING_OVERRIDES.OVERRIDE_MODE, override.overrideMode().name())
                .set(ORG_MODEL_PRICING_OVERRIDES.DISCOUNT_RATIO_BPS, override.discountRatioBps())
                .set(ORG_MODEL_PRICING_OVERRIDES.FIXED_PRICING_JSON, PgJooqSupport.toJsonb(override.fixedPricingJson()))
                .set(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_FROM, override.effectiveFrom())
                .set(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_TO, override.effectiveTo())
                .set(ORG_MODEL_PRICING_OVERRIDES.STATUS, override.status().name())
                .set(ORG_MODEL_PRICING_OVERRIDES.CREATED_BY_ADMIN_ID, override.createdByAdminId())
                .set(ORG_MODEL_PRICING_OVERRIDES.DELETED_AT, override.deletedAt())
                .set(ORG_MODEL_PRICING_OVERRIDES.VERSION, override.version())
                .set(ORG_MODEL_PRICING_OVERRIDES.CREATED_AT, override.createdAt())
                .set(ORG_MODEL_PRICING_OVERRIDES.UPDATED_AT, override.updatedAt())
                .execute();
    }

    @Override
    public Optional<OrgModelPricingOverride> findById(UUID id) {
        return dsl.selectFrom(ORG_MODEL_PRICING_OVERRIDES)
                .where(ORG_MODEL_PRICING_OVERRIDES.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<OrgModelPricingOverride> findEffective(UUID orgId, UUID virtualModelId, OffsetDateTime at) {
        return dsl.selectFrom(ORG_MODEL_PRICING_OVERRIDES)
                .where(ORG_MODEL_PRICING_OVERRIDES.ORG_ID.eq(orgId))
                .and(ORG_MODEL_PRICING_OVERRIDES.VIRTUAL_MODEL_ID.eq(virtualModelId))
                .and(ORG_MODEL_PRICING_OVERRIDES.STATUS.eq(ToggleStatus.ACTIVE.name()))
                .and(ORG_MODEL_PRICING_OVERRIDES.DELETED_AT.isNull())
                .and(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_FROM.le(at))
                .and(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_TO.isNull().or(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_TO.gt(at)))
                .orderBy(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_FROM.desc(), ORG_MODEL_PRICING_OVERRIDES.ID.asc())
                .limit(1)
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<OrgModelPricingOverride> listActiveByOrg(UUID orgId) {
        return dsl.selectFrom(ORG_MODEL_PRICING_OVERRIDES)
                .where(ORG_MODEL_PRICING_OVERRIDES.ORG_ID.eq(orgId))
                .and(ORG_MODEL_PRICING_OVERRIDES.STATUS.eq(ToggleStatus.ACTIVE.name()))
                .and(ORG_MODEL_PRICING_OVERRIDES.DELETED_AT.isNull())
                .orderBy(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_FROM.desc())
                .fetch(this::toDomain);
    }

    @Override
    public boolean update(OrgModelPricingOverride next, long expectedVersion) {
        int updated = dsl.update(ORG_MODEL_PRICING_OVERRIDES)
                .set(ORG_MODEL_PRICING_OVERRIDES.OVERRIDE_MODE, next.overrideMode().name())
                .set(ORG_MODEL_PRICING_OVERRIDES.DISCOUNT_RATIO_BPS, next.discountRatioBps())
                .set(ORG_MODEL_PRICING_OVERRIDES.FIXED_PRICING_JSON, PgJooqSupport.toJsonb(next.fixedPricingJson()))
                .set(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_FROM, next.effectiveFrom())
                .set(ORG_MODEL_PRICING_OVERRIDES.EFFECTIVE_TO, next.effectiveTo())
                .set(ORG_MODEL_PRICING_OVERRIDES.STATUS, next.status().name())
                .set(ORG_MODEL_PRICING_OVERRIDES.VERSION, ORG_MODEL_PRICING_OVERRIDES.VERSION.plus(1))
                .set(ORG_MODEL_PRICING_OVERRIDES.UPDATED_AT, next.updatedAt())
                .where(ORG_MODEL_PRICING_OVERRIDES.ID.eq(next.id()))
                .and(ORG_MODEL_PRICING_OVERRIDES.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt) {
        int updated = dsl.update(ORG_MODEL_PRICING_OVERRIDES)
                .set(ORG_MODEL_PRICING_OVERRIDES.DELETED_AT, deletedAt)
                .set(ORG_MODEL_PRICING_OVERRIDES.VERSION, ORG_MODEL_PRICING_OVERRIDES.VERSION.plus(1))
                .set(ORG_MODEL_PRICING_OVERRIDES.UPDATED_AT, deletedAt)
                .where(ORG_MODEL_PRICING_OVERRIDES.ID.eq(id))
                .and(ORG_MODEL_PRICING_OVERRIDES.VERSION.eq(expectedVersion))
                .and(ORG_MODEL_PRICING_OVERRIDES.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    private OrgModelPricingOverride toDomain(OrgModelPricingOverridesRecord r) {
        return new OrgModelPricingOverride(
                r.getId(),
                r.getOrgId(),
                r.getVirtualModelId(),
                PgJooqSupport.toEnum(OverrideMode.class, r.getOverrideMode()),
                r.getDiscountRatioBps(),
                PgJooqSupport.fromJsonb(r.getFixedPricingJson()),
                r.getEffectiveFrom(),
                r.getEffectiveTo(),
                PgJooqSupport.toEnum(ToggleStatus.class, r.getStatus()),
                r.getCreatedByAdminId(),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
