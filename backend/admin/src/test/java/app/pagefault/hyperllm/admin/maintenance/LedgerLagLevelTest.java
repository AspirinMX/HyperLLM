package app.pagefault.hyperllm.admin.maintenance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerLagLevelTest {

    @Test
    void recordCountAloneIsNotEnoughToCallItDegraded() {
        // Five thousand records at a thousand a second is five seconds of staleness.
        assertThat(LedgerLagLevel.isDegraded(4_000L, 4d, 5_000L, 60d)).isFalse();
    }

    @Test
    void drainTimeDecidesOnceTheRateIsKnown() {
        assertThat(LedgerLagLevel.isDegraded(1_000L, 61d, 5_000L, 60d)).isTrue();
    }

    @Test
    void aStalledConsumerRanksAsSlowEvenWithFewRecords() {
        assertThat(LedgerLagLevel.drainSeconds(10L, 0d)).isEqualTo(100d);
    }

    @Test
    void theRecordCountStillCatchesABurst() {
        assertThat(LedgerLagLevel.isDegraded(6_000L, 1d, 5_000L, 60d)).isTrue();
    }
}
