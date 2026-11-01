package app.pagefault.hyperllm.console.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAmountsTest {

    @Test
    void roundsTheCreditUpAndUsesTheStoredRate() {
        BigDecimal cny = PaymentAmounts.canonicalRate(new BigDecimal("0.140000004"));

        assertThat(cny).isEqualByComparingTo("0.14000000");
        assertThat(PaymentAmounts.creditMicros(1L, BigDecimal.ONE)).isEqualTo(10_000L);
        assertThat(PaymentAmounts.creditMicros(1L, cny)).isEqualTo(1_400L);
        assertThat(PaymentAmounts.creditMicros(3L, cny)).isEqualTo(4_200L);
    }

    @Test
    void roundsThePriceUpSoTheChannelNeverChargesLess() {
        BigDecimal cny = PaymentAmounts.canonicalRate(new BigDecimal("0.14"));

        assertThat(PaymentAmounts.payAmountMinor(10_000_000L, BigDecimal.ONE)).isEqualTo(1_000L);
        assertThat(PaymentAmounts.payAmountMinor(10_000_000L, cny)).isEqualTo(7_143L);
        assertThat(PaymentAmounts.payAmountMinor(1L, cny)).isEqualTo(1L);
    }

    @Test
    void rejectsAmountsOutsideTheExactRange() {
        assertThatThrownBy(() -> PaymentAmounts.creditMicros(0L, BigDecimal.ONE))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> PaymentAmounts.canonicalRate(BigDecimal.ZERO))
                .hasMessageContaining("positive");
    }
}
