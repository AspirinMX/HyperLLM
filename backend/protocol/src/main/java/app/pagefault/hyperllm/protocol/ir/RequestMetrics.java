package app.pagefault.hyperllm.protocol.ir;

/**
 * Single-pass prompt token and outbound buffer size estimates captured during request decoding,
 * eliminating redundant full-graph traversals in {@code TokenEstimator} and {@code CodecSupport}.
 */
public record RequestMetrics(
        int estimatedPromptTokens,
        int estimatedEncodedBytes,
        boolean computed
) {
    public static final RequestMetrics UNCOMPUTED = new RequestMetrics(0, 0, false);

    public static RequestMetrics of(int estimatedPromptTokens, int estimatedEncodedBytes) {
        return new RequestMetrics(
                Math.max(0, estimatedPromptTokens),
                Math.max(1024, estimatedEncodedBytes),
                true
        );
    }
}
