package app.pagefault.hyperllm.protocol.ir;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/**
 * Custom function tool declaration normalized across OpenAI Chat/Responses, Anthropic, and Gemini.
 *
 * <p>Tool parameter schemas are preferably held as a zero-DOM {@link ValidatedJsonObject} slice so
 * requests with dozens of JSON schemas never construct Jackson {@code ObjectNode} trees on decode,
 * token estimation, normalization, or re-encoding.
 */
public final class CanonicalToolDefinition {

    private final String name;
    private final String description;
    private final JsonNode parametersSchema;
    private final ValidatedJsonObject validatedSchema;
    private final Boolean strict;
    private final CacheControl cacheControl;

    public CanonicalToolDefinition(
            String name,
            String description,
            JsonNode parametersSchema,
            Boolean strict,
            CacheControl cacheControl
    ) {
        this(name, description, parametersSchema, null, strict, cacheControl);
    }

    private CanonicalToolDefinition(
            String name,
            String description,
            JsonNode parametersSchema,
            ValidatedJsonObject validatedSchema,
            Boolean strict,
            CacheControl cacheControl
    ) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.validatedSchema = validatedSchema;
        this.strict = strict;
        this.cacheControl = cacheControl;
    }

    public static CanonicalToolDefinition ofValidated(
            String name,
            String description,
            ValidatedJsonObject validatedSchema,
            Boolean strict,
            CacheControl cacheControl
    ) {
        return new CanonicalToolDefinition(name, description, null, validatedSchema, strict, cacheControl);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** Lazy DOM accessor; avoids building a tree unless a caller inspects the schema AST directly. */
    public JsonNode parametersSchema() {
        if (parametersSchema != null) {
            return parametersSchema;
        }
        return validatedSchema != null ? validatedSchema.asJsonNode() : null;
    }

    public ValidatedJsonObject validatedSchema() {
        return validatedSchema;
    }

    public boolean hasValidObjectSchema() {
        return validatedSchema != null || (parametersSchema != null && parametersSchema.isObject());
    }

    public boolean hasAnySchema() {
        return validatedSchema != null || (parametersSchema != null && !parametersSchema.isNull());
    }

    public void writeParametersSchema(JsonGenerator gen, ObjectMapper mapper) throws IOException {
        if (validatedSchema != null) {
            validatedSchema.writeRawObject(gen, mapper);
            return;
        }
        if (parametersSchema != null && parametersSchema.isObject()) {
            mapper.writeTree(gen, parametersSchema);
            return;
        }
        gen.writeRawValue("{\"type\":\"object\",\"properties\":{}}");
    }

    public void writeParametersSchemaOrRaw(JsonGenerator gen, ObjectMapper mapper) throws IOException {
        if (validatedSchema != null) {
            validatedSchema.writeRawObject(gen, mapper);
            return;
        }
        if (parametersSchema != null && !parametersSchema.isNull()) {
            mapper.writeTree(gen, parametersSchema);
        }
    }

    public int estimatedSchemaTokens() {
        if (validatedSchema != null) {
            return validatedSchema.estimatedTokens();
        }
        if (parametersSchema != null && !parametersSchema.isNull()) {
            return ValidatedJsonObject.ofJsonNode(parametersSchema) != null
                    ? ValidatedJsonObject.ofJsonNode(parametersSchema).estimatedTokens()
                    : 0;
        }
        return 0;
    }

    public int estimatedSchemaWireBytes() {
        if (validatedSchema != null) {
            return validatedSchema.estimatedWireBytes();
        }
        return 256;
    }

    public Boolean strict() {
        return strict;
    }

    public CacheControl cacheControl() {
        return cacheControl;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof CanonicalToolDefinition other) {
            return Objects.equals(name, other.name)
                    && Objects.equals(description, other.description)
                    && Objects.equals(parametersSchema(), other.parametersSchema())
                    && Objects.equals(strict, other.strict)
                    && Objects.equals(cacheControl, other.cacheControl);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, parametersSchema(), strict, cacheControl);
    }

    @Override
    public String toString() {
        return "CanonicalToolDefinition[name=" + name + ", strict=" + strict + "]";
    }
}
