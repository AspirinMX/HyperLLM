package app.pagefault.hyperllm.common.money;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostMathTest {

    @Test
    void rawCostCeilsToTheNearestMicro() {
        // 1 token at 3 micros per million rounds up to 1 micro rather than down to 0.
        assertThat(CostMath.rawCostMicros(1, 0, 0, 0, 0, 3, 0, 0, 0, 0)).isEqualTo(1L);
        // Exactly divisible: 1_000 tokens at 3_000_000 micros per million is 3_000 micros.
        assertThat(CostMath.rawCostMicros(1_000, 0, 0, 0, 0, 3_000_000, 0, 0, 0, 0)).isEqualTo(3_000L);
        assertThat(CostMath.rawCostMicros(0, 0, 0, 0, 0, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000))
                .isZero();
    }

    @Test
    void rawCostSumsEveryTokenDimension() {
        long cost = CostMath.rawCostMicros(1_000, 2_000, 3_000, 4_000, 5_000,
                1_000_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000);
        // 1000 + 4000 + 9000 + 16000 + 25000 = 55_000
        assertThat(cost).isEqualTo(55_000L);
    }

    @Test
    void discountAppliesASecondCeiling() {
        assertThat(CostMath.discountedCostMicros(1_000L, 10_000)).isEqualTo(1_000L);
        assertThat(CostMath.discountedCostMicros(1_000L, 9_500)).isEqualTo(950L);
        // 1_001 * 9_500 / 10_000 = 950.95 -> 951
        assertThat(CostMath.discountedCostMicros(1_001L, 9_500)).isEqualTo(951L);
        assertThat(CostMath.discountedCostMicros(1_000L, 0)).isZero();
    }

    @Test
    void proportionalShareKeepsTheWholeAmountForFullCoverage() {
        assertThat(CostMath.proportionalShareMicros(1_000L, 1_000L, 1_000L)).isEqualTo(1_000L);
        assertThat(CostMath.proportionalShareMicros(1_000L, 2_000L, 1_000L)).isEqualTo(1_000L);
        assertThat(CostMath.proportionalShareMicros(1_000L, 400L, 1_000L)).isEqualTo(400L);
        // 999 * 1 / 3 = 333 exactly; 1_000 * 1 / 3 = 333.33 -> 334
        assertThat(CostMath.proportionalShareMicros(1_000L, 1L, 3L)).isEqualTo(334L);
        assertThat(CostMath.proportionalShareMicros(1_000L, 0L, 3L)).isZero();
        assertThat(CostMath.proportionalShareMicros(1_000L, 1L, 0L)).isZero();
    }

    @Test
    void rejectsNegativeInputs() {
        assertThatThrownBy(() -> CostMath.rawCostMicros(-1, 0, 0, 0, 0, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CostMath.discountedCostMicros(-1L, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesAmountsThatWouldOverflowLongMultiplication() {
        long huge = 1_000_000_000L;
        // 1e9 tokens * 2e9 micros per million = 2e18 before division, at the edge of long; the
        // BigInteger path keeps the intermediate product exact, and the result is 2e12 micros.
        assertThat(CostMath.rawCostMicros(huge, 0, 0, 0, 0, 2_000_000_000L, 0, 0, 0, 0))
                .isEqualTo(2_000_000_000_000L);
    }
}
