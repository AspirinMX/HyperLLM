package app.pagefault.hyperllm.domain.billing;

import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.pricing.PricingSource;
import app.pagefault.hyperllm.domain.pricing.ResolvedPricing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AllocationDetailCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void encodesProvenanceUsageLegsAndTotals() throws Exception {
        ResolvedPricing pricing = new ResolvedPricing(
                PricingSource.OVERRIDE_DISCOUNT, BillingMode.TOKEN, 9_500, 1, UUID.randomUUID(),
                3_000_000L, 15_000_000L, 300_000L, 3_750_000L, 15_000_000L
        );
        BillableUsage usage = new BillableUsage(1_200L, 800L, 0L, 0L, 0L);
        UUID packId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        AllocationPlan plan = new AllocationPlan(List.of(
                AllocationLeg.pack(packId, 1_500L, 788L),
                AllocationLeg.bonus(grantId, 120L),
                AllocationLeg.cash(12L)
        ), 920L, 0L, false);

        JsonNode root = MAPPER.readTree(AllocationDetailCodec.encodeV1(pricing, usage, plan));

        assertThat(root.path("v").asInt()).isEqualTo(1);
        assertThat(root.path("pricing").path("source").asText()).isEqualTo("OVERRIDE_DISCOUNT");
        assertThat(root.path("pricing").path("discountBps").asInt()).isEqualTo(9_500);
        assertThat(root.path("pricing").path("tierIndex").asInt()).isEqualTo(1);
        assertThat(root.path("pricing").path("unitPrices").path("input").asLong()).isEqualTo(3_000_000L);
        assertThat(root.path("usage").path("prompt").asLong()).isEqualTo(1_200L);
        assertThat(root.path("legs")).hasSize(3);
        assertThat(root.path("legs").get(0).path("type").asText()).isEqualTo("PACK");
        assertThat(root.path("legs").get(0).path("id").asText()).isEqualTo(packId.toString());
        assertThat(root.path("legs").get(0).path("units").asLong()).isEqualTo(1_500L);
        assertThat(root.path("legs").get(2).path("type").asText()).isEqualTo("CASH");
        assertThat(root.path("legs").get(2).has("id")).isFalse();
        assertThat(root.path("totals").path("chargedMicros").asLong()).isEqualTo(920L);
        assertThat(root.path("totals").path("truncatedLegs").asInt()).isZero();
    }

    @Test
    void truncatesLongLegListsButReportsHowManyWereDropped() throws Exception {
        ResolvedPricing pricing = new ResolvedPricing(
                PricingSource.TIER, BillingMode.TOKEN, 10_000, null, null,
                1L, 1L, 1L, 1L, 1L
        );
        List<AllocationLeg> legs = new ArrayList<>();
        for (int i = 0; i < AllocationDetailCodec.MAX_LEGS + 3; i++) {
            legs.add(AllocationLeg.bonus(UUID.randomUUID(), 1L));
        }
        AllocationPlan plan = new AllocationPlan(List.copyOf(legs), 35L, 0L, true);

        JsonNode root = MAPPER.readTree(AllocationDetailCodec.encodeV1(pricing, BillableUsage.empty(), plan));

        assertThat(root.path("legs")).hasSize(AllocationDetailCodec.MAX_LEGS);
        assertThat(root.path("totals").path("truncatedLegs").asInt()).isEqualTo(3);
        assertThat(root.path("totals").path("degraded").asBoolean()).isTrue();
    }

    @Test
    void encodesAnEmptyPlan() throws Exception {
        ResolvedPricing pricing = new ResolvedPricing(
                PricingSource.TIER, BillingMode.TOKEN, 10_000, null, null,
                1L, 1L, 1L, 1L, 1L
        );
        AllocationPlan plan = new AllocationPlan(List.of(), 0L, 0L, false);

        JsonNode root = MAPPER.readTree(AllocationDetailCodec.encodeV1(pricing, BillableUsage.empty(), plan));

        assertThat(root.path("legs")).isEmpty();
        assertThat(root.path("totals").path("chargedMicros").asLong()).isZero();
    }
}
