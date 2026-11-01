package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.ResourcePackSkusRecord;
import app.pagefault.hyperllm.domain.model.billing.ResourcePackSku;
import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import app.pagefault.hyperllm.domain.repository.ResourcePackSkuRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.RESOURCE_PACK_SKUS;

@Repository
@RequiredArgsConstructor
public class JooqResourcePackSkuRepository implements ResourcePackSkuRepository {

    private final DSLContext dsl;

    @Override
    public void insert(ResourcePackSku sku) {
        dsl.insertInto(RESOURCE_PACK_SKUS)
                .set(RESOURCE_PACK_SKUS.ID, sku.id())
                .set(RESOURCE_PACK_SKUS.CODE, sku.code())
                .set(RESOURCE_PACK_SKUS.NAME, sku.name())
                .set(RESOURCE_PACK_SKUS.UNIT_TYPE, sku.unitType().name())
                .set(RESOURCE_PACK_SKUS.APPLICABLE_MODELS, PgJooqSupport.toTextArray(sku.applicableModels()))
                .set(RESOURCE_PACK_SKUS.TOTAL_UNITS, sku.totalUnits())
                .set(RESOURCE_PACK_SKUS.PRICE_MICROS, sku.priceMicros())
                .set(RESOURCE_PACK_SKUS.VALID_DAYS, sku.validDays())
                .set(RESOURCE_PACK_SKUS.STATUS, sku.status().name())
                .set(RESOURCE_PACK_SKUS.CREATED_BY_ADMIN_ID, sku.createdByAdminId())
                .set(RESOURCE_PACK_SKUS.VERSION, sku.version())
                .set(RESOURCE_PACK_SKUS.CREATED_AT, sku.createdAt())
                .set(RESOURCE_PACK_SKUS.UPDATED_AT, sku.updatedAt())
                .execute();
    }

    @Override
    public Optional<ResourcePackSku> findById(UUID id) {
        return dsl.selectFrom(RESOURCE_PACK_SKUS)
                .where(RESOURCE_PACK_SKUS.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<ResourcePackSku> findByCode(String code) {
        return dsl.selectFrom(RESOURCE_PACK_SKUS)
                .where(DSL.lower(RESOURCE_PACK_SKUS.CODE).eq(code.toLowerCase(java.util.Locale.ROOT)))
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<ResourcePackSku> listActive() {
        return dsl.selectFrom(RESOURCE_PACK_SKUS)
                .where(RESOURCE_PACK_SKUS.STATUS.eq(PackSkuStatus.ACTIVE.name()))
                .orderBy(RESOURCE_PACK_SKUS.PRICE_MICROS.asc(), RESOURCE_PACK_SKUS.ID.asc())
                .fetch(this::toDomain);
    }

    @Override
    public PageResponse<ResourcePackSku> listPage(PackSkuStatus status, PageQuery pageQuery) {
        Condition condition = status == null
                ? DSL.trueCondition()
                : RESOURCE_PACK_SKUS.STATUS.eq(status.name());
        return PgJooqSupport.fetchPage(dsl, RESOURCE_PACK_SKUS, condition,
                RESOURCE_PACK_SKUS.CREATED_AT.desc(), pageQuery, this::toDomain);
    }

    @Override
    public boolean update(ResourcePackSku next, long expectedVersion) {
        return dsl.update(RESOURCE_PACK_SKUS)
                .set(RESOURCE_PACK_SKUS.NAME, next.name())
                .set(RESOURCE_PACK_SKUS.UNIT_TYPE, next.unitType().name())
                .set(RESOURCE_PACK_SKUS.APPLICABLE_MODELS, PgJooqSupport.toTextArray(next.applicableModels()))
                .set(RESOURCE_PACK_SKUS.TOTAL_UNITS, next.totalUnits())
                .set(RESOURCE_PACK_SKUS.PRICE_MICROS, next.priceMicros())
                .set(RESOURCE_PACK_SKUS.VALID_DAYS, next.validDays())
                .set(RESOURCE_PACK_SKUS.STATUS, next.status().name())
                .set(RESOURCE_PACK_SKUS.VERSION, RESOURCE_PACK_SKUS.VERSION.plus(1))
                .set(RESOURCE_PACK_SKUS.UPDATED_AT, next.updatedAt())
                .where(RESOURCE_PACK_SKUS.ID.eq(next.id()))
                .and(RESOURCE_PACK_SKUS.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    private ResourcePackSku toDomain(ResourcePackSkusRecord r) {
        return new ResourcePackSku(
                r.getId(),
                r.getCode(),
                r.getName(),
                PgJooqSupport.toEnum(PackUnitType.class, r.getUnitType()),
                PgJooqSupport.fromTextArray(r.getApplicableModels()),
                r.getTotalUnits(),
                r.getPriceMicros(),
                r.getValidDays(),
                PgJooqSupport.toEnum(PackSkuStatus.class, r.getStatus()),
                r.getCreatedByAdminId(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
