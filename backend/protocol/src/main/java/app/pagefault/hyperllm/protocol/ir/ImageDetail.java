package app.pagefault.hyperllm.protocol.ir;

import java.util.Locale;

/**
 * Vision resolution hint normalized across OpenAI Chat/Responses and Gemini.
 */
public enum ImageDetail {
    AUTO,
    LOW,
    HIGH,
    ORIGINAL;

    public static ImageDetail parseOrDefault(String raw, ImageDetail fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> LOW;
            case "high" -> HIGH;
            case "original" -> ORIGINAL;
            default -> AUTO;
        };
    }

    public String toOpenAiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
