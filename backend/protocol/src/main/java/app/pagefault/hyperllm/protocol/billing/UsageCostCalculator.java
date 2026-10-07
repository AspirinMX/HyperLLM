package app.pagefault.hyperllm.protocol.billing;

import app.pagefault.hyperllm.common.money.MicroUsd;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;

import java.math.BigInteger;

/**
 * Computes exact request charges in micro-USD with single-step ceiling rounding.
 */
public final class UsageCostCalculator {

    private static final BigInteger ONE_MILLION = BigInteger.valueOf(MicroUsd.MICROS_PER_USD);

    private UsageCostCalculator() {
    }

    public static long computeRawCostMicros(
            TokenUsage usage,
            long inputPriceMicros1m,
            long outputPriceMicros1m,
            long cacheReadPriceMicros1m,
            long cacheWritePriceMicros1m,
            long reasoningPriceMicros1m
    ) {
        if (usage == null) {
            return 0L;
        }
        BigInteger numerator = BigInteger.valueOf(usage.promptTokens()).multiply(BigInteger.valueOf(inputPriceMicros1m))
                .add(BigInteger.valueOf(usage.completionTokens()).multiply(BigInteger.valueOf(outputPriceMicros1m)))
                .add(BigInteger.valueOf(usage.cachedReadTokens()).multiply(BigInteger.valueOf(cacheReadPriceMicros1m)))
                .add(BigInteger.valueOf(usage.cachedWriteTokens()).multiply(BigInteger.valueOf(cacheWritePriceMicros1m)))
                .add(BigInteger.valueOf(usage.reasoningTokens()).multiply(BigInteger.valueOf(reasoningPriceMicros1m)));
        if (numerator.signum() == 0) {
            return 0L;
        }
        BigInteger[] divRem = numerator.divideAndRemainder(ONE_MILLION);
        long ceil = divRem[0].longValueExact();
        if (divRem[1].signum() > 0) {
            ceil = Math.incrementExact(ceil);
        }
        return ceil;
    }

    public static long computeDiscountedCostMicros(
            TokenUsage usage,
            long inputPriceMicros1m,
            long outputPriceMicros1m,
            long cacheReadPriceMicros1m,
            long cacheWritePriceMicros1m,
            long reasoningPriceMicros1m,
            int effectiveDiscountBps
    ) {
        long rawCost = computeRawCostMicros(
                usage,
                inputPriceMicros1m,
                outputPriceMicros1m,
                cacheReadPriceMicros1m,
                cacheWritePriceMicros1m,
                reasoningPriceMicros1m
        );
        return MicroUsd.applyBpsCeil(rawCost, effectiveDiscountBps);
    }
}
