package app.pagefault.hyperllm.domain.pricing;

import app.pagefault.hyperllm.domain.model.billing.OrgModelPricingOverride;
import app.pagefault.hyperllm.domain.model.enums.OverrideMode;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the unit prices and discount for one request.
 *
 * <p>The precedence chain is exclusive rather than multiplicative: an effective model-level
 * override replaces the organisation and tier discount entirely. Negotiated prices are final, so
 * stacking another ratio on top would double the concession and make the invoice unexplainable.
 */
public final class PricingResolver {

    public static final int FULL_DISCOUNT_BPS = 10_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PricingResolver() {
    }

    /**
     * @param orgDiscountRatioBps   organisation-level ratio, {@code null} when it inherits the tier
     * @param tierDefaultDiscountBps the customer tier's default ratio
     * @param rule                  the pricing rule effective at the request instant
     * @param override              an effective override for this (org, model), or {@code null}
     * @param inputTokens           input-side tokens used to pick a tiered-context price
     */
    public static ResolvedPricing resolve(
            Integer orgDiscountRatioBps,
            int tierDefaultDiscountBps,
            PricingRule rule,
            OrgModelPricingOverride override,
            long inputTokens
    ) {
        Objects.requireNonNull(rule, "a pricing rule is required to resolve prices");

        if (override != null && override.overrideMode() == OverrideMode.FIXED_PRICING) {
            long[] prices = parseFixedPricing(override.fixedPricingJson());
            return new ResolvedPricing(
                    PricingSource.OVERRIDE_FIXED, rule.billingMode(), FULL_DISCOUNT_BPS, null, override.id(),
                    prices[0], prices[1], prices[2], prices[3], prices[4]
            );
        }

        List<TieredPricingRules.Tier> tiers = TieredPricingRules.parse(rule.tieredRulesJson());
        Optional<TieredPricingRules.Tier> selected = TieredPricingRules.select(tiers, inputTokens);

        long input = rule.inputPriceMicros1m();
        long output = rule.outputPriceMicros1m();
        long cacheRead = rule.cacheReadPriceMicros1m();
        long cacheWrite = rule.cacheWritePriceMicros1m();
        long reasoning = rule.reasoningPriceMicros1m();
        Integer tierIndex = null;
        if (selected.isPresent()) {
            TieredPricingRules.Tier tier = selected.get();
            input = tier.inputPriceMicros1m();
            output = tier.outputPriceMicros1m();
            cacheRead = tier.cacheReadPriceMicros1m();
            cacheWrite = tier.cacheWritePriceMicros1m();
            reasoning = tier.reasoningPriceMicros1m();
            tierIndex = tiers.indexOf(tier);
        }

        if (override != null) {
            return new ResolvedPricing(
                    PricingSource.OVERRIDE_DISCOUNT, rule.billingMode(), override.discountRatioBps(), tierIndex,
                    override.id(), input, output, cacheRead, cacheWrite, reasoning
            );
        }

        int discountBps = orgDiscountRatioBps == null ? tierDefaultDiscountBps : orgDiscountRatioBps;
        PricingSource source = orgDiscountRatioBps == null ? PricingSource.TIER : PricingSource.ORG;
        return new ResolvedPricing(
                source, rule.billingMode(), discountBps, tierIndex, null,
                input, output, cacheRead, cacheWrite, reasoning
        );
    }

    /**
     * Parses {@code fixed_pricing_json}. Missing dimensions fall back to zero, which makes the
     * mistake visible in the invoice rather than silently reusing list prices from another source.
     */
    private static long[] parseFixedPricing(String json) {
        long[] prices = new long[5];
        if (json == null || json.isBlank()) {
            return prices;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return prices;
            }
            prices[0] = longField(root, "inputPriceMicros1m");
            prices[1] = longField(root, "outputPriceMicros1m");
            prices[2] = longField(root, "cacheReadPriceMicros1m");
            prices[3] = longField(root, "cacheWritePriceMicros1m");
            prices[4] = longField(root, "reasoningPriceMicros1m");
        } catch (Exception ignored) {
            // Malformed fixed pricing yields zeros; publishing is validated elsewhere.
        }
        return prices;
    }

    private static long longField(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : 0L;
    }
}
