package app.pagefault.hyperllm.protocol.ir;

import java.util.Locale;

/**
 * Inbound and upstream LLM text-generation wire protocol families supported by HyperLLM.
 */
public enum ProtocolKind {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT;

    public static ProtocolKind parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ProtocolKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
