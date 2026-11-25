package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.protocol.ir.CacheControl;
import app.pagefault.hyperllm.protocol.ir.CanonicalContentPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalSystemPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalThinkingConfig;
import app.pagefault.hyperllm.protocol.ir.CanonicalToolDefinition;
import app.pagefault.hyperllm.protocol.ir.CanonicalTurn;
import app.pagefault.hyperllm.protocol.ir.ImageSource;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

/**
 * Shared JSON parsing, Data URI extraction, reasoning effort/budget conversion, and safe truncation helpers.
 */
public final class CodecSupport {

    public static final int MAX_ACCUMULATED_CHARS = 1_000_000;
    public static final String GEMINI_SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator";

    private CodecSupport() {
    }

    public static ObjectNode parseResponseObjectRoot(ObjectMapper mapper, byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new HyperLlmException(ErrorCode.UPSTREAM_ERROR, "Upstream returned empty response body");
        }
        try {
            JsonNode tree = mapper.readTree(rawBody);
            if (!(tree instanceof ObjectNode obj)) {
                throw new HyperLlmException(ErrorCode.UPSTREAM_ERROR, "Upstream response body is not a JSON object");
            }
            return obj;
        } catch (IOException ex) {
            throw new HyperLlmException(ErrorCode.UPSTREAM_ERROR, "Malformed JSON in upstream response", ex);
        }
    }

    public static String requireModel(ObjectNode root) {
        JsonNode modelNode = root.get("model");
        if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'model' is required and must be non-blank");
        }
        return modelNode.asText().trim();
    }

    public static byte[] writeJsonBytes(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize protocol JSON", ex);
        }
    }

    public static String writeJsonString(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize protocol JSON", ex);
        }
    }

    public static ImageSource parseImageUrlOrDataUri(String url) {
        if (url == null || url.isBlank()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Image URL must not be blank");
        }
        String trimmed = url.trim();
        if (trimmed.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = trimmed.indexOf(',');
            if (comma < 0) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed data URI in image input");
            }
            String meta = trimmed.substring(5, comma);
            String data = trimmed.substring(comma + 1);
            String mediaType = "image/png";
            int semi = meta.indexOf(';');
            String candidateMime = (semi >= 0 ? meta.substring(0, semi) : meta).trim();
            if (!candidateMime.isEmpty()) {
                mediaType = candidateMime;
            }
            return new ImageSource.Base64Image(mediaType, data);
        }
        return new ImageSource.UrlImage(trimmed, guessMimeFromUrl(trimmed));
    }

    public static String guessMimeFromUrl(String url) {
        if (url == null) {
            return null;
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return null;
    }

    public static CacheControl parseCacheControl(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String type = node.path("type").asText("ephemeral");
        String ttl = node.hasNonNull("ttl") ? node.get("ttl").asText() : null;
        return new CacheControl(type, ttl);
    }

    public static ObjectNode encodeCacheControl(ObjectMapper mapper, CacheControl cc) {
        ObjectNode obj = mapper.createObjectNode();
        obj.put("type", cc.type() != null ? cc.type() : "ephemeral");
        if (cc.ttl() != null && !cc.ttl().isBlank()) {
            obj.put("ttl", cc.ttl());
        }
        return obj;
    }

    public static ObjectNode parseArgumentsToObject(ObjectMapper mapper, String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(rawArgs);
            if (parsed instanceof ObjectNode obj) {
                return obj;
            }
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.set("value", parsed);
            return wrapper;
        } catch (Exception ex) {
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("_raw", rawArgs);
            return wrapper;
        }
    }

    public static String serializeArguments(ObjectMapper mapper, CanonicalContentPart.ToolCallPart tc) {
        if (tc.rawArgumentsJson() != null && !tc.rawArgumentsJson().isBlank()) {
            return tc.rawArgumentsJson();
        }
        if (tc.argumentsJson() != null && !tc.argumentsJson().isNull()) {
            return writeJsonString(mapper, tc.argumentsJson());
        }
        return "{}";
    }

    public static ObjectNode argumentsAsObject(ObjectMapper mapper, CanonicalContentPart.ToolCallPart tc) {
        if (tc.argumentsJson() instanceof ObjectNode obj) {
            return obj.deepCopy();
        }
        return parseArgumentsToObject(mapper, tc.rawArgumentsJson());
    }

    public static String generateId(String prefix) {
        return prefix + UuidV7.next().toString().replace("-", "");
    }

    public static long nowEpochSeconds() {
        return Instant.now().getEpochSecond();
    }

    public static void appendBounded(StringBuilder sb, String fragment) {
        if (fragment == null || fragment.isEmpty() || sb.length() >= MAX_ACCUMULATED_CHARS) {
            return;
        }
        int remaining = MAX_ACCUMULATED_CHARS - sb.length();
        if (fragment.length() <= remaining) {
            sb.append(fragment);
        } else {
            sb.append(fragment, 0, remaining);
        }
    }

    public static String toOpenAiReasoningEffort(CanonicalThinkingConfig thinking) {
        if (thinking == null || thinking.mode() == CanonicalThinkingConfig.ThinkingMode.OMITTED
                && thinking.effort() == CanonicalThinkingConfig.ThinkingEffort.OMITTED) {
            return null;
        }
        if (thinking.mode() == CanonicalThinkingConfig.ThinkingMode.DISABLED) {
            return "none";
        }
        if (thinking.effort() != CanonicalThinkingConfig.ThinkingEffort.OMITTED) {
            return switch (thinking.effort()) {
                case NONE -> "none";
                case MINIMAL -> "minimal";
                case LOW -> "low";
                case MEDIUM -> "medium";
                case HIGH -> "high";
                case XHIGH, MAX -> "xhigh";
                case OMITTED -> null;
            };
        }
        if (thinking.budgetTokens() != null) {
            int b = thinking.budgetTokens();
            if (b <= 0) {
                return "none";
            }
            if (b <= 1024) {
                return "minimal";
            }
            if (b <= 3072) {
                return "low";
            }
            if (b <= 10_240) {
                return "medium";
            }
            if (b <= 24_576) {
                return "high";
            }
            return "xhigh";
        }
        return "medium";
    }

    public static int toAnthropicBudgetTokens(CanonicalThinkingConfig thinking) {
        if (thinking.budgetTokens() != null && thinking.budgetTokens() >= 1024) {
            return thinking.budgetTokens();
        }
        return switch (thinking.effort()) {
            case MINIMAL -> 1024;
            case LOW -> 2048;
            case MEDIUM, OMITTED -> 8192;
            case HIGH -> 16_384;
            case XHIGH, MAX -> 32_768;
            case NONE -> 0;
        };
    }

    public static String toAnthropicAdaptiveEffort(CanonicalThinkingConfig thinking) {
        if (thinking.effort() != CanonicalThinkingConfig.ThinkingEffort.OMITTED) {
            return switch (thinking.effort()) {
                case MINIMAL, LOW -> "low";
                case MEDIUM -> "medium";
                case HIGH -> "high";
                case XHIGH, MAX -> "max";
                case NONE, OMITTED -> null;
            };
        }
        if (thinking.budgetTokens() != null) {
            int b = thinking.budgetTokens();
            if (b <= 3072) {
                return "low";
            }
            if (b <= 10_240) {
                return "medium";
            }
            if (b <= 20_480) {
                return "high";
            }
            return "max";
        }
        return "medium";
    }

    public static String toGeminiThinkingLevel(CanonicalThinkingConfig thinking) {
        if (thinking.effort() != CanonicalThinkingConfig.ThinkingEffort.OMITTED) {
            return switch (thinking.effort()) {
                case NONE, MINIMAL -> "minimal";
                case LOW -> "low";
                case MEDIUM -> "medium";
                case HIGH -> "high";
                case XHIGH, MAX -> "xhigh";
                case OMITTED -> "medium";
            };
        }
        if (thinking.budgetTokens() != null) {
            int b = thinking.budgetTokens();
            if (b <= 0) {
                return "minimal";
            }
            if (b <= 1024) {
                return "minimal";
            }
            if (b <= 3072) {
                return "low";
            }
            if (b <= 10_240) {
                return "medium";
            }
            if (b <= 24_576) {
                return "high";
            }
            return "xhigh";
        }
        return "medium";
    }

    public static int toGeminiThinkingBudget(CanonicalThinkingConfig thinking) {
        if (thinking.mode() == CanonicalThinkingConfig.ThinkingMode.DISABLED
                || thinking.effort() == CanonicalThinkingConfig.ThinkingEffort.NONE) {
            return 0;
        }
        if (thinking.budgetTokens() != null) {
            return thinking.budgetTokens();
        }
        return switch (thinking.effort()) {
            case MINIMAL -> 1024;
            case LOW -> 2048;
            case MEDIUM, OMITTED -> 8192;
            case HIGH -> 16_384;
            case XHIGH, MAX -> 24_576;
            case NONE -> 0;
        };
    }

    public static boolean isAnthropicAdaptiveStyle(String thinkingStyle, String upstreamModel) {
        return "ANTHROPIC_ADAPTIVE".equalsIgnoreCase(thinkingStyle);
    }

    public static boolean isGeminiLevelStyle(String thinkingStyle, String upstreamModel) {
        return "GEMINI_LEVEL".equalsIgnoreCase(thinkingStyle);
    }

    public static Double asNullableDouble(JsonNode parent, String field) {
        return (parent.hasNonNull(field) && parent.get(field).isNumber()) ? parent.get(field).asDouble() : null;
    }

    public static Integer asNullableInt(JsonNode parent, String field) {
        return (parent.hasNonNull(field) && parent.get(field).isIntegralNumber()) ? parent.get(field).asInt() : null;
    }

    public static Long asNullableLong(JsonNode parent, String field) {
        return (parent.hasNonNull(field) && parent.get(field).isIntegralNumber()) ? parent.get(field).asLong() : null;
    }

    public static Boolean asNullableBoolean(JsonNode parent, String field) {
        return (parent.hasNonNull(field) && parent.get(field).isBoolean()) ? parent.get(field).asBoolean() : null;
    }

    public static String asNullableText(JsonNode parent, String field) {
        return (parent.hasNonNull(field) && parent.get(field).isTextual()) ? parent.get(field).asText() : null;
    }

    public static boolean isSameOrigin(ProtocolKind origin, ProtocolKind target) {
        return origin != null && origin == target;
    }

    // ---- Streaming JsonParser / JsonGenerator helpers for large messages arrays ----

    /**
     * Pre-sized {@link ByteArrayOutputStream} avoiding repeated buffer-doubling copies on 100K+ token
     * requests and exposing the backing array directly when the serialized size matches capacity.
     */
    static final class ResizableByteBuffer extends ByteArrayOutputStream {

        ResizableByteBuffer(int initialCapacity) {
            super(Math.max(512, initialCapacity));
        }

        byte[] toExactByteArray() {
            return count == buf.length ? buf : Arrays.copyOf(buf, count);
        }
    }

    static int estimateEncodedCapacity(CanonicalRequest req) {
        if (req == null) {
            return 1024;
        }
        long chars = 512L;
        for (CanonicalSystemPart sp : req.systemInstructions()) {
            chars += sp.text().length() + 48L;
        }
        for (CanonicalTurn turn : req.turns()) {
            chars += 48L;
            for (CanonicalContentPart part : turn.parts()) {
                chars += estimatePartChars(part);
            }
        }
        for (CanonicalToolDefinition tool : req.tools()) {
            chars += 256L;
            if (tool.name() != null) {
                chars += tool.name().length();
            }
            if (tool.description() != null) {
                chars += tool.description().length();
            }
        }
        long estimated = (chars * 5L) / 4L + 1024L;
        return (int) Math.min(16L * 1024L * 1024L, Math.max(1024L, estimated));
    }

    private static long estimatePartChars(CanonicalContentPart part) {
        return switch (part) {
            case CanonicalContentPart.TextPart t -> t.text().length() + 32L;
            case CanonicalContentPart.ThinkingPart th ->
                    th.text().length() + (th.signature() != null ? th.signature().length() : 0L) + 64L;
            case CanonicalContentPart.RedactedThinkingPart rt ->
                    (rt.opaqueData() != null ? rt.opaqueData().length() : 0L) + 64L;
            case CanonicalContentPart.RefusalPart r -> r.refusal().length() + 32L;
            case CanonicalContentPart.ImagePart img -> {
                if (img.source() instanceof ImageSource.Base64Image b64 && b64.base64Data() != null) {
                    yield b64.base64Data().length() + 128L;
                }
                yield 256L;
            }
            case CanonicalContentPart.ToolCallPart tc -> {
                long len = 128L;
                if (tc.rawArgumentsJson() != null) {
                    len += tc.rawArgumentsJson().length();
                }
                yield len;
            }
            case CanonicalContentPart.ToolResultPart tr -> {
                long len = 96L;
                for (CanonicalContentPart child : tr.content()) {
                    len += estimatePartChars(child);
                }
                yield len;
            }
        };
    }

    static JsonGenerator newUtf8Generator(ObjectMapper mapper, ResizableByteBuffer out) throws IOException {
        return mapper.getFactory().createGenerator(out, JsonEncoding.UTF8);
    }

    static JsonParser openObjectParser(ObjectMapper mapper, byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Request body must not be empty");
        }
        try {
            JsonParser parser = mapper.getFactory().createParser(rawBody);
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_OBJECT) {
                parser.close();
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Request body must be a JSON object");
            }
            return parser;
        } catch (IOException ex) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body", ex);
        }
    }

    static void writeCacheControl(JsonGenerator gen, CacheControl cc) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", cc.type() != null ? cc.type() : "ephemeral");
        if (cc.ttl() != null && !cc.ttl().isBlank()) {
            gen.writeStringField("ttl", cc.ttl());
        }
        gen.writeEndObject();
    }

    static void writeJsonNode(JsonGenerator gen, ObjectMapper mapper, JsonNode node) throws IOException {
        mapper.writeTree(gen, node);
    }

    static void writeEmptyObjectSchema(JsonGenerator gen) throws IOException {
        gen.writeRawValue("{\"type\":\"object\",\"properties\":{}}");
    }

    static void writeArgumentsAsObject(
            JsonGenerator gen,
            ObjectMapper mapper,
            CanonicalContentPart.ToolCallPart tc
    ) throws IOException {
        if (tc.argumentsJson() instanceof ObjectNode obj) {
            mapper.writeTree(gen, obj);
            return;
        }
        String rawArgs = tc.rawArgumentsJson();
        if (rawArgs == null || rawArgs.isBlank()) {
            gen.writeStartObject();
            gen.writeEndObject();
            return;
        }
        if (isValidJsonObject(mapper, rawArgs)) {
            gen.writeRawValue(rawArgs);
            return;
        }
        mapper.writeTree(gen, parseArgumentsToObject(mapper, rawArgs));
    }

    static void writeFunctionResponseObject(
            JsonGenerator gen,
            ObjectMapper mapper,
            String rawText,
            boolean isError
    ) throws IOException {
        if (!isError && isValidJsonObject(mapper, rawText)) {
            gen.writeRawValue(rawText);
            return;
        }
        gen.writeStartObject();
        gen.writeStringField(isError ? "error" : "output", rawText != null ? rawText : "");
        gen.writeEndObject();
    }

    static boolean isValidJsonObject(ObjectMapper mapper, String rawText) {
        if (!startsWithOpenBrace(rawText)) {
            return false;
        }
        try (JsonParser p = mapper.getFactory().createParser(rawText)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            p.skipChildren();
            return p.nextToken() == null;
        } catch (IOException ex) {
            return false;
        }
    }

    static boolean startsWithOpenBrace(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c > ' ') {
                return c == '{';
            }
        }
        return false;
    }

    static String readNullableString(JsonParser parser) throws IOException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        parser.skipChildren();
        return null;
    }

    static Integer readNullableIntegralInt(JsonParser parser) throws IOException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_NUMBER_INT) {
            return parser.getIntValue();
        }
        parser.skipChildren();
        return null;
    }

    static Long readNullableIntegralLong(JsonParser parser) throws IOException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_NUMBER_INT) {
            return parser.getLongValue();
        }
        parser.skipChildren();
        return null;
    }

    static Double readNullableDouble(JsonParser parser) throws IOException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDoubleValue();
        }
        parser.skipChildren();
        return null;
    }

    static Boolean readNullableBoolean(JsonParser parser) throws IOException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (t == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        parser.skipChildren();
        return null;
    }
}
