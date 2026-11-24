package app.pagefault.hyperllm.protocol.ir;

/**
 * Cross-protocol prompt cache settings carried on {@link CanonicalRequest}.
 */
public record CanonicalCacheConfig(
        CacheControl topLevelCacheControl,
        String promptCacheKey,
        String promptCacheRetention,
        String geminiCachedContent
) {
    public static CanonicalCacheConfig empty() {
        return new CanonicalCacheConfig(null, null, null, null);
    }
}
