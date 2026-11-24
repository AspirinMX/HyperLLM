package app.pagefault.hyperllm.domain.model.routing;

import app.pagefault.hyperllm.domain.model.enums.ModelCapability;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelVisibility;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.enums.RoutingStrategy;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VirtualModel(
        UUID id,
        String modelCode,
        String displayName,
        String providerBadge,
        ModelCapability capabilityType,
        ProtocolFamily nativeProtocol,
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
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public VirtualModel {
        translationPolicy = (translationPolicy == null || translationPolicy.isBlank())
                ? "BEST_EFFORT"
                : translationPolicy;
    }

    public VirtualModel(
            UUID id,
            String modelCode,
            String displayName,
            String providerBadge,
            ModelCapability capabilityType,
            ProtocolFamily nativeProtocol,
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
            OffsetDateTime deletedAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this(id, modelCode, displayName, providerBadge, capabilityType, nativeProtocol,
                contextWindow, maxOutputTokens, supportsTools, supportsVision, supportsThinking,
                supportsPromptCache, routingStrategy, stickyCacheEnabled, stickyTtlSeconds,
                visibility, fallbackModelCode, status, metadataJson, true, "BEST_EFFORT",
                deletedAt, version, createdAt, updatedAt);
    }
}
