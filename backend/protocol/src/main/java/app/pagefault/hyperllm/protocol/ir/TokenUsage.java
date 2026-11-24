package app.pagefault.hyperllm.protocol.ir;

/**
 * Five-dimensional strictly disjoint token usage record captured from upstream telemetry
 * or estimated from content.
 *
 * <p>Disjoint billing invariant:
 * <ul>
 *   <li>{@code promptTokens}: uncached regular input tokens</li>
 *   <li>{@code cachedReadTokens}: cache-hit input tokens</li>
 *   <li>{@code cachedWriteTokens}: cache-creation input tokens</li>
 *   <li>{@code completionTokens}: non-reasoning visible output tokens</li>
 *   <li>{@code reasoningTokens}: separately reported thinking/reasoning tokens</li>
 * </ul>
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

    /**
     * Normalizes OpenAI Chat/Responses usage where {@code totalInput} already includes cached tokens
     * and {@code totalOutput} already includes reasoning tokens.
     */
    public static TokenUsage fromOpenAiInclusive(
            int totalInputTokens,
            int cachedReadTokens,
            int cachedWriteTokens,
            int totalOutputTokens,
            int reasoningTokens
    ) {
        int safeCachedRead = Math.max(0, cachedReadTokens);
        int safeCachedWrite = Math.max(0, cachedWriteTokens);
        int safeReasoning = Math.max(0, reasoningTokens);
        int uncachedInput = Math.max(0, Math.max(0, totalInputTokens) - safeCachedRead - safeCachedWrite);
        int visibleOutput = Math.max(0, Math.max(0, totalOutputTokens) - safeReasoning);
        return new TokenUsage(uncachedInput, safeCachedRead, safeCachedWrite, visibleOutput, safeReasoning, false);
    }

    /**
     * Normalizes Anthropic usage where {@code input_tokens} already excludes cache read/write tokens
     * and {@code output_tokens} includes any thinking tokens without a separate breakdown.
     */
    public static TokenUsage fromAnthropic(
            int uncachedInputTokens,
            int cachedReadTokens,
            int cachedWriteTokens,
            int outputTokens
    ) {
        return new TokenUsage(
                Math.max(0, uncachedInputTokens),
                Math.max(0, cachedReadTokens),
                Math.max(0, cachedWriteTokens),
                Math.max(0, outputTokens),
                0,
                false
        );
    }

    /**
     * Normalizes Gemini {@code usageMetadata} where {@code promptTokenCount} includes
     * {@code cachedContentTokenCount} while {@code candidatesTokenCount} excludes {@code thoughtsTokenCount}.
     */
    public static TokenUsage fromGemini(
            int promptTokenCount,
            int toolUsePromptTokenCount,
            int cachedContentTokenCount,
            int candidatesTokenCount,
            int thoughtsTokenCount
    ) {
        int safeCached = Math.max(0, cachedContentTokenCount);
        int totalInput = Math.max(0, promptTokenCount) + Math.max(0, toolUsePromptTokenCount);
        int uncachedInput = Math.max(0, totalInput - safeCached);
        return new TokenUsage(
                uncachedInput,
                safeCached,
                0,
                Math.max(0, candidatesTokenCount),
                Math.max(0, thoughtsTokenCount),
                false
        );
    }

    public int totalInputTokens() {
        return promptTokens + cachedReadTokens + cachedWriteTokens;
    }

    public int totalOutputTokens() {
        return completionTokens + reasoningTokens;
    }

    public int totalTokens() {
        return totalInputTokens() + totalOutputTokens();
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
