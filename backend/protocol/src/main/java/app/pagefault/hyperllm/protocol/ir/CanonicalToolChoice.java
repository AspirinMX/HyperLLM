package app.pagefault.hyperllm.protocol.ir;

/**
 * Tool selection directive and parallel execution flag.
 */
public record CanonicalToolChoice(
        ToolChoiceMode mode,
        String specificToolName,
        Boolean parallelToolCalls
) {
    public enum ToolChoiceMode {
        OMITTED,
        AUTO,
        NONE,
        REQUIRED,
        SPECIFIC
    }

    public CanonicalToolChoice {
        mode = mode == null ? ToolChoiceMode.OMITTED : mode;
    }

    public static CanonicalToolChoice omitted() {
        return new CanonicalToolChoice(ToolChoiceMode.OMITTED, null, null);
    }
}
