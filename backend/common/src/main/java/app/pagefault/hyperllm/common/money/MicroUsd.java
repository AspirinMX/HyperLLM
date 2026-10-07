package app.pagefault.hyperllm.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact integer arithmetic helpers for micro-USD (1 USD = 1,000,000 micro-USD)
 * and basis-point discounts (10,000 bps = 1.0000x).
 */
public final class MicroUsd {

    public static final long MICROS_PER_USD = 1_000_000L;
    public static final int BPS_BASE = 10_000;

    private MicroUsd() {
    }

    /**
     * Computes ceiling cost in micro-USD for a token count and a per-1M-token price.
     */
    public static long ceilCostPerMillion(long tokens, long priceMicrosPer1M) {
        if (tokens < 0 || priceMicrosPer1M < 0) {
            throw new IllegalArgumentException("tokens and priceMicrosPer1M must be non-negative");
        }
        if (tokens == 0 || priceMicrosPer1M == 0) {
            return 0L;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(BigDecimal.valueOf(priceMicrosPer1M))
                .divide(BigDecimal.valueOf(MICROS_PER_USD), 0, RoundingMode.CEILING)
                .longValueExact();
    }

    /**
     * Applies a basis-point multiplier (10,000 = 1.0x) and rounds up to the nearest micro-USD.
     */
    public static long applyBpsCeil(long rawMicros, int discountBps) {
        if (rawMicros < 0 || discountBps < 0) {
            throw new IllegalArgumentException("rawMicros and discountBps must be non-negative");
        }
        if (rawMicros == 0 || discountBps == 0) {
            return 0L;
        }
        return BigDecimal.valueOf(rawMicros)
                .multiply(BigDecimal.valueOf(discountBps))
                .divide(BigDecimal.valueOf(BPS_BASE), 0, RoundingMode.CEILING)
                .longValueExact();
    }
}
