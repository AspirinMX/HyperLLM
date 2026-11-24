package app.pagefault.hyperllm.protocol.ir;

import java.util.Locale;

/**
 * Compatibility policy governing lossy cross-protocol translations.
 */
public enum TranslationPolicy {
    /**
     * Safely degrades non-critical parameter differences (e.g., temperature clamping,
     * dropping unsupported sampling knobs, hoisting OpenAI Chat tool-result images) while
     * still failing fast with HTTP 400 on vendor-locked features that break correctness or ZDR.
     */
    BEST_EFFORT,

    /**
     * Rejects any lossy cross-protocol conversion with HTTP 400.
     */
    STRICT;

    public static TranslationPolicy parseOrDefault(String raw, TranslationPolicy fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return TranslationPolicy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
