package app.pagefault.hyperllm.protocol.billing;

import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsageCostCalculatorTest {

    @Test
    void computesSingleStepCeilingCostAcrossFiveDimensionsWithDiscount() {
        TokenUsage usage = new TokenUsage(100, 50, 10, 200, 20, false);

        // numerator = 100*2_500_000 + 200*10_000_000 + 50*500_000 + 10*1_000_000 + 20*10_000_000
        // = 250_000_000 + 2_000_000_000 + 25_000_000 + 10_000_000 + 200_000_000 = 2_485_000_000
        // raw = ceil(2_485_000_000 / 1_000_000) = 2485
        long raw = UsageCostCalculator.computeRawCostMicros(
                usage, 2_500_000L, 10_000_000L, 500_000L, 1_000_000L, 10_000_000L
        );
        assertThat(raw).isEqualTo(2_485L);

        // 2485 * 9500 / 10000 = 2360.75 -> ceil = 2361
        long discounted = UsageCostCalculator.computeDiscountedCostMicros(
                usage, 2_500_000L, 10_000_000L, 500_000L, 1_000_000L, 10_000_000L, 9500
        );
        assertThat(discounted).isEqualTo(2_361L);
    }

    @Test
    void roundsFractionalSingleTokenUpToAtLeastOneMicroUsd() {
        TokenUsage usage = new TokenUsage(1, 0, 0, 0, 0, false);
        long raw = UsageCostCalculator.computeRawCostMicros(usage, 1L, 0L, 0L, 0L, 0L);
        assertThat(raw).isEqualTo(1L);
    }
}
