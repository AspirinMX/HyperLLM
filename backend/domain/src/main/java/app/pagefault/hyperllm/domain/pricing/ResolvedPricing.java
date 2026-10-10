package app.pagefault.hyperllm.domain.pricing;

import app.pagefault.hyperllm.domain.model.enums.BillingMode;

import java.util.UUID;

/**
 * Unit prices and discount resolved for one (organisation, model, instant) triple.
 *
 * <p>{@code tierIndex} is the index of the matched tiered-context rule, or {@code null} when the
 * base prices from {@code pricing_rules} are used.
 */
public record ResolvedPricing(
        PricingSource source,
        BillingMode billingMode,
        int discountBps,
        Integer tierIndex,
        UUID overrideId,
        long inputPriceMicros1m,
        long outputPriceMicros1m,
        long cacheReadPriceMicros1m,
        long cacheWritePriceMicros1m,
        long reasoningPriceMicros1m
) {

    public boolean tokenBilling() {
        return billingMode == BillingMode.TOKEN;
    }
}
