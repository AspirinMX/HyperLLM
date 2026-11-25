package app.pagefault.hyperllm.protocol.ir;

/**
 * Tiny immutable request metadata (~48 bytes) retained by streaming bridges and response handlers
 * after the upstream request has been dispatched, preventing large multi-turn {@link CanonicalRequest}
 * graphs and raw request bodies from being pinned in Old Gen during long-lived SSE streams.
 */
public record StreamRequestMetadata(
        ProtocolKind inboundProtocol,
        String model,
        boolean clientRequestedUsage,
        boolean geminiAltSse,
        int estimatedPromptTokens
) {
    public static StreamRequestMetadata fromCanonical(CanonicalRequest request, int estimatedPromptTokens) {
        return new StreamRequestMetadata(
                request.inboundProtocol(),
                request.model(),
                request.clientRequestedUsage(),
                request.options().geminiAltSse(),
                Math.max(0, estimatedPromptTokens)
        );
    }
}
