package app.pagefault.hyperllm.protocol.ir;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Custom function tool declaration normalized across OpenAI Chat/Responses, Anthropic, and Gemini.
 */
public record CanonicalToolDefinition(
        String name,
        String description,
        JsonNode parametersSchema,
        Boolean strict,
        CacheControl cacheControl
) {
}
