package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.VirtualModelsRecord;
import app.pagefault.hyperllm.domain.model.enums.ModelCapability;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelVisibility;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.enums.RoutingStrategy;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.repository.VirtualModelRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.VIRTUAL_MODELS;

@Repository
@RequiredArgsConstructor
public class JooqVirtualModelRepository implements VirtualModelRepository {

    private final DSLContext dsl;

    @Override
    public void insert(VirtualModel model) {
        dsl.insertInto(VIRTUAL_MODELS)
                .set(VIRTUAL_MODELS.ID, model.id())
                .set(VIRTUAL_MODELS.MODEL_CODE, model.modelCode())
                .set(VIRTUAL_MODELS.DISPLAY_NAME, model.displayName())
                .set(VIRTUAL_MODELS.PROVIDER_BADGE, model.providerBadge())
                .set(VIRTUAL_MODELS.CAPABILITY_TYPE, model.capabilityType().name())
                .set(VIRTUAL_MODELS.NATIVE_PROTOCOL, model.nativeProtocol().name())
                .set(VIRTUAL_MODELS.CONTEXT_WINDOW, model.contextWindow())
                .set(VIRTUAL_MODELS.MAX_OUTPUT_TOKENS, model.maxOutputTokens())
                .set(VIRTUAL_MODELS.SUPPORTS_TOOLS, model.supportsTools())
                .set(VIRTUAL_MODELS.SUPPORTS_VISION, model.supportsVision())
                .set(VIRTUAL_MODELS.SUPPORTS_THINKING, model.supportsThinking())
                .set(VIRTUAL_MODELS.SUPPORTS_PROMPT_CACHE, model.supportsPromptCache())
                .set(VIRTUAL_MODELS.ROUTING_STRATEGY, model.routingStrategy().name())
                .set(VIRTUAL_MODELS.STICKY_CACHE_ENABLED, model.stickyCacheEnabled())
                .set(VIRTUAL_MODELS.STICKY_TTL_SECONDS, model.stickyTtlSeconds())
                .set(VIRTUAL_MODELS.VISIBILITY, model.visibility().name())
                .set(VIRTUAL_MODELS.FALLBACK_MODEL_CODE, model.fallbackModelCode())
                .set(VIRTUAL_MODELS.STATUS, model.status().name())
                .set(VIRTUAL_MODELS.METADATA_JSON, PgJooqSupport.toJsonb(model.metadataJson()))
                .set(VIRTUAL_MODELS.DELETED_AT, model.deletedAt())
                .set(VIRTUAL_MODELS.VERSION, model.version())
                .set(VIRTUAL_MODELS.CREATED_AT, model.createdAt())
                .set(VIRTUAL_MODELS.UPDATED_AT, model.updatedAt())
                .execute();
    }

