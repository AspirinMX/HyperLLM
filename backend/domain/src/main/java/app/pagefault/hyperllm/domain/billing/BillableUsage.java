package app.pagefault.hyperllm.domain.billing;

/**
 * The five billable token dimensions. Mirrors {@code protocol.ir.TokenUsage} without importing it,
 * because {@code domain} must not depend on the protocol module.
 */
public record BillableUsage(
        long promptTokens,
        long completionTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long reasoningTokens
) {

    public static BillableUsage empty() {
        return new BillableUsage(0L, 0L, 0L, 0L, 0L);
    }

    public long totalTokens() {
        return promptTokens + completionTokens + cacheReadTokens + cacheWriteTokens + reasoningTokens;
    }
}
