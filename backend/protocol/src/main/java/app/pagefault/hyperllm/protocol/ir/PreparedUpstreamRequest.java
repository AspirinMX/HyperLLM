package app.pagefault.hyperllm.protocol.ir;

/**
 * Outbound request payload prepared for upstream dispatch after model mapping and stream usage injection.
 */
public record PreparedUpstreamRequest(
        CanonicalChatRequest canonicalRequest,
        byte[] upstreamBody
) {
}
