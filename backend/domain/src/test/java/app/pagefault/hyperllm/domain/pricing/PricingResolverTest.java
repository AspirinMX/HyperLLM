package app.pagefault.hyperllm.domain.pricing;

import app.pagefault.hyperllm.domain.model.billing.OrgModelPricingOverride;
import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.model.enums.OverrideMode;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PricingResolverTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    @Test
    void inheritsTheTierRatioWhenTheOrganisationHasNone() {
        ResolvedPricing pricing = PricingResolver.resolve(null, 9_500, rule("[]"), null, 100);

        assertThat(pricing.source()).isEqualTo(PricingSource.TIER);
        assertThat(pricing.discountBps()).isEqualTo(9_500);
        assertThat(pricing.tierIndex()).isNull();
        assertThat(pricing.inputPriceMicros1m()).isEqualTo(3_000_000L);
    }

    @Test
    void organisationRatioWinsOverTheTierDefault() {
        ResolvedPricing pricing = PricingResolver.resolve(8_800, 9_500, rule("[]"), null, 100);

        assertThat(pricing.source()).isEqualTo(PricingSource.ORG);
        assertThat(pricing.discountBps()).isEqualTo(8_800);
    }

    @Test
    void modelOverrideReplacesTheOrganisationRatioInsteadOfMultiplyingIt() {
        OrgModelPricingOverride override = override(OverrideMode.DISCOUNT_BPS, 7_000, null);
        ResolvedPricing pricing = PricingResolver.resolve(8_800, 9_500, rule("[]"), override, 100);

        assertThat(pricing.source()).isEqualTo(PricingSource.OVERRIDE_DISCOUNT);
        assertThat(pricing.discountBps()).isEqualTo(7_000);
        assertThat(pricing.overrideId()).isEqualTo(override.id());
    }

    @Test
    void fixedPricingIgnoresBothDiscountsAndTiering() {
        String tiered = "[{\"minInputTokens\":0,\"maxInputTokens\":null,"
                + "\"inputPriceMicros1m\":1,\"outputPriceMicros1m\":1,"
                + "\"cacheReadPriceMicros1m\":1,\"cacheWritePriceMicros1m\":1,\"reasoningPriceMicros1m\":1}]";
        OrgModelPricingOverride override = override(OverrideMode.FIXED_PRICING, null,
                "{\"inputPriceMicros1m\":11,\"outputPriceMicros1m\":22,"
                        + "\"cacheReadPriceMicros1m\":33,\"cacheWritePriceMicros1m\":44,\"reasoningPriceMicros1m\":55}");

        ResolvedPricing pricing = PricingResolver.resolve(8_800, 9_500, rule(tiered), override, 999_999);

        assertThat(pricing.source()).isEqualTo(PricingSource.OVERRIDE_FIXED);
        assertThat(pricing.discountBps()).isEqualTo(PricingResolver.FULL_DISCOUNT_BPS);
        assertThat(pricing.tierIndex()).isNull();
        assertThat(pricing.inputPriceMicros1m()).isEqualTo(11L);
        assertThat(pricing.reasoningPriceMicros1m()).isEqualTo(55L);
    }

    @Test
    void selectsTheTierThatCoversTheInputTokens() {
        String tiered = "[{\"minInputTokens\":0,\"maxInputTokens\":131071,"
                + "\"inputPriceMicros1m\":100,\"outputPriceMicros1m\":200,"
                + "\"cacheReadPriceMicros1m\":10,\"cacheWritePriceMicros1m\":20,\"reasoningPriceMicros1m\":200},"
                + "{\"minInputTokens\":131072,\"maxInputTokens\":null,"
                + "\"inputPriceMicros1m\":200,\"outputPriceMicros1m\":400,"
                + "\"cacheReadPriceMicros1m\":20,\"cacheWritePriceMicros1m\":40,\"reasoningPriceMicros1m\":400}]";

        assertThat(PricingResolver.resolve(null, 10_000, rule(tiered), null, 131_071).tierIndex()).isZero();
        ResolvedPricing high = PricingResolver.resolve(null, 10_000, rule(tiered), null, 131_072);
        assertThat(high.tierIndex()).isEqualTo(1);
        assertThat(high.inputPriceMicros1m()).isEqualTo(200L);
        assertThat(high.outputPriceMicros1m()).isEqualTo(400L);
    }

    @Test
    void fallsBackToBasePricesWhenTieringIsMalformed() {
        ResolvedPricing pricing = PricingResolver.resolve(null, 10_000, rule("not-json"), null, 500_000);

        assertThat(pricing.tierIndex()).isNull();
        assertThat(pricing.inputPriceMicros1m()).isEqualTo(3_000_000L);
    }

    @Test
    void validationAcceptsAnEmptyArrayAndRejectsGaps() {
        assertThat(TieredPricingRules.validate("[]")).isEmpty();

        String gap = "[{\"minInputTokens\":0,\"maxInputTokens\":10,"
                + "\"inputPriceMicros1m\":1,\"outputPriceMicros1m\":1,"
                + "\"cacheReadPriceMicros1m\":1,\"cacheWritePriceMicros1m\":1,\"reasoningPriceMicros1m\":1},"
                + "{\"minInputTokens\":12,\"maxInputTokens\":null,"
                + "\"inputPriceMicros1m\":2,\"outputPriceMicros1m\":2,"
                + "\"cacheReadPriceMicros1m\":2,\"cacheWritePriceMicros1m\":2,\"reasoningPriceMicros1m\":2}]";
        assertThat(TieredPricingRules.validate(gap))
                .anySatisfy(error -> assertThat(error).contains("exactly after"));
    }

    @Test
    void validationRejectsATierListThatDoesNotStartAtZero() {
        String shifted = "[{\"minInputTokens\":100,\"maxInputTokens\":null,"
                + "\"inputPriceMicros1m\":1,\"outputPriceMicros1m\":1,"
                + "\"cacheReadPriceMicros1m\":1,\"cacheWritePriceMicros1m\":1,\"reasoningPriceMicros1m\":1}]";
        assertThat(TieredPricingRules.validate(shifted))
                .anySatisfy(error -> assertThat(error).contains("must start at 0"));
    }

    @Test
    void fallsBackToOutputPriceWhenReasoningPriceIsZero() {
        PricingRule zeroReasoningRule = new PricingRule(
                UUID.randomUUID(), UUID.randomUUID(), BillingMode.TOKEN,
                2_000_000L, 12_000_000L, 200_000L, 2_500_000L, 0L, 0L,
                "[]", "{}", NOW.minusDays(1), null, null, NOW
        );
        ResolvedPricing base = PricingResolver.resolve(null, 10_000, zeroReasoningRule, null, 500);
        assertThat(base.reasoningPriceMicros1m()).isEqualTo(12_000_000L);

        String tiered = "[{\"minInputTokens\":0,\"maxInputTokens\":null,"
                + "\"inputPriceMicros1m\":100,\"outputPriceMicros1m\":400,"
                + "\"cacheReadPriceMicros1m\":10,\"cacheWritePriceMicros1m\":20,\"reasoningPriceMicros1m\":0}]";
        ResolvedPricing tieredResolved = PricingResolver.resolve(null, 10_000, rule(tiered), null, 500);
        assertThat(tieredResolved.reasoningPriceMicros1m()).isEqualTo(400L);

        OrgModelPricingOverride fixed = override(OverrideMode.FIXED_PRICING, null,
                "{\"inputPriceMicros1m\":11,\"outputPriceMicros1m\":22,\"reasoningPriceMicros1m\":0}");
        ResolvedPricing fixedResolved = PricingResolver.resolve(null, 10_000, zeroReasoningRule, fixed, 500);
        assertThat(fixedResolved.reasoningPriceMicros1m()).isEqualTo(22L);
    }

    private static PricingRule rule(String tieredJson) {
        return new PricingRule(
                UUID.randomUUID(), UUID.randomUUID(), BillingMode.TOKEN,
                3_000_000L, 15_000_000L, 300_000L, 3_750_000L, 15_000_000L, 0L,
                tieredJson, "{}", NOW.minusDays(1), null, null, NOW
        );
    }

    private static OrgModelPricingOverride override(OverrideMode mode, Integer discountBps, String fixedJson) {
        return new OrgModelPricingOverride(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), mode, discountBps, fixedJson,
                NOW.minusDays(1), null, ToggleStatus.ACTIVE, null, null, 0L, NOW, NOW
        );
    }
}
