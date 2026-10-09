package app.pagefault.hyperllm.protocol.billing;

import app.pagefault.hyperllm.common.money.CostMath;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;

/**
 * Computes exact request charges in micro-USD. Delegates to {@link CostMath} so the gateway, the
 * control plane and the reconciliation jobs share one rounding order.
 */
public final class UsageCostCalculator {

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
        return CostMath.rawCostMicros(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.cachedReadTokens(),
                usage.cachedWriteTokens(),
                usage.reasoningTokens(),
                inputPriceMicros1m,
                outputPriceMicros1m,
                cacheReadPriceMicros1m,
                cacheWritePriceMicros1m,
                reasoningPriceMicros1m
        );
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
        return CostMath.discountedCostMicros(
                computeRawCostMicros(usage, inputPriceMicros1m, outputPriceMicros1m,
                        cacheReadPriceMicros1m, cacheWritePriceMicros1m, reasoningPriceMicros1m),
                effectiveDiscountBps
        );
    }
}
