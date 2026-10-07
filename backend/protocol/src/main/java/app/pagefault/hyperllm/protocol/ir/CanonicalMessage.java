package app.pagefault.hyperllm.protocol.ir;

/**
 * Lightweight normalized message entry retained in Canonical IR for token fallback and Phase 3 translation.
 */
public record CanonicalMessage(
        String role,
        String content
) {
}
