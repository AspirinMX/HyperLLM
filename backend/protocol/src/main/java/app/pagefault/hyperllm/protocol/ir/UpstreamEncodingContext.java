package app.pagefault.hyperllm.protocol.ir;

/**
 * Target upstream channel metadata needed when encoding a {@link CanonicalRequest} to wire JSON.
 */
public record UpstreamEncodingContext(
        String upstreamModelName,
        Integer modelMaxOutputTokens,
        TranslationPolicy policy,
        String thinkingStyle
) {
    public UpstreamEncodingContext {
        policy = policy == null ? TranslationPolicy.BEST_EFFORT : policy;
    }

    public static UpstreamEncodingContext simple(String upstreamModelName) {
        return new UpstreamEncodingContext(upstreamModelName, null, TranslationPolicy.BEST_EFFORT, null);
    }

    public int resolveDefaultMaxOutputTokens(int fallback) {
        return (modelMaxOutputTokens != null && modelMaxOutputTokens > 0) ? modelMaxOutputTokens : fallback;
    }
}
