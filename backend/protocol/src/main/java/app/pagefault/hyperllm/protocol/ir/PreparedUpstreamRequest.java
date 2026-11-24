package app.pagefault.hyperllm.protocol.ir;

/**
 * Outbound request payload prepared for upstream dispatch after passthrough rewrite or IR translation.
 */
public record PreparedUpstreamRequest(
        CanonicalChatRequest canonicalRequest,
        CanonicalRequest canonical,
        ProtocolKind outboundProtocol,
        byte[] upstreamBody,
        boolean passthrough
) {
    /** Backwards-compatible constructor for Phase 1 callers and tests. */
    public PreparedUpstreamRequest(CanonicalChatRequest canonicalRequest, byte[] upstreamBody) {
        this(canonicalRequest, null, canonicalRequest != null ? canonicalRequest.inboundProtocol() : null, upstreamBody, true);
    }

    public PreparedUpstreamRequest(CanonicalRequest canonical, ProtocolKind outboundProtocol, byte[] upstreamBody, boolean passthrough) {
        this(null, canonical, outboundProtocol, upstreamBody, passthrough);
    }
}
