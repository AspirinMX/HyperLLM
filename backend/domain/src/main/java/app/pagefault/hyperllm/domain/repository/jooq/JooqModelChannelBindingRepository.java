package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.ModelChannelBindingsRecord;
import app.pagefault.hyperllm.domain.model.enums.BindingStatus;
import app.pagefault.hyperllm.domain.model.routing.ModelChannelBinding;
import app.pagefault.hyperllm.domain.repository.ModelChannelBindingRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.MODEL_CHANNEL_BINDINGS;
import static app.pagefault.hyperllm.domain.jooq.Tables.VIRTUAL_MODELS;

@Repository
@RequiredArgsConstructor
public class JooqModelChannelBindingRepository implements ModelChannelBindingRepository {

    private final DSLContext dsl;

    @Override
    public void insert(ModelChannelBinding b) {
        dsl.insertInto(MODEL_CHANNEL_BINDINGS)
                .set(MODEL_CHANNEL_BINDINGS.ID, b.id())
                .set(MODEL_CHANNEL_BINDINGS.VIRTUAL_MODEL_ID, b.virtualModelId())
                .set(MODEL_CHANNEL_BINDINGS.CHANNEL_ID, b.channelId())
                .set(MODEL_CHANNEL_BINDINGS.UPSTREAM_MODEL_NAME, b.upstreamModelName())
                .set(MODEL_CHANNEL_BINDINGS.PRIORITY_TIER, b.priorityTier())
                .set(MODEL_CHANNEL_BINDINGS.WEIGHT, b.weight())
                .set(MODEL_CHANNEL_BINDINGS.COST_DISCOUNT_BPS_OVERRIDE, b.costDiscountBpsOverride())
                .set(MODEL_CHANNEL_BINDINGS.UPSTREAM_PRICING_OVERRIDE_JSON,
                        PgJooqSupport.toJsonb(b.upstreamPricingOverrideJson()))
                .set(MODEL_CHANNEL_BINDINGS.EXTRA_HEADERS_JSON, PgJooqSupport.toJsonb(b.extraHeadersJson()))
                .set(MODEL_CHANNEL_BINDINGS.STATUS, b.status().name())
                .set(MODEL_CHANNEL_BINDINGS.DELETED_AT, b.deletedAt())
                .set(MODEL_CHANNEL_BINDINGS.VERSION, b.version())
                .set(MODEL_CHANNEL_BINDINGS.CREATED_AT, b.createdAt())
                .set(MODEL_CHANNEL_BINDINGS.UPDATED_AT, b.updatedAt())
                .execute();
    }

    @Override
    public void replaceForModel(UUID virtualModelId, List<ModelChannelBinding> bindings) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.update(MODEL_CHANNEL_BINDINGS)
                .set(MODEL_CHANNEL_BINDINGS.STATUS, BindingStatus.DISABLED.name())
                .set(MODEL_CHANNEL_BINDINGS.DELETED_AT, now)
                .set(MODEL_CHANNEL_BINDINGS.VERSION, MODEL_CHANNEL_BINDINGS.VERSION.plus(1))
                .set(MODEL_CHANNEL_BINDINGS.UPDATED_AT, now)
                .where(MODEL_CHANNEL_BINDINGS.VIRTUAL_MODEL_ID.eq(virtualModelId))
                .and(MODEL_CHANNEL_BINDINGS.DELETED_AT.isNull())
                .execute();

        if (bindings != null) {
            for (ModelChannelBinding binding : bindings) {
                insert(binding);
            }
        }
    }

    @Override
    public List<ModelChannelBinding> listByModelId(UUID virtualModelId) {
        return dsl.selectFrom(MODEL_CHANNEL_BINDINGS)
                .where(MODEL_CHANNEL_BINDINGS.VIRTUAL_MODEL_ID.eq(virtualModelId))
                .and(MODEL_CHANNEL_BINDINGS.DELETED_AT.isNull())
                .orderBy(MODEL_CHANNEL_BINDINGS.PRIORITY_TIER.asc(), MODEL_CHANNEL_BINDINGS.WEIGHT.desc())
                .fetch(this::toDomain);
    }

    @Override
    public List<String> listActiveModelCodesByChannelId(UUID channelId) {
        return dsl.selectDistinct(VIRTUAL_MODELS.MODEL_CODE)
                .from(MODEL_CHANNEL_BINDINGS)
                .join(VIRTUAL_MODELS).on(VIRTUAL_MODELS.ID.eq(MODEL_CHANNEL_BINDINGS.VIRTUAL_MODEL_ID))
                .where(MODEL_CHANNEL_BINDINGS.CHANNEL_ID.eq(channelId))
                .and(MODEL_CHANNEL_BINDINGS.DELETED_AT.isNull())
                .and(VIRTUAL_MODELS.DELETED_AT.isNull())
                .fetch(VIRTUAL_MODELS.MODEL_CODE);
    }

    private ModelChannelBinding toDomain(ModelChannelBindingsRecord r) {
        return new ModelChannelBinding(
                r.getId(),
                r.getVirtualModelId(),
                r.getChannelId(),
                r.getUpstreamModelName(),
                r.getPriorityTier(),
                r.getWeight(),
                r.getCostDiscountBpsOverride(),
                PgJooqSupport.fromJsonb(r.getUpstreamPricingOverrideJson()),
                PgJooqSupport.fromJsonb(r.getExtraHeadersJson()),
                PgJooqSupport.toEnum(BindingStatus.class, r.getStatus()),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
