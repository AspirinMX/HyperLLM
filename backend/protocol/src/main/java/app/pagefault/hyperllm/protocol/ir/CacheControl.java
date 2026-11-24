package app.pagefault.hyperllm.protocol.ir;

/**
 * Prompt cache breakpoint hint (primarily Anthropic {@code cache_control}).
 */
public record CacheControl(
        String type,
        String ttl
) {
    public static CacheControl ephemeral() {
        return new CacheControl("ephemeral", null);
    }
}
