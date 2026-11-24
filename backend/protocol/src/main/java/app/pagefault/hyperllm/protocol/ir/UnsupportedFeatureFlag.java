package app.pagefault.hyperllm.protocol.ir;

/**
 * Records a vendor-specific request feature discovered during decoding.
 * <ul>
 *   <li>{@link Severity#HARD_INCOMPATIBLE}: Stateful or security-sensitive features
 *       (e.g. {@code previous_response_id}, {@code conversation}, {@code background},
 *       {@code audio_output}) that are allowed only on homomorphic passthrough and
 *       fail fast with HTTP 400 on cross-protocol translation even in {@code BEST_EFFORT}.</li>
 *   <li>{@link Severity#DEGRADABLE}: Agent or vendor extensions (e.g. built-in server tools
 *       like {@code web_search}, unknown content blocks like {@code tool_reference} or
 *       {@code document}) that are safely dropped in {@code BEST_EFFORT} and rejected in
 *       {@code STRICT}.</li>
 * </ul>
 */
public record UnsupportedFeatureFlag(
        String featureName,
        String detail,
        Severity severity
) {
    public enum Severity {
        HARD_INCOMPATIBLE,
        DEGRADABLE
    }

    public UnsupportedFeatureFlag {
        severity = severity == null ? Severity.HARD_INCOMPATIBLE : severity;
    }

    public UnsupportedFeatureFlag(String featureName, String detail) {
        this(featureName, detail, Severity.HARD_INCOMPATIBLE);
    }

    public static UnsupportedFeatureFlag hard(String featureName, String detail) {
        return new UnsupportedFeatureFlag(featureName, detail, Severity.HARD_INCOMPATIBLE);
    }

    public static UnsupportedFeatureFlag degradable(String featureName, String detail) {
        return new UnsupportedFeatureFlag(featureName, detail, Severity.DEGRADABLE);
    }

    public boolean isHardIncompatible() {
        return severity == Severity.HARD_INCOMPATIBLE;
    }
}
