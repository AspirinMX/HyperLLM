package app.pagefault.hyperllm.protocol.ir;

/**
 * Canonical completion stop reason shared across all four text-generation protocols.
 */
public enum CanonicalStopReason {
    END_TURN,
    TOOL_CALLS,
    MAX_TOKENS,
    STOP_SEQUENCE,
    CONTENT_FILTER,
    REFUSAL,
    MALFORMED_TOOL_CALL,
    ERROR
}
