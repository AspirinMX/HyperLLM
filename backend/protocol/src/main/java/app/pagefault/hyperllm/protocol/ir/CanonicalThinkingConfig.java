package app.pagefault.hyperllm.protocol.ir;

import java.util.Locale;

/**
 * Normalized reasoning / extended thinking configuration.
 */
public record CanonicalThinkingConfig(
        ThinkingMode mode,
        ThinkingEffort effort,
        Integer budgetTokens,
        Boolean includeThoughts,
        String summaryMode
) {
    public enum ThinkingMode {
        OMITTED,
        DISABLED,
        ENABLED,
        ADAPTIVE
    }

    public enum ThinkingEffort {
        OMITTED,
        NONE,
        MINIMAL,
        LOW,
        MEDIUM,
        HIGH,
        XHIGH,
        MAX;

        public static ThinkingEffort parseOrOmitted(String raw) {
            if (raw == null || raw.isBlank()) {
                return OMITTED;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "none" -> NONE;
                case "minimal" -> MINIMAL;
                case "low" -> LOW;
                case "medium" -> MEDIUM;
                case "high" -> HIGH;
                case "xhigh" -> XHIGH;
                case "max" -> MAX;
                default -> OMITTED;
            };
        }
    }

    public CanonicalThinkingConfig {
        mode = mode == null ? ThinkingMode.OMITTED : mode;
        effort = effort == null ? ThinkingEffort.OMITTED : effort;
    }

    public static CanonicalThinkingConfig omitted() {
        return new CanonicalThinkingConfig(ThinkingMode.OMITTED, ThinkingEffort.OMITTED, null, null, null);
    }

    public boolean isEnabled() {
        return mode == ThinkingMode.ENABLED
                || mode == ThinkingMode.ADAPTIVE
                || (mode == ThinkingMode.OMITTED && effort != ThinkingEffort.OMITTED && effort != ThinkingEffort.NONE);
    }
}
