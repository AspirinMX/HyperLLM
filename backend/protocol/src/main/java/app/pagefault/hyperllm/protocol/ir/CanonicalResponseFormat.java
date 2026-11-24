package app.pagefault.hyperllm.protocol.ir;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Structured output format specification.
 */
public sealed interface CanonicalResponseFormat permits
        CanonicalResponseFormat.TextFormat,
        CanonicalResponseFormat.JsonObjectFormat,
        CanonicalResponseFormat.JsonSchemaFormat {

    record TextFormat() implements CanonicalResponseFormat {
    }

    record JsonObjectFormat() implements CanonicalResponseFormat {
    }

    record JsonSchemaFormat(
            String name,
            String description,
            JsonNode schema,
            Boolean strict
    ) implements CanonicalResponseFormat {
        public JsonSchemaFormat {
            name = (name == null || name.isBlank()) ? "response" : name.trim();
        }
    }
}