    @Override
    public Optional<VirtualModel> findById(UUID id) {
        return dsl.selectFrom(VIRTUAL_MODELS)
                .where(VIRTUAL_MODELS.ID.eq(id))
                .and(VIRTUAL_MODELS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<VirtualModel> findActiveByModelCode(String modelCode) {
        return dsl.selectFrom(VIRTUAL_MODELS)
                .where(VIRTUAL_MODELS.MODEL_CODE.eq(modelCode))
                .and(VIRTUAL_MODELS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<VirtualModel> listPublicActive() {
        return dsl.selectFrom(VIRTUAL_MODELS)
                .where(VIRTUAL_MODELS.DELETED_AT.isNull())
                .and(VIRTUAL_MODELS.VISIBILITY.eq(ModelVisibility.PUBLIC.name()))
                .and(VIRTUAL_MODELS.STATUS.eq(ModelStatus.ACTIVE.name()))
                .orderBy(VIRTUAL_MODELS.MODEL_CODE.asc())
                .fetch(this::toDomain);
    }

    @Override
    public PageResponse<VirtualModel> listPage(PageQuery pageQuery) {
        Condition cond = VIRTUAL_MODELS.DELETED_AT.isNull();
        return PgJooqSupport.fetchPage(dsl, VIRTUAL_MODELS, cond, VIRTUAL_MODELS.MODEL_CODE.asc(), pageQuery, this::toDomain);
    }

    @Override
    public boolean update(
            UUID modelId,
            String displayName,
            String providerBadge,
            int contextWindow,
            Integer maxOutputTokens,
            boolean supportsTools,
            boolean supportsVision,
            boolean supportsThinking,
            boolean supportsPromptCache,
            RoutingStrategy routingStrategy,
            boolean stickyCacheEnabled,
            int stickyTtlSeconds,
            ModelVisibility visibility,
            String fallbackModelCode,
            ModelStatus status,
            String metadataJson,
            long expectedVersion,
            OffsetDateTime updatedAt
    ) {
        int updated = dsl.update(VIRTUAL_MODELS)
                .set(VIRTUAL_MODELS.DISPLAY_NAME, displayName)
                .set(VIRTUAL_MODELS.PROVIDER_BADGE, providerBadge)
                .set(VIRTUAL_MODELS.CONTEXT_WINDOW, contextWindow)
                .set(VIRTUAL_MODELS.MAX_OUTPUT_TOKENS, maxOutputTokens)
                .set(VIRTUAL_MODELS.SUPPORTS_TOOLS, supportsTools)
                .set(VIRTUAL_MODELS.SUPPORTS_VISION, supportsVision)
                .set(VIRTUAL_MODELS.SUPPORTS_THINKING, supportsThinking)
                .set(VIRTUAL_MODELS.SUPPORTS_PROMPT_CACHE, supportsPromptCache)
                .set(VIRTUAL_MODELS.ROUTING_STRATEGY, routingStrategy.name())
                .set(VIRTUAL_MODELS.STICKY_CACHE_ENABLED, stickyCacheEnabled)
                .set(VIRTUAL_MODELS.STICKY_TTL_SECONDS, stickyTtlSeconds)
                .set(VIRTUAL_MODELS.VISIBILITY, visibility.name())
                .set(VIRTUAL_MODELS.FALLBACK_MODEL_CODE, fallbackModelCode)
                .set(VIRTUAL_MODELS.STATUS, status.name())
                .set(VIRTUAL_MODELS.METADATA_JSON, PgJooqSupport.toJsonb(metadataJson))
                .set(VIRTUAL_MODELS.VERSION, VIRTUAL_MODELS.VERSION.plus(1))
                .set(VIRTUAL_MODELS.UPDATED_AT, updatedAt)
                .where(VIRTUAL_MODELS.ID.eq(modelId))
                .and(VIRTUAL_MODELS.VERSION.eq(expectedVersion))
                .and(VIRTUAL_MODELS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    @Override
    public boolean softDelete(UUID modelId, long expectedVersion, OffsetDateTime deletedAt) {
        int updated = dsl.update(VIRTUAL_MODELS)
                .set(VIRTUAL_MODELS.STATUS, ModelStatus.DISABLED.name())
                .set(VIRTUAL_MODELS.DELETED_AT, deletedAt)
                .set(VIRTUAL_MODELS.VERSION, VIRTUAL_MODELS.VERSION.plus(1))
                .set(VIRTUAL_MODELS.UPDATED_AT, deletedAt)
                .where(VIRTUAL_MODELS.ID.eq(modelId))
                .and(VIRTUAL_MODELS.VERSION.eq(expectedVersion))
                .and(VIRTUAL_MODELS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    VirtualModel toDomain(VirtualModelsRecord r) {
        return new VirtualModel(
                r.getId(),
                r.getModelCode(),
                r.getDisplayName(),
                r.getProviderBadge(),
                PgJooqSupport.toEnum(ModelCapability.class, r.getCapabilityType()),
                PgJooqSupport.toEnum(ProtocolFamily.class, r.getNativeProtocol()),
                r.getContextWindow(),
                r.getMaxOutputTokens(),
                Boolean.TRUE.equals(r.getSupportsTools()),
                Boolean.TRUE.equals(r.getSupportsVision()),
                Boolean.TRUE.equals(r.getSupportsThinking()),
                Boolean.TRUE.equals(r.getSupportsPromptCache()),
                PgJooqSupport.toEnum(RoutingStrategy.class, r.getRoutingStrategy()),
                Boolean.TRUE.equals(r.getStickyCacheEnabled()),
                r.getStickyTtlSeconds(),
                PgJooqSupport.toEnum(ModelVisibility.class, r.getVisibility()),
                r.getFallbackModelCode(),
                PgJooqSupport.toEnum(ModelStatus.class, r.getStatus()),
                PgJooqSupport.fromJsonb(r.getMetadataJson()),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
