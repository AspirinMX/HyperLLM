package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.ResourcePacksRecord;
import app.pagefault.hyperllm.domain.model.billing.ResourcePack;
import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import app.pagefault.hyperllm.domain.repository.ResourcePackRepository;
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

import static app.pagefault.hyperllm.domain.jooq.Tables.RESOURCE_PACKS;

@Repository
@RequiredArgsConstructor
public class JooqResourcePackRepository implements ResourcePackRepository {

    private final DSLContext dsl;

    @Override
    public void insert(ResourcePack pack) {
        dsl.insertInto(RESOURCE_PACKS)
                .set(RESOURCE_PACKS.ID, pack.id())
                .set(RESOURCE_PACKS.ORG_ID, pack.orgId())
                .set(RESOURCE_PACKS.PACK_NAME, pack.packName())
                .set(RESOURCE_PACKS.UNIT_TYPE, pack.unitType().name())
                .set(RESOURCE_PACKS.APPLICABLE_MODELS, PgJooqSupport.toTextArray(pack.applicableModels()))
                .set(RESOURCE_PACKS.TOTAL_UNITS, pack.totalUnits())
                .set(RESOURCE_PACKS.REMAINING_UNITS, pack.remainingUnits())
                .set(RESOURCE_PACKS.PURCHASE_ORDER_ID, pack.purchaseOrderId())
                .set(RESOURCE_PACKS.EXPIRES_AT, pack.expiresAt())
                .set(RESOURCE_PACKS.STATUS, pack.status().name())
                .set(RESOURCE_PACKS.VERSION, pack.version())
                .set(RESOURCE_PACKS.CREATED_AT, pack.createdAt())
                .set(RESOURCE_PACKS.UPDATED_AT, pack.updatedAt())
                .execute();
    }

    @Override
    public Optional<ResourcePack> findById(UUID id) {
        return dsl.selectFrom(RESOURCE_PACKS)
                .where(RESOURCE_PACKS.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<ResourcePack> listConsumableByOrgAndModel(UUID orgId, String modelCode, OffsetDateTime at) {
        return dsl.selectFrom(RESOURCE_PACKS)
                .where(RESOURCE_PACKS.ORG_ID.eq(orgId))
                .and(RESOURCE_PACKS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .and(RESOURCE_PACKS.EXPIRES_AT.gt(at))
                .and(containsModel(RESOURCE_PACKS.APPLICABLE_MODELS, modelCode))
                .orderBy(RESOURCE_PACKS.EXPIRES_AT.asc(), RESOURCE_PACKS.ID.asc())
                .fetch(this::toDomain);
    }

    @Override
    public boolean decrementUnits(UUID packId, long units, OffsetDateTime updatedAt) {
        int updated = dsl.update(RESOURCE_PACKS)
                .set(RESOURCE_PACKS.REMAINING_UNITS, RESOURCE_PACKS.REMAINING_UNITS.minus(units))
                .set(RESOURCE_PACKS.VERSION, RESOURCE_PACKS.VERSION.plus(1))
                .set(RESOURCE_PACKS.UPDATED_AT, updatedAt)
                .where(RESOURCE_PACKS.ID.eq(packId))
                .and(RESOURCE_PACKS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .and(RESOURCE_PACKS.REMAINING_UNITS.ge(units))
                .execute();
        return updated == 1;
    }

    @Override
    public List<ResourcePack> listExpiredActive(OffsetDateTime at, int limit) {
        return dsl.selectFrom(RESOURCE_PACKS)
                .where(RESOURCE_PACKS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .and(RESOURCE_PACKS.EXPIRES_AT.lessOrEqual(at))
                .orderBy(RESOURCE_PACKS.EXPIRES_AT.asc(), RESOURCE_PACKS.ID.asc())
                .limit(limit)
                .fetch(this::toDomain);
    }

    @Override
    public boolean expire(UUID packId, OffsetDateTime updatedAt) {
        return dsl.update(RESOURCE_PACKS)
                .set(RESOURCE_PACKS.STATUS, EntitlementStatus.EXPIRED.name())
                .set(RESOURCE_PACKS.VERSION, RESOURCE_PACKS.VERSION.plus(1))
                .set(RESOURCE_PACKS.UPDATED_AT, updatedAt)
                .where(RESOURCE_PACKS.ID.eq(packId))
                .and(RESOURCE_PACKS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .execute() == 1;
    }

    @Override
    public boolean revoke(UUID packId, long expectedVersion, OffsetDateTime updatedAt) {
        int updated = dsl.update(RESOURCE_PACKS)
                .set(RESOURCE_PACKS.STATUS, EntitlementStatus.REVOKED.name())
                .set(RESOURCE_PACKS.VERSION, RESOURCE_PACKS.VERSION.plus(1))
                .set(RESOURCE_PACKS.UPDATED_AT, updatedAt)
                .where(RESOURCE_PACKS.ID.eq(packId))
                .and(RESOURCE_PACKS.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    @Override
    public long sumRemainingUnits(UUID orgId, String modelCode, OffsetDateTime at) {
        Long sum = dsl.select(DSL.coalesce(DSL.sum(RESOURCE_PACKS.REMAINING_UNITS), DSL.inline(0L)))
                .from(RESOURCE_PACKS)
                .where(RESOURCE_PACKS.ORG_ID.eq(orgId))
                .and(RESOURCE_PACKS.STATUS.eq(EntitlementStatus.ACTIVE.name()))
                .and(RESOURCE_PACKS.EXPIRES_AT.gt(at))
                .and(containsModel(RESOURCE_PACKS.APPLICABLE_MODELS, modelCode))
                .fetchOne(0, Long.class);
        return sum == null ? 0L : sum;
    }

    @Override
    public PageResponse<ResourcePack> listByOrg(UUID orgId, PageQuery pageQuery) {
        return PgJooqSupport.fetchPage(
                dsl, RESOURCE_PACKS, RESOURCE_PACKS.ORG_ID.eq(orgId),
                RESOURCE_PACKS.CREATED_AT.desc(), pageQuery, this::toDomain
        );
    }

    /**
     * {@code column @> ARRAY[value]} rather than {@code value = ANY(column)} so the predicate stays
     * index-friendly and reads as "the array contains this model".
     */
    private static Condition containsModel(Field<String[]> column, String modelCode) {
        return DSL.field("{0} @> ARRAY[{1}]::text[]", Boolean.class, column, DSL.inline(modelCode)).isTrue();
    }

    private ResourcePack toDomain(ResourcePacksRecord r) {
        return new ResourcePack(
                r.getId(),
                r.getOrgId(),
                r.getPackName(),
                PgJooqSupport.toEnum(PackUnitType.class, r.getUnitType()),
                PgJooqSupport.fromTextArray(r.getApplicableModels()),
                r.getTotalUnits(),
                r.getRemainingUnits(),
                r.getPurchaseOrderId(),
                r.getExpiresAt(),
                PgJooqSupport.toEnum(EntitlementStatus.class, r.getStatus()),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
