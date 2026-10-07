package app.pagefault.hyperllm.domain.model.routing;

import app.pagefault.hyperllm.domain.model.enums.BillingMode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PricingRule(
        UUID id,
        UUID virtualModelId,
        BillingMode billingMode,
        long inputPriceMicros1m,
        long outputPriceMicros1m,
        long cacheReadPriceMicros1m,
        long cacheWritePriceMicros1m,
        long reasoningPriceMicros1m,
        long perCallMicros,
        String tieredRulesJson,
        String modalityRulesJson,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        UUID createdByAdminId,
        OffsetDateTime createdAt
) {
}
