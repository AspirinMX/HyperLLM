package app.pagefault.hyperllm.common.money;

import java.math.BigInteger;

/**
 * Integer-only cost arithmetic shared by the gateway and the control plane.
 *
 * <p>Calculation order is fixed and must not be collapsed: the raw token cost is rounded up to the
 * nearest micro-USD first, then the discount is applied with a second ceiling. Each ceiling
 * represents a distinct indivisible unit (micro-USD and basis points), and keeping them apart is
 * what makes historical ledger rows comparable across services.
 */
public final class CostMath {

    private static final BigInteger ONE_MILLION = BigInteger.valueOf(MicroUsd.MICROS_PER_USD);

    private CostMath() {
    }

    /**
     * Rounds the weighted token numerator up to whole micro-USD.
     *
     * @param promptTokens       regular input tokens
     * @param completionTokens   regular output tokens
     * @param cacheReadTokens    prompt cache read tokens
     * @param cacheWriteTokens   prompt cache write tokens
     * @param reasoningTokens    reasoning output tokens
     * @param inputPriceMicros1m price per one million regular input tokens
     * @param outputPriceMicros1m price per one million regular output tokens
     * @param cacheReadPriceMicros1m price per one million cached read tokens
     * @param cacheWritePriceMicros1m price per one million cached write tokens
     * @param reasoningPriceMicros1m price per one million reasoning tokens
     */
    public static long rawCostMicros(
            long promptTokens,
            long completionTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long reasoningTokens,
            long inputPriceMicros1m,
            long outputPriceMicros1m,
            long cacheReadPriceMicros1m,
            long cacheWritePriceMicros1m,
            long reasoningPriceMicros1m
    ) {
        requireNonNegative(promptTokens, completionTokens, cacheReadTokens, cacheWriteTokens, reasoningTokens);
        requireNonNegative(inputPriceMicros1m, outputPriceMicros1m, cacheReadPriceMicros1m,
                cacheWritePriceMicros1m, reasoningPriceMicros1m);

        BigInteger numerator = BigInteger.valueOf(promptTokens).multiply(BigInteger.valueOf(inputPriceMicros1m))
                .add(BigInteger.valueOf(completionTokens).multiply(BigInteger.valueOf(outputPriceMicros1m)))
                .add(BigInteger.valueOf(cacheReadTokens).multiply(BigInteger.valueOf(cacheReadPriceMicros1m)))
                .add(BigInteger.valueOf(cacheWriteTokens).multiply(BigInteger.valueOf(cacheWritePriceMicros1m)))
                .add(BigInteger.valueOf(reasoningTokens).multiply(BigInteger.valueOf(reasoningPriceMicros1m)));
        return ceilDivide(numerator, ONE_MILLION);
    }

    /** Applies a basis-point multiplier (10_000 = 1.0x) on top of an already rounded amount. */
    public static long discountedCostMicros(long rawCostMicros, int discountBps) {
        if (rawCostMicros < 0 || discountBps < 0) {
            throw new IllegalArgumentException("rawCostMicros and discountBps must be non-negative");
        }
        if (rawCostMicros == 0L || discountBps == 0) {
            return 0L;
        }
        return ceilDivide(BigInteger.valueOf(rawCostMicros)
                .multiply(BigInteger.valueOf(discountBps)),
                BigInteger.valueOf(MicroUsd.BPS_BASE));
    }

    /**
     * The share of {@code remainingMicros} covered by {@code consumedUnits} out of
     * {@code remainingUnits}, rounded up. Used to split a charge proportionally across entitlement
     * batches without accumulating rounding drift, because callers always pass the units and the
     * amount still remaining rather than per-batch absolutes.
     */
    public static long proportionalShareMicros(long remainingMicros, long consumedUnits, long remainingUnits) {
        if (remainingMicros <= 0L || consumedUnits <= 0L || remainingUnits <= 0L) {
            return 0L;
        }
        long effectiveUnits = Math.min(consumedUnits, remainingUnits);
        if (effectiveUnits == remainingUnits) {
            return remainingMicros;
        }
        return ceilDivide(BigInteger.valueOf(remainingMicros).multiply(BigInteger.valueOf(effectiveUnits)),
                BigInteger.valueOf(remainingUnits));
    }

    private static long ceilDivide(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() == 0) {
            return 0L;
        }
        BigInteger[] divRem = numerator.divideAndRemainder(denominator);
        long ceil = divRem[0].longValueExact();
        if (divRem[1].signum() > 0) {
            ceil = Math.incrementExact(ceil);
        }
        return ceil;
    }

    private static void requireNonNegative(long... values) {
        for (long value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("Cost inputs must be non-negative");
            }
        }
    }
}
