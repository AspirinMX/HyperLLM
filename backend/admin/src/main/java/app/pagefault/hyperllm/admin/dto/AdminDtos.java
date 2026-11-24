package app.pagefault.hyperllm.admin.dto;

import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.model.enums.BindingStatus;
import app.pagefault.hyperllm.domain.model.enums.ChannelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelCapability;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelVisibility;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.OverrideMode;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.enums.RoutingStrategy;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record AdminLoginRequest(
            @NotBlank String login,
            @NotBlank String password
    ) {
    }

    public record AdminMfaSetupRequest(
            String secret,
            String code
    ) {
    }

    public record AdminMfaVerifyRequest(@NotBlank String code) {
    }

    public record AdminAuthResponse(
            boolean mfaRequired,
            String mfaAction,
            String accessToken,
            String challengeToken,
            long expiresInSeconds,
            UUID adminId,
            String username,
            String email,
            AdminRole role,
            boolean mfaVerified
    ) {
    }

    public record MfaSetupChallengeResponse(
            String secret,
            String otpauthUri
    ) {
    }

    public record ProviderDto(
            UUID id,
            String code,
            String displayName,
            ProtocolFamily protocolFamily,
            String defaultBaseUrl,
            AuthScheme authScheme,
            String status,
            List<String> supportedProtocols,
            String defaultWireProtocol
    ) {
    }

    public record CreateChannelRequest(
            @NotNull UUID providerId,
            @NotBlank String name,
            String region,
            String baseUrlOverride,
            @NotBlank String apiKey,
            @Positive Integer defaultCostDiscountBps,
            @Positive Integer rpmLimit,
            @Positive Integer tpmLimit,
            @Positive Integer maxConcurrency,
            String healthCheckModel
    ) {
    }

    public record UpdateChannelRequest(
            @NotBlank String name,
            String region,
            String baseUrlOverride,
            String apiKey,
            @NotNull @Positive Integer defaultCostDiscountBps,
            @Positive Integer rpmLimit,
            @Positive Integer tpmLimit,
            @Positive Integer maxConcurrency,
            String healthCheckModel,
            @NotNull ChannelStatus status,
            @NotNull Long version
    ) {
    }

    public record ChannelDto(
            UUID id,
            UUID providerId,
            String name,
            String region,
            String baseUrlOverride,
            boolean hasCredentials,
            short credentialKeyVersion,
            int defaultCostDiscountBps,
            Integer rpmLimit,
            Integer tpmLimit,
            Integer maxConcurrency,
            String healthCheckModel,
            ChannelStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ChannelProbeDto(
            UUID channelId,
            boolean healthy,
            int httpStatus,
            long latencyMs,
            String endpointUrl,
            String message
    ) {
    }

    public record CreateVirtualModelRequest(
            @NotBlank String modelCode,
            @NotBlank String displayName,
            String providerBadge,
            @NotNull ModelCapability capabilityType,
            @NotNull ProtocolFamily nativeProtocol,
            @Positive int contextWindow,
            @Positive Integer maxOutputTokens,
            boolean supportsTools,
            boolean supportsVision,
            boolean supportsThinking,
            boolean supportsPromptCache,
            @NotNull RoutingStrategy routingStrategy,
            boolean stickyCacheEnabled,
            @Min(0) int stickyTtlSeconds,
            @NotNull ModelVisibility visibility,
            String fallbackModelCode,
            Boolean allowProtocolTranslation,
            String translationPolicy
    ) {
    }

    public record UpdateVirtualModelRequest(
            @NotBlank String displayName,
            String providerBadge,
            @Positive int contextWindow,
            @Positive Integer maxOutputTokens,
            boolean supportsTools,
            boolean supportsVision,
            boolean supportsThinking,
            boolean supportsPromptCache,
            @NotNull RoutingStrategy routingStrategy,
            boolean stickyCacheEnabled,
            @Min(0) int stickyTtlSeconds,
            @NotNull ModelVisibility visibility,
            String fallbackModelCode,
            @NotNull ModelStatus status,
            Boolean allowProtocolTranslation,
            String translationPolicy,
            @NotNull Long version
    ) {
    }

    public record VirtualModelDto(
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
            boolean allowProtocolTranslation,
            String translationPolicy,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record BindingItemRequest(
            @NotNull UUID channelId,
            @NotBlank String upstreamModelName,
            String upstreamProtocol,
            @Min(1) short priorityTier,
            @Positive int weight,
            @Positive Integer costDiscountBpsOverride,
            BindingStatus status
    ) {
    }

    public record ReplaceBindingsRequest(@NotNull @Valid List<BindingItemRequest> bindings) {
    }

    public record BindingDto(
            UUID id,
            UUID virtualModelId,
            UUID channelId,
            String upstreamModelName,
            String upstreamProtocol,
            short priorityTier,
            int weight,
            Integer costDiscountBpsOverride,
            BindingStatus status,
            OffsetDateTime updatedAt
    ) {
    }

    public record PublishPricingRuleRequest(
            @NotNull BillingMode billingMode,
            @Min(0) long inputPriceMicros1m,
            @Min(0) long outputPriceMicros1m,
            @Min(0) long cacheReadPriceMicros1m,
            @Min(0) long cacheWritePriceMicros1m,
            @Min(0) long reasoningPriceMicros1m,
            @Min(0) long perCallMicros,
            @NotNull OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo
    ) {
    }

    public record PricingRuleDto(
            UUID id,
            UUID virtualModelId,
            BillingMode billingMode,
            long inputPriceMicros1m,
            long outputPriceMicros1m,
            long cacheReadPriceMicros1m,
            long cacheWritePriceMicros1m,
            long reasoningPriceMicros1m,
            long perCallMicros,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            OffsetDateTime createdAt
    ) {
    }

    /**
     * A tenant as the operator list shows it.
     *
     * @param cashBalanceMicros the ledger-confirmed cash, {@code null} when no wallet exists yet
     * @param bonusBalanceMicros the ledger-confirmed bonus
     * @param liveDetailAvailable true when the detail endpoint can also show frozen and available;
     *                            a list cannot, because the database has no concept of a reservation
     */
    public record AdminOrgDto(
            UUID id,
            String name,
            String slug,
            UUID ownerUserId,
            UUID tierId,
            Integer discountRatioBps,
            int maxConcurrency,
            boolean personal,
            OrgStatus status,
            Long cashBalanceMicros,
            Long bonusBalanceMicros,
            boolean liveDetailAvailable,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record UpdateOrgStatusRequest(
            @NotNull OrgStatus status,
            @NotNull Long version
    ) {
    }

    public enum TopupType {
        CASH,
        BONUS
    }

    public record AdminWalletTopupRequest(
            @NotNull TopupType topupType,
            @Positive long amountMicros,
            @NotBlank String idempotencyKey,
            String description
    ) {
    }

    public record AdminWalletTopupResponse(
            UUID orgId,
            UUID eventId
    ) {
    }

    public record CreateOrgPricingOverrideRequest(
            @NotNull UUID virtualModelId,
            @NotNull OverrideMode overrideMode,
            @Positive Integer discountRatioBps,
            String fixedPricingJson,
            @NotNull OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo
    ) {
    }

    public record OrgPricingOverrideDto(
            UUID id,
            UUID orgId,
            UUID virtualModelId,
            String modelCode,
            String modelDisplayName,
            OverrideMode overrideMode,
            Integer discountRatioBps,
            String fixedPricingJson,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            ToggleStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
