package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelVisibility;
import app.pagefault.hyperllm.domain.model.enums.RoutingStrategy;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VirtualModelRepository {

    void insert(VirtualModel model);

    Optional<VirtualModel> findById(UUID id);

    Optional<VirtualModel> findActiveByModelCode(String modelCode);

    List<VirtualModel> listPublicActive();

    PageResponse<VirtualModel> listPage(PageQuery pageQuery);

    default boolean update(
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
        return update(modelId, displayName, providerBadge, contextWindow, maxOutputTokens,
                supportsTools, supportsVision, supportsThinking, supportsPromptCache,
                routingStrategy, stickyCacheEnabled, stickyTtlSeconds, visibility,
                fallbackModelCode, status, metadataJson, true, "BEST_EFFORT",
                expectedVersion, updatedAt);
    }

    boolean update(
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
            boolean allowProtocolTranslation,
            String translationPolicy,
            long expectedVersion,
            OffsetDateTime updatedAt
    );

    boolean softDelete(UUID modelId, long expectedVersion, OffsetDateTime deletedAt);
}
