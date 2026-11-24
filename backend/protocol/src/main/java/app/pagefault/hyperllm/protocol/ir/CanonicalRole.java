package app.pagefault.hyperllm.protocol.ir;

/**
 * Normalized conversation turn role in Canonical IR (aligned with Vercel AI SDK
 * {@code LanguageModelV2/V3Prompt}: system, user, assistant, tool, plus developer priority).
 */
public enum CanonicalRole {
    SYSTEM,
    DEVELOPER,
    USER,
    ASSISTANT,
    TOOL
}
