package app.pagefault.hyperllm.protocol.ir;

/**
 * System or developer instruction block hoisted to the top level of {@link CanonicalRequest}.
 */
public record CanonicalSystemPart(
        String text,
        boolean developerPriority,
        CacheControl cacheControl
) {
    public CanonicalSystemPart {
        text = text == null ? "" : text;
    }
}
