package app.pagefault.hyperllm.protocol.ir;

/**
 * Five-dimensional token usage record captured from upstream telemetry or estimated via jtokkit.
 */
public record TokenUsage(
        int promptTokens,
        int cachedReadTokens,
        int cachedWriteTokens,
        int completionTokens,
        int reasoningTokens,
        boolean estimated
) {

    public TokenUsage {
        if (promptTokens < 0 || cachedReadTokens < 0 || cachedWriteTokens < 0
                || completionTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("Token counts must be non-negative");
        }
    }

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0, 0, 0, false);
    }

    public TokenUsage mergeMax(TokenUsage next) {
        if (next == null) {
            return this;
        }
        return new TokenUsage(
                Math.max(this.promptTokens, next.promptTokens),
                Math.max(this.cachedReadTokens, next.cachedReadTokens),
                Math.max(this.cachedWriteTokens, next.cachedWriteTokens),
                Math.max(this.completionTokens, next.completionTokens),
                Math.max(this.reasoningTokens, next.reasoningTokens),
                this.estimated || next.estimated
        );
    }
}
