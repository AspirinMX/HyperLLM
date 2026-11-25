package app.pagefault.hyperllm.protocol.ir;

/**
 * Outbound request payload prepared for upstream dispatch: the normalized Canonical IR graph together
 * with the bytes the outbound codec produced from it.
 */
public record PreparedUpstreamRequest(
        CanonicalChatRequest canonicalRequest,
        CanonicalRequest canonical,
        ProtocolKind outboundProtocol,
        byte[] upstreamBody
) {
    /** Backwards-compatible constructor for adapter callers and tests. */
    public PreparedUpstreamRequest(CanonicalChatRequest canonicalRequest, byte[] upstreamBody) {
        this(canonicalRequest, null, canonicalRequest != null ? canonicalRequest.inboundProtocol() : null, upstreamBody);
    }

    public PreparedUpstreamRequest(CanonicalRequest canonical, ProtocolKind outboundProtocol, byte[] upstreamBody) {
        this(null, canonical, outboundProtocol, upstreamBody);
    }
}
