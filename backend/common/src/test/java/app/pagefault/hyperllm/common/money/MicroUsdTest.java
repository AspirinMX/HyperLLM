package app.pagefault.hyperllm.common.money;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicroUsdTest {

    @Test
    void ceilCostPerMillionRoundsUpFractionalMicros() {
        assertThat(MicroUsd.ceilCostPerMillion(0, 1_500_000)).isZero();
        assertThat(MicroUsd.ceilCostPerMillion(1_000_000, 3_000_000)).isEqualTo(3_000_000L);
        assertThat(MicroUsd.ceilCostPerMillion(1, 3_000_000)).isEqualTo(3L);
        assertThat(MicroUsd.ceilCostPerMillion(1, 150_000)).isEqualTo(1L); // 0.15 -> ceil to 1 micro-USD
    }

    @Test
    void applyBpsCeilRoundsUp() {
        assertThat(MicroUsd.applyBpsCeil(1_000, 10_000)).isEqualTo(1_000L);
        assertThat(MicroUsd.applyBpsCeil(100, 9_500)).isEqualTo(95L);
        assertThat(MicroUsd.applyBpsCeil(1, 9_500)).isEqualTo(1L); // 0.95 -> ceil to 1
    }

    @Test
    void negativeArgumentsThrow() {
        assertThatThrownBy(() -> MicroUsd.ceilCostPerMillion(-1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MicroUsd.applyBpsCeil(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
