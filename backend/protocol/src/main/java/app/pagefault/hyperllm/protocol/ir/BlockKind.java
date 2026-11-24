package app.pagefault.hyperllm.protocol.ir;

/**
 * Logical content block kind opened during streaming output.
 */
public enum BlockKind {
    TEXT,
    THINKING,
    REDACTED_THINKING,
    TOOL_CALL,
    REFUSAL
}
