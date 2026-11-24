package app.pagefault.hyperllm.protocol.ir;

import java.util.List;

/**
 * Sampling and output-limit parameters shared across text-generation protocols.
 */
public record CanonicalGenerationConfig(
        Integer maxOutputTokens,
        Double temperature,
        Double topP,
        Integer topK,
        List<String> stopSequences,
        Long seed,
        Double presencePenalty,
        Double frequencyPenalty,
        Integer candidateCount
) {
    public CanonicalGenerationConfig {
        stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
    }

    public static CanonicalGenerationConfig empty() {
        return new CanonicalGenerationConfig(null, null, null, null, List.of(), null, null, null, null);
    }
}
