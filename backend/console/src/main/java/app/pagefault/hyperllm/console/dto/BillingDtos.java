package app.pagefault.hyperllm.console.dto;

import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.model.enums.ModelCapability;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class BillingDtos {

    private BillingDtos() {
    }

    public record PublicModelCatalogDto(
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
            ActivePricingDto pricing
    ) {
    }

    public record ActivePricingDto(
            BillingMode billingMode,
            long inputPriceMicros1m,
            long outputPriceMicros1m,
            long cacheReadPriceMicros1m,
            long cacheWritePriceMicros1m,
            long reasoningPriceMicros1m,
            long perCallMicros,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo
    ) {
    }

    /**
     * @param cashBalanceMicros live cash, read from the same source the gateway charges
     * @param bonusBalanceMicros live unexpired bonus, summed across batches
     * @param heldMicros         reserved by requests still in flight; only Redis knows this
     * @param availableMicros    balance minus held; what a new request can actually spend
     * @param ledgerConfirmed    true when the totals come from the ledger fold rather than live state
     */
    public record WalletDto(
            UUID orgId,
            long cashBalanceMicros,
            long bonusBalanceMicros,
            long heldMicros,
            long availableMicros,
            boolean ledgerConfirmed,
            long totalRechargedMicros,
            long totalBonusGrantedMicros,
            long totalConsumedCashMicros,
            long totalConsumedBonusMicros,
            Long lowBalanceThresholdMicros,
            long version,
            OffsetDateTime updatedAt
    ) {
    }

    public record WalletLedgerDto(
            UUID id,
            UUID orgId,
            UUID projectId,
            UUID apiKeyId,
            LedgerType ledgerType,
            long cashDeltaMicros,
            long bonusDeltaMicros,
            long packDeltaUnits,
            Long cashBalanceAfterMicros,
            Long bonusBalanceAfterMicros,
            String refRequestId,
            String description,
            OffsetDateTime createdAt
    ) {
    }

    public record EphemeralTokenRequest(@NotNull UUID projectId) {
    }

    public record EphemeralTokenResponse(
            String token,
            int ttlSeconds,
            OffsetDateTime expiresAt
    ) {
    }
}
