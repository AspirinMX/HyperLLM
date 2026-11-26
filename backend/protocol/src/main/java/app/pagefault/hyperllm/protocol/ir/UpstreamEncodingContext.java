package app.pagefault.hyperllm.protocol.ir;

/**
 * Target upstream channel metadata needed when encoding a {@link CanonicalRequest} to wire JSON.
 */
public record UpstreamEncodingContext(
        String upstreamModelName,
        Integer modelMaxOutputTokens,
        TranslationPolicy policy,
        String thinkingStyle,
        String providerCode,
        boolean supportsThinking,
        String metadataJson,
        ProviderCapabilities preResolvedCapabilities
) {
    public UpstreamEncodingContext {
        policy = policy == null ? TranslationPolicy.BEST_EFFORT : policy;
    }

    public UpstreamEncodingContext(
            String upstreamModelName,
            Integer modelMaxOutputTokens,
            TranslationPolicy policy,
            String thinkingStyle,
            String providerCode,
            boolean supportsThinking,
            String metadataJson
    ) {
        this(upstreamModelName, modelMaxOutputTokens, policy, thinkingStyle,
                providerCode, supportsThinking, metadataJson, null);
    }

    public UpstreamEncodingContext(
            String upstreamModelName,
            Integer modelMaxOutputTokens,
            TranslationPolicy policy,
            String thinkingStyle
    ) {
        this(upstreamModelName, modelMaxOutputTokens, policy, thinkingStyle, null, false, null, null);
    }

    public UpstreamEncodingContext(
            String upstreamModelName,
            Integer modelMaxOutputTokens,
            TranslationPolicy policy,
            String thinkingStyle,
            String providerCode
    ) {
        this(upstreamModelName, modelMaxOutputTokens, policy, thinkingStyle, providerCode, false, null, null);
    }

    public static UpstreamEncodingContext simple(String upstreamModelName) {
        return new UpstreamEncodingContext(upstreamModelName, null, TranslationPolicy.BEST_EFFORT, null, null, false, null, null);
    }

    public static UpstreamEncodingContext forProvider(String providerCode, String upstreamModelName) {
        return new UpstreamEncodingContext(upstreamModelName, null, TranslationPolicy.BEST_EFFORT, null, providerCode, false, null, null);
    }

    public ProviderCapabilities capabilitiesFor(ProtocolKind outboundProtocol) {
        if (preResolvedCapabilities != null && preResolvedCapabilities.outboundProtocol() == outboundProtocol) {
            return preResolvedCapabilities;
        }
        return ProviderCapabilities.resolve(
                outboundProtocol, providerCode, supportsThinking, thinkingStyle, metadataJson);
    }

    public int resolveDefaultMaxOutputTokens(int fallback) {
        return (modelMaxOutputTokens != null && modelMaxOutputTokens > 0) ? modelMaxOutputTokens : fallback;
    }
}
