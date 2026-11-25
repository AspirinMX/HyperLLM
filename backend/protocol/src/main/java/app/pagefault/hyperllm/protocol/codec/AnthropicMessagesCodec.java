package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.BlockKind;
import app.pagefault.hyperllm.protocol.ir.CacheControl;
import app.pagefault.hyperllm.protocol.ir.CanonicalCacheConfig;
import app.pagefault.hyperllm.protocol.ir.CanonicalContentPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalGenerationConfig;
import app.pagefault.hyperllm.protocol.ir.CanonicalProtocolOptions;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalResponse;
import app.pagefault.hyperllm.protocol.ir.CanonicalResponseFormat;
import app.pagefault.hyperllm.protocol.ir.CanonicalRole;
import app.pagefault.hyperllm.protocol.ir.CanonicalStopReason;
import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;
import app.pagefault.hyperllm.protocol.ir.CanonicalSystemPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalThinkingConfig;
import app.pagefault.hyperllm.protocol.ir.CanonicalToolChoice;
import app.pagefault.hyperllm.protocol.ir.CanonicalToolDefinition;
import app.pagefault.hyperllm.protocol.ir.CanonicalTurn;
import app.pagefault.hyperllm.protocol.ir.ImageDetail;
import app.pagefault.hyperllm.protocol.ir.ImageSource;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UnsupportedFeatureFlag;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bidirectional codec for Anthropic Messages API ({@code POST /v1/messages}).
 */
public final class AnthropicMessagesCodec implements ProtocolCodec {

    /** Constant envelope of a {@code content_block_delta} frame, up to the block index. */
    private static final byte[] DELTA_EVENT_PREFIX =
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DELTA_EVENT_DELTA_KEY = ",\"delta\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DELTA_EVENT_TAIL = "}\n\n".getBytes(StandardCharsets.US_ASCII);

    private static final int DEFAULT_MAX_TOKENS = 8192;

    private final ObjectMapper mapper;

    public AnthropicMessagesCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProtocolKind kind() {
        return ProtocolKind.ANTHROPIC_MESSAGES;
    }

    @Override
    public CanonicalRequest decodeRequest(byte[] rawBody, InboundRequestContext context) {
        try (JsonParser parser = CodecSupport.openObjectParser(mapper, rawBody)) {
            return decodeStreamingRoot(parser, context);
        } catch (HyperLlmException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body", ex);
        }
    }

    @Override
    public byte[] encodeRequest(CanonicalRequest req, UpstreamEncodingContext context) {
        CodecSupport.ResizableByteBuffer out = new CodecSupport.ResizableByteBuffer(
                CodecSupport.estimateEncodedCapacity(req));
        try (JsonGenerator gen = CodecSupport.newUtf8Generator(mapper, out)) {
            gen.writeStartObject();
            String upstreamModel = context.upstreamModelName() != null && !context.upstreamModelName().isBlank()
                    ? context.upstreamModelName() : req.model();
            gen.writeStringField("model", upstreamModel);
            gen.writeBooleanField("stream", req.stream());

            int maxTokens = req.generation().maxOutputTokens() != null && req.generation().maxOutputTokens() > 0
                    ? req.generation().maxOutputTokens()
                    : context.resolveDefaultMaxOutputTokens(DEFAULT_MAX_TOKENS);

            boolean thinkingEnabled = req.thinking().isEnabled();
            String adaptiveEffort = null;

            if (req.thinking().mode() == CanonicalThinkingConfig.ThinkingMode.DISABLED
                    || req.thinking().effort() == CanonicalThinkingConfig.ThinkingEffort.NONE) {
                gen.writeObjectFieldStart("thinking");
                gen.writeStringField("type", "disabled");
                gen.writeEndObject();
                thinkingEnabled = false;
            } else if (thinkingEnabled) {
                boolean adaptive = req.thinking().mode() == CanonicalThinkingConfig.ThinkingMode.ADAPTIVE
                        || CodecSupport.isAnthropicAdaptiveStyle(context.thinkingStyle(), upstreamModel);
                gen.writeObjectFieldStart("thinking");
                if (adaptive) {
                    gen.writeStringField("type", "adaptive");
                    adaptiveEffort = CodecSupport.toAnthropicAdaptiveEffort(req.thinking());
                } else {
                    int budget = Math.max(1024, CodecSupport.toAnthropicBudgetTokens(req.thinking()));
                    if (maxTokens <= budget) {
                        maxTokens = budget + 4096;
                    }
                    gen.writeStringField("type", "enabled");
                    gen.writeNumberField("budget_tokens", budget);
                }
                gen.writeEndObject();
            }
            gen.writeNumberField("max_tokens", maxTokens);

            // System instructions (+ optional BEST_EFFORT json_object hint)
            boolean addJsonHint = req.responseFormat() instanceof CanonicalResponseFormat.JsonObjectFormat;
            if (addJsonHint && context.policy() == TranslationPolicy.STRICT) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "Schemaless json_object format is not supported natively by Anthropic in STRICT mode");
            }
            boolean hasSystem = addJsonHint;
            if (!hasSystem) {
                for (CanonicalSystemPart sp : req.systemInstructions()) {
                    if (!sp.text().isEmpty()) {
                        hasSystem = true;
                        break;
                    }
                }
            }
            if (hasSystem) {
                gen.writeArrayFieldStart("system");
                for (CanonicalSystemPart sp : req.systemInstructions()) {
                    if (!sp.text().isEmpty()) {
                        writeTextBlock(gen, sp.text(), sp.cacheControl());
                    }
                }
                if (addJsonHint) {
                    writeTextBlock(gen, "Respond with a valid JSON object only. Do not wrap in markdown code fences.", null);
                }
                gen.writeEndArray();
            }

            // Messages with strict alternation, first-user guarantee & 2-pass tool_result ordering
            writeAlternatingMessages(gen, req.turns(), context.policy());

            // Sampling parameters (respecting Anthropic's thinking constraints)
            CanonicalGenerationConfig g = req.generation();
            if (!thinkingEnabled) {
                if (g.temperature() != null) {
                    gen.writeNumberField("temperature", Math.max(0.0, Math.min(1.0, g.temperature())));
                }
                if (g.topP() != null) {
                    gen.writeNumberField("top_p", g.topP());
                }
                if (g.topK() != null) {
                    gen.writeNumberField("top_k", g.topK());
                }
            }
            if (!g.stopSequences().isEmpty()) {
                gen.writeArrayFieldStart("stop_sequences");
                for (String s : g.stopSequences()) {
                    gen.writeString(s);
                }
                gen.writeEndArray();
            }

            writeTools(gen, req, thinkingEnabled, context.policy());

            CanonicalResponseFormat.JsonSchemaFormat jsFormat =
                    (req.responseFormat() instanceof CanonicalResponseFormat.JsonSchemaFormat js && js.schema() != null)
                            ? js : null;
            if (adaptiveEffort != null || jsFormat != null) {
                gen.writeObjectFieldStart("output_config");
                if (adaptiveEffort != null) {
                    gen.writeStringField("effort", adaptiveEffort);
                }
                if (jsFormat != null) {
                    gen.writeObjectFieldStart("format");
                    gen.writeStringField("type", "json_schema");
                    gen.writeFieldName("schema");
                    CodecSupport.writeJsonNode(gen, mapper, jsFormat.schema());
                    gen.writeEndObject();
                }
                gen.writeEndObject();
            }

            if (req.cache().topLevelCacheControl() != null) {
                gen.writeFieldName("cache_control");
                CodecSupport.writeCacheControl(gen, req.cache().topLevelCacheControl());
            }
            if (req.options().user() != null && !req.options().user().isBlank()) {
                gen.writeObjectFieldStart("metadata");
                gen.writeStringField("user_id", req.options().user());
                gen.writeEndObject();
            }
            if (req.options().serviceTier() != null && !req.options().serviceTier().isBlank()) {
                gen.writeStringField("service_tier", req.options().serviceTier());
            }
            gen.writeEndObject();
        } catch (HyperLlmException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize Anthropic Messages request JSON", ex);
        }
        return out.toExactByteArray();
    }

    // ---- Streaming request decoding (zero inbound DOM tree for large messages[]) ----

    private CanonicalRequest decodeStreamingRoot(JsonParser parser, InboundRequestContext context) throws IOException {
        String model = null;
        Integer parsedMaxTokens = null;
        boolean stream = false;
        boolean hasMessagesArray = false;

        List<CanonicalSystemPart> systemParts = new ArrayList<>();
        List<CanonicalSystemPart> leadingMsgSystemParts = new ArrayList<>();
        List<CanonicalTurn> turns = new ArrayList<>();
        List<UnsupportedFeatureFlag> unsupported = new ArrayList<>();

        JsonNode toolsNode = null;
        JsonNode toolChoiceNode = null;
        JsonNode outputConfigNode = null;
        JsonNode thinkingNode = null;
        List<String> stops = new ArrayList<>();
        Double temperature = null;
        Double topP = null;
        Integer topK = null;
        JsonNode cacheControlNode = null;
        String userId = null;
        String serviceTier = null;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken valTok = parser.nextToken();
            if (valTok == null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body");
            }
            switch (field) {
                case "model" -> model = CodecSupport.readNullableString(parser);
                case "max_tokens" -> {
                    if (valTok != JsonToken.VALUE_NULL) {
                        if (valTok != JsonToken.VALUE_NUMBER_INT) {
                            parser.skipChildren();
                            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                                    "Field 'max_tokens' must be a positive integer");
                        }
                        int v = parser.getIntValue();
                        if (v <= 0) {
                            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                                    "Field 'max_tokens' must be a positive integer");
                        }
                        parsedMaxTokens = v;
                    }
                }
                case "stream" -> {
                    if (valTok == JsonToken.VALUE_TRUE) {
                        stream = true;
                    } else if (valTok == JsonToken.VALUE_FALSE) {
                        stream = false;
                    } else {
                        parser.skipChildren();
                    }
                }
                case "system" -> {
                    JsonNode sysNode = mapper.readTree(parser);
                    systemParts.addAll(decodeSystem(sysNode, false));
                }
                case "messages" -> {
                    if (valTok == JsonToken.START_ARRAY) {
                        hasMessagesArray = true;
                        readStreamingMessages(parser, leadingMsgSystemParts, turns, unsupported);
                    } else {
                        parser.skipChildren();
                    }
                }
                case "tools" -> toolsNode = mapper.readTree(parser);
                case "tool_choice" -> toolChoiceNode = mapper.readTree(parser);
                case "output_config" -> outputConfigNode = mapper.readTree(parser);
                case "thinking" -> thinkingNode = mapper.readTree(parser);
                case "stop_sequences" -> {
                    if (valTok == JsonToken.START_ARRAY) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                                stops.add(parser.getText());
                            } else {
                                parser.skipChildren();
                            }
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
                case "temperature" -> temperature = CodecSupport.readNullableDouble(parser);
                case "top_p" -> topP = CodecSupport.readNullableDouble(parser);
                case "top_k" -> topK = CodecSupport.readNullableIntegralInt(parser);
                case "cache_control" -> cacheControlNode = mapper.readTree(parser);
                case "metadata" -> {
                    if (valTok == JsonToken.START_OBJECT) {
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String mf = parser.currentName();
                            parser.nextToken();
                            if ("user_id".equals(mf)) {
                                userId = CodecSupport.readNullableString(parser);
                            } else {
                                parser.skipChildren();
                            }
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
                case "service_tier" -> serviceTier = CodecSupport.readNullableString(parser);
                default -> parser.skipChildren();
            }
        }

        if (model == null || model.isBlank()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'model' is required and must be non-blank");
        }
        if (!hasMessagesArray || (leadingMsgSystemParts.isEmpty() && turns.isEmpty())) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'messages' must be a non-empty array");
        }
        if (!leadingMsgSystemParts.isEmpty()) {
            systemParts.addAll(leadingMsgSystemParts);
        }

        List<CanonicalToolDefinition> tools = decodeTools(toolsNode, unsupported);
        CanonicalToolChoice toolChoice = decodeToolChoice(toolChoiceNode);
        CanonicalResponseFormat responseFormat = outputConfigNode != null
                ? decodeOutputFormat(outputConfigNode.get("format")) : null;
        CanonicalThinkingConfig thinking = decodeThinking(thinkingNode, outputConfigNode);

        CanonicalGenerationConfig generation = new CanonicalGenerationConfig(
                parsedMaxTokens, temperature, topP, topK, stops, null, null, null, null);
        CanonicalCacheConfig cache = new CanonicalCacheConfig(
                CodecSupport.parseCacheControl(cacheControlNode), null, null, null);
        CanonicalProtocolOptions options = new CanonicalProtocolOptions(
                true, true,
                context != null ? context.anthropicVersion() : null,
                context != null ? context.anthropicBeta() : null,
                userId, serviceTier, null
        );

        return new CanonicalRequest(
                ProtocolKind.ANTHROPIC_MESSAGES, model.trim(), stream, systemParts, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, unsupported
        );
    }

    private void readStreamingMessages(
            JsonParser parser,
            List<CanonicalSystemPart> leadingSystemParts,
            List<CanonicalTurn> turns,
            List<UnsupportedFeatureFlag> unsupported
    ) throws IOException {
        boolean sawNonSystem = false;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String roleStr = "user";
            String plainText = null;
            List<CanonicalContentPart> blocks = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken t = parser.nextToken();
                switch (f) {
                    case "role" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            roleStr = parser.getText().toLowerCase(Locale.ROOT);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "content" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            plainText = parser.getText();
                        } else if (t == JsonToken.START_ARRAY) {
                            blocks = readStreamingContentBlocks(parser, unsupported);
                        } else if (t == JsonToken.VALUE_NULL) {
                            // null content
                        } else {
                            JsonNode fallback = mapper.readTree(parser);
                            plainText = fallback.toString();
                        }
                    }
                    default -> parser.skipChildren();
                }
            }

            switch (roleStr) {
                case "system", "developer" -> {
                    boolean devPriority = "developer".equals(roleStr);
                    if (!sawNonSystem) {
                        if (plainText != null) {
                            leadingSystemParts.add(new CanonicalSystemPart(plainText, devPriority, null));
                        } else if (blocks != null) {
                            for (CanonicalContentPart p : blocks) {
                                if (p instanceof CanonicalContentPart.TextPart tp) {
                                    leadingSystemParts.add(new CanonicalSystemPart(tp.text(), devPriority, tp.cacheControl()));
                                }
                            }
                        }
                    } else {
                        List<CanonicalContentPart> parts = blocks != null ? blocks
                                : (plainText != null ? List.of(new CanonicalContentPart.TextPart(plainText)) : List.of());
                        turns.add(new CanonicalTurn(devPriority ? CanonicalRole.DEVELOPER : CanonicalRole.SYSTEM, parts));
                    }
                }
                case "user" -> {
                    sawNonSystem = true;
                    List<CanonicalContentPart> parts = blocks != null ? blocks
                            : (plainText != null ? List.of(new CanonicalContentPart.TextPart(plainText)) : List.of());
                    turns.add(new CanonicalTurn(CanonicalRole.USER, parts));
                }
                case "assistant" -> {
                    sawNonSystem = true;
                    List<CanonicalContentPart> parts = blocks != null ? blocks
                            : (plainText != null ? List.of(new CanonicalContentPart.TextPart(plainText)) : List.of());
                    turns.add(new CanonicalTurn(CanonicalRole.ASSISTANT, parts));
                }
                default -> throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                        "Unsupported Anthropic role: " + roleStr);
            }
        }
    }

    private List<CanonicalContentPart> readStreamingContentBlocks(
            JsonParser parser,
            List<UnsupportedFeatureFlag> unsupported
    ) throws IOException {
        List<CanonicalContentPart> out = new ArrayList<>(4);
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonToken t = parser.currentToken();
            if (t == JsonToken.VALUE_STRING) {
                out.add(new CanonicalContentPart.TextPart(parser.getText()));
                continue;
            }
            if (t != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String type = "";
            String text = "";
            String thinking = "";
            String signature = null;
            String data = "";
            String id = null;
            String name = "";
            String toolUseId = null;
            boolean isError = false;
            CacheControl cc = null;
            JsonNode inputNode = null;
            String nestedPlainText = null;
            List<CanonicalContentPart> nestedBlocks = null;

            // Image source fields (streamed without building an ObjectNode!)
            String imgSrcType = "";
            String imgMediaType = "image/png";
            String imgData = "";
            String imgUrl = "";
            String imgFileId = "";

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken bt = parser.nextToken();
                switch (f) {
                    case "type" -> {
                        if (bt == JsonToken.VALUE_STRING) {
                            type = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "text" -> {
                        if (bt == JsonToken.VALUE_STRING) {
                            text = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "thinking" -> {
                        if (bt == JsonToken.VALUE_STRING) {
                            thinking = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "signature" -> signature = CodecSupport.readNullableString(parser);
                    case "data" -> {
                        if (bt == JsonToken.VALUE_STRING) {
                            data = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "id" -> id = CodecSupport.readNullableString(parser);
                    case "name" -> {
                        if (bt == JsonToken.VALUE_STRING) {
                            name = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "input" -> inputNode = mapper.readTree(parser);
                    case "tool_use_id" -> toolUseId = CodecSupport.readNullableString(parser);
                    case "is_error" -> {
                        if (bt == JsonToken.VALUE_TRUE) {
                            isError = true;
                        } else if (bt == JsonToken.VALUE_FALSE) {
                            isError = false;
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "content" -> {
                        if (bt == JsonToken.VALUE_STRING) {
                            nestedPlainText = parser.getText();
                        } else if (bt == JsonToken.START_ARRAY) {
                            nestedBlocks = readStreamingContentBlocks(parser, unsupported);
                        } else if (bt != JsonToken.VALUE_NULL) {
                            nestedPlainText = mapper.readTree(parser).toString();
                        }
                    }
                    case "cache_control" -> cc = readStreamingCacheControl(parser);
                    case "source" -> {
                        if (bt == JsonToken.START_OBJECT) {
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String sf = parser.currentName();
                                JsonToken st = parser.nextToken();
                                switch (sf) {
                                    case "type" -> {
                                        if (st == JsonToken.VALUE_STRING) {
                                            imgSrcType = parser.getText();
                                        } else {
                                            parser.skipChildren();
                                        }
                                    }
                                    case "media_type" -> {
                                        if (st == JsonToken.VALUE_STRING) {
                                            imgMediaType = parser.getText();
                                        } else {
                                            parser.skipChildren();
                                        }
                                    }
                                    case "data" -> {
                                        if (st == JsonToken.VALUE_STRING) {
                                            imgData = parser.getText();
                                        } else {
                                            parser.skipChildren();
                                        }
                                    }
                                    case "url" -> {
                                        if (st == JsonToken.VALUE_STRING) {
                                            imgUrl = parser.getText();
                                        } else {
                                            parser.skipChildren();
                                        }
                                    }
                                    case "file_id" -> {
                                        if (st == JsonToken.VALUE_STRING) {
                                            imgFileId = parser.getText();
                                        } else {
                                            parser.skipChildren();
                                        }
                                    }
                                    default -> parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    default -> parser.skipChildren();
                }
            }

            switch (type) {
                case "text" -> out.add(new CanonicalContentPart.TextPart(text, cc));
                case "image" -> {
                    CanonicalContentPart imgPart = switch (imgSrcType) {
                        case "base64" -> new CanonicalContentPart.ImagePart(
                                new ImageSource.Base64Image(imgMediaType, imgData), ImageDetail.AUTO, cc);
                        case "url" -> new CanonicalContentPart.ImagePart(
                                new ImageSource.UrlImage(imgUrl, CodecSupport.guessMimeFromUrl(imgUrl)), ImageDetail.AUTO, cc);
                        case "file" -> new CanonicalContentPart.ImagePart(
                                new ImageSource.FileRefImage(ProtocolKind.ANTHROPIC_MESSAGES, imgFileId, null), ImageDetail.AUTO, cc);
                        default -> {
                            unsupported.add(new UnsupportedFeatureFlag("image_source:" + imgSrcType,
                                    "Unsupported Anthropic image source type: " + imgSrcType));
                            yield new CanonicalContentPart.TextPart("");
                        }
                    };
                    out.add(imgPart);
                }
                case "thinking" -> out.add(new CanonicalContentPart.ThinkingPart(
                        thinking, signature, null, ProtocolKind.ANTHROPIC_MESSAGES));
                case "redacted_thinking" -> out.add(new CanonicalContentPart.RedactedThinkingPart(
                        data, ProtocolKind.ANTHROPIC_MESSAGES));
                case "tool_use" -> {
                    ObjectNode inputObj = (inputNode instanceof ObjectNode o) ? o : mapper.createObjectNode();
                    // Defer rawArgumentsJson stringification until needed by OpenAI encoders
                    out.add(new CanonicalContentPart.ToolCallPart(
                            id, null, name, inputObj, null, null, ProtocolKind.ANTHROPIC_MESSAGES));
                }
                case "tool_result" -> {
                    List<CanonicalContentPart> nested = nestedBlocks != null ? nestedBlocks
                            : (nestedPlainText != null ? List.of(new CanonicalContentPart.TextPart(nestedPlainText)) : List.of());
                    out.add(new CanonicalContentPart.ToolResultPart(toolUseId, null, nested, isError, cc));
                }
                default -> unsupported.add(UnsupportedFeatureFlag.degradable("content_block:" + type,
                        "Anthropic content block '" + type + "' is not supported across protocol translation"));
            }
        }
        return out;
    }

    private CacheControl readStreamingCacheControl(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return null;
        }
        String type = "ephemeral";
        String ttl = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String f = parser.currentName();
            JsonToken t = parser.nextToken();
            if ("type".equals(f) && t == JsonToken.VALUE_STRING) {
                type = parser.getText();
            } else if ("ttl".equals(f)) {
                ttl = CodecSupport.readNullableString(parser);
            } else {
                parser.skipChildren();
            }
        }
        return new CacheControl(type, ttl);
    }

    @Override
    public CanonicalResponse decodeResponse(byte[] responseBody, String virtualModel) {
        ObjectNode root = CodecSupport.parseResponseObjectRoot(mapper, responseBody);
        String id = root.path("id").asText("");
        if (id.isBlank()) {
            id = CodecSupport.generateId("msg_");
        }
        String model = (virtualModel != null && !virtualModel.isBlank())
                ? virtualModel : root.path("model").asText("");

        List<UnsupportedFeatureFlag> ignoredFlags = new ArrayList<>();
        List<CanonicalContentPart> parts = decodeContentBlocks(root.get("content"), ignoredFlags);
        CanonicalStopReason stopReason = mapStopReason(CodecSupport.asNullableText(root, "stop_reason"), parts);
        String stopSeq = CodecSupport.asNullableText(root, "stop_sequence");
        TokenUsage usage = parseUsageObject(root.get("usage")).orElse(TokenUsage.empty());

        return new CanonicalResponse(
                id, model, CodecSupport.nowEpochSeconds(), parts, stopReason, stopSeq, usage, ProtocolKind.ANTHROPIC_MESSAGES);
    }

    @Override
    public byte[] encodeResponse(CanonicalResponse response) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", response.id() != null && !response.id().isBlank() ? response.id() : CodecSupport.generateId("msg_"));
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", response.virtualModel());

        ArrayNode contentArr = mapper.createArrayNode();
        for (CanonicalContentPart part : response.content()) {
            switch (part) {
                case CanonicalContentPart.ThinkingPart th -> {
                    ObjectNode block = mapper.createObjectNode();
                    block.put("type", "thinking");
                    block.put("thinking", th.text());
                    if (CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                            && th.signature() != null) {
                        block.put("signature", th.signature());
                    }
                    contentArr.add(block);
                }
                case CanonicalContentPart.RedactedThinkingPart rt -> {
                    if (CodecSupport.isSameOrigin(rt.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                            && rt.opaqueData() != null) {
                        ObjectNode block = mapper.createObjectNode();
                        block.put("type", "redacted_thinking");
                        block.put("data", rt.opaqueData());
                        contentArr.add(block);
                    }
                }
                case CanonicalContentPart.TextPart t -> {
                    if (!t.text().isEmpty()) {
                        ObjectNode block = mapper.createObjectNode();
                        block.put("type", "text");
                        block.put("text", t.text());
                        contentArr.add(block);
                    }
                }
                case CanonicalContentPart.RefusalPart r -> {
                    ObjectNode block = mapper.createObjectNode();
                    block.put("type", "text");
                    block.put("text", r.refusal());
                    contentArr.add(block);
                }
                case CanonicalContentPart.ToolCallPart tc -> {
                    ObjectNode block = mapper.createObjectNode();
                    block.put("type", "tool_use");
                    block.put("id", tc.callId() != null && !tc.callId().isBlank() ? tc.callId() : CodecSupport.generateId("toolu_"));
                    block.put("name", tc.name());
                    block.set("input", CodecSupport.argumentsAsObject(mapper, tc));
                    contentArr.add(block);
                }
                case CanonicalContentPart.ImagePart ignored -> {}
                case CanonicalContentPart.ToolResultPart ignored -> {}
            }
        }
        root.set("content", contentArr);
        root.put("stop_reason", encodeStopReason(response.stopReason(), response.hasToolCalls()));
        if (response.stopSequence() != null) {
            root.put("stop_sequence", response.stopSequence());
        } else {
            root.set("stop_sequence", NullNode.getInstance());
        }
        root.set("usage", encodeUsageObject(response.usage()));
        return CodecSupport.writeJsonBytes(mapper, root);
    }

    @Override
    public Optional<TokenUsage> extractNonStreamingUsage(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            return parseUsageObject(root.get("usage"));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public UpstreamStreamDecoder newStreamDecoder(String virtualModel) {
        return new AnthropicStreamDecoder(virtualModel);
    }

    @Override
    public DownstreamStreamEncoder newStreamEncoder(StreamRequestMetadata inboundMeta) {
        return new AnthropicStreamEncoder(inboundMeta);
    }

    // ---- Decoding helpers ----

    

    private List<CanonicalSystemPart> decodeSystem(JsonNode systemNode, boolean developerPriority) {
        if (systemNode == null || systemNode.isNull()) {
            return List.of();
        }
        if (systemNode.isTextual()) {
            return List.of(new CanonicalSystemPart(systemNode.asText(), developerPriority, null));
        }
        if (systemNode.isArray()) {
            List<CanonicalSystemPart> out = new ArrayList<>(systemNode.size());
            for (JsonNode block : systemNode) {
                if (block.isObject() && block.hasNonNull("text")) {
                    out.add(new CanonicalSystemPart(
                            block.get("text").asText(),
                            developerPriority,
                            CodecSupport.parseCacheControl(block.get("cache_control"))
                    ));
                } else if (block.isTextual()) {
                    out.add(new CanonicalSystemPart(block.asText(), developerPriority, null));
                }
            }
            return out;
        }
        return List.of();
    }

    private List<CanonicalContentPart> decodeContentBlocks(JsonNode contentNode, List<UnsupportedFeatureFlag> unsupported) {
        if (contentNode == null || contentNode.isNull()) {
            return List.of();
        }
        if (contentNode.isTextual()) {
            return List.of(new CanonicalContentPart.TextPart(contentNode.asText()));
        }
        if (!contentNode.isArray()) {
            return List.of(new CanonicalContentPart.TextPart(contentNode.toString()));
        }
        List<CanonicalContentPart> out = new ArrayList<>(contentNode.size());
        for (JsonNode block : contentNode) {
            if (!block.isObject()) {
                continue;
            }
            String type = block.path("type").asText("");
            CacheControl cc = CodecSupport.parseCacheControl(block.get("cache_control"));
            switch (type) {
                case "text" -> out.add(new CanonicalContentPart.TextPart(block.path("text").asText(""), cc));
                case "image" -> out.add(decodeImageBlock(block, cc, unsupported));
                case "thinking" -> out.add(new CanonicalContentPart.ThinkingPart(
                        block.path("thinking").asText(""),
                        CodecSupport.asNullableText(block, "signature"),
                        null,
                        ProtocolKind.ANTHROPIC_MESSAGES
                ));
                case "redacted_thinking" -> out.add(new CanonicalContentPart.RedactedThinkingPart(
                        block.path("data").asText(""), ProtocolKind.ANTHROPIC_MESSAGES));
                case "tool_use" -> {
                    String id = CodecSupport.asNullableText(block, "id");
                    String name = block.path("name").asText("");
                    JsonNode input = block.get("input");
                    ObjectNode inputObj = (input instanceof ObjectNode o) ? o : mapper.createObjectNode();
                    out.add(new CanonicalContentPart.ToolCallPart(
                            id, null, name, inputObj, CodecSupport.writeJsonString(mapper, inputObj),
                            null, ProtocolKind.ANTHROPIC_MESSAGES
                    ));
                }
                case "tool_result" -> {
                    String toolUseId = CodecSupport.asNullableText(block, "tool_use_id");
                    boolean isError = block.path("is_error").asBoolean(false);
                    List<CanonicalContentPart> nested = decodeContentBlocks(block.get("content"), unsupported);
                    out.add(new CanonicalContentPart.ToolResultPart(toolUseId, null, nested, isError, cc));
                }
                default -> unsupported.add(UnsupportedFeatureFlag.degradable("content_block:" + type,
                        "Anthropic content block '" + type + "' is not supported across protocol translation"));
            }
        }
        return out;
    }

    private CanonicalContentPart decodeImageBlock(
            JsonNode block, CacheControl cc, List<UnsupportedFeatureFlag> unsupported) {
        JsonNode source = block.path("source");
        String srcType = source.path("type").asText("");
        return switch (srcType) {
            case "base64" -> new CanonicalContentPart.ImagePart(
                    new ImageSource.Base64Image(
                            source.path("media_type").asText("image/png"),
                            source.path("data").asText("")
                    ),
                    ImageDetail.AUTO,
                    cc
            );
            case "url" -> {
                String url = source.path("url").asText("");
                yield new CanonicalContentPart.ImagePart(
                        new ImageSource.UrlImage(url, CodecSupport.guessMimeFromUrl(url)),
                        ImageDetail.AUTO,
                        cc
                );
            }
            case "file" -> new CanonicalContentPart.ImagePart(
                    new ImageSource.FileRefImage(
                            ProtocolKind.ANTHROPIC_MESSAGES, source.path("file_id").asText(""), null),
                    ImageDetail.AUTO,
                    cc
            );
            default -> {
                unsupported.add(new UnsupportedFeatureFlag("image_source:" + srcType,
                        "Unsupported Anthropic image source type: " + srcType));
                yield new CanonicalContentPart.TextPart("");
            }
        };
    }

    private List<CanonicalToolDefinition> decodeTools(JsonNode toolsNode, List<UnsupportedFeatureFlag> unsupported) {
        if (toolsNode == null || !toolsNode.isArray()) {
            return List.of();
        }
        List<CanonicalToolDefinition> out = new ArrayList<>(toolsNode.size());
        for (JsonNode tool : toolsNode) {
            String type = CodecSupport.asNullableText(tool, "type");
            if (type != null && !type.isBlank() && !"custom".equals(type)) {
                unsupported.add(UnsupportedFeatureFlag.degradable("builtin_tool:" + type,
                        "Anthropic built-in/server tool '" + type + "' cannot be translated across vendors"));
                continue;
            }
            out.add(new CanonicalToolDefinition(
                    tool.path("name").asText(""),
                    CodecSupport.asNullableText(tool, "description"),
                    tool.get("input_schema"),
                    CodecSupport.asNullableBoolean(tool, "strict"),
                    CodecSupport.parseCacheControl(tool.get("cache_control"))
            ));
        }
        return out;
    }

    private CanonicalToolChoice decodeToolChoice(JsonNode node) {
        if (node == null || !node.isObject()) {
            return CanonicalToolChoice.omitted();
        }
        Boolean disableParallel = CodecSupport.asNullableBoolean(node, "disable_parallel_tool_use");
        Boolean parallel = disableParallel != null ? !disableParallel : null;
        String type = node.path("type").asText("auto");
        return switch (type) {
            case "none" -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.NONE, null, parallel);
            case "any" -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.REQUIRED, null, parallel);
            case "tool" -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.SPECIFIC,
                    CodecSupport.asNullableText(node, "name"), parallel);
            default -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.AUTO, null, parallel);
        };
    }

    private CanonicalResponseFormat decodeOutputFormat(JsonNode formatNode) {
        if (formatNode == null || !formatNode.isObject()) {
            return null;
        }
        if ("json_schema".equals(formatNode.path("type").asText())) {
            return new CanonicalResponseFormat.JsonSchemaFormat("response", null, formatNode.get("schema"), Boolean.TRUE);
        }
        return null;
    }

    private CanonicalThinkingConfig decodeThinking(JsonNode thinkingNode, JsonNode outputConfigNode) {
        var effort = (outputConfigNode != null && outputConfigNode.isObject())
                ? CanonicalThinkingConfig.ThinkingEffort.parseOrOmitted(CodecSupport.asNullableText(outputConfigNode, "effort"))
                : CanonicalThinkingConfig.ThinkingEffort.OMITTED;
        if (thinkingNode == null || !thinkingNode.isObject()) {
            if (effort != CanonicalThinkingConfig.ThinkingEffort.OMITTED) {
                return new CanonicalThinkingConfig(CanonicalThinkingConfig.ThinkingMode.ADAPTIVE, effort, null, Boolean.TRUE, null);
            }
            return CanonicalThinkingConfig.omitted();
        }
        String type = thinkingNode.path("type").asText("");
        return switch (type) {
            case "enabled" -> new CanonicalThinkingConfig(
                    CanonicalThinkingConfig.ThinkingMode.ENABLED,
                    effort,
                    CodecSupport.asNullableInt(thinkingNode, "budget_tokens"),
                    Boolean.TRUE,
                    null
            );
            case "adaptive" -> new CanonicalThinkingConfig(
                    CanonicalThinkingConfig.ThinkingMode.ADAPTIVE, effort, null, Boolean.TRUE, null);
            case "disabled" -> new CanonicalThinkingConfig(
                    CanonicalThinkingConfig.ThinkingMode.DISABLED, CanonicalThinkingConfig.ThinkingEffort.NONE, null, Boolean.FALSE, null);
            default -> CanonicalThinkingConfig.omitted();
        };
    }

    // ---- Streaming encoding helpers (zero outbound DOM tree, zero-sort 2-pass turn grouping) ----

    /**
     * Maps a canonical turn back onto the Anthropic wire role. Mid-conversation {@code system} /
     * {@code developer} turns are native to Anthropic (cache-preserving developer overrides), so they must
     * round-trip as {@code role: "system"} instead of being flattened into {@code "user"}.
     */
    private static String wireRole(CanonicalTurn turn) {
        return switch (turn.role()) {
            case ASSISTANT -> "assistant";
            case SYSTEM, DEVELOPER -> "system";
            default -> "user";
        };
    }

    private void writeAlternatingMessages(
            JsonGenerator gen,
            List<CanonicalTurn> turns,
            TranslationPolicy policy
    ) throws IOException {
        int n = turns.size();
        int firstNonEmpty = -1;
        for (int i = 0; i < n; i++) {
            CanonicalTurn turn = turns.get(i);
            String role = wireRole(turn);
            if (hasEmittableBlock(turn, role)) {
                firstNonEmpty = i;
                break;
            }
        }
        if (firstNonEmpty < 0) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Translated Anthropic messages cannot be empty");
        }

        String firstRole = wireRole(turns.get(firstNonEmpty));
        if (!"user".equals(firstRole) && policy == TranslationPolicy.STRICT) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                    "Anthropic Messages requires the first message to have role 'user'");
        }

        gen.writeArrayFieldStart("messages");
        if (!"user".equals(firstRole)) {
            gen.writeStartObject();
            gen.writeStringField("role", "user");
            gen.writeArrayFieldStart("content");
            writeTextBlock(gen, "Continue.", null);
            gen.writeEndArray();
            gen.writeEndObject();
        }

        int i = firstNonEmpty;
        while (i < n) {
            CanonicalTurn startTurn = turns.get(i);
            String groupRole = wireRole(startTurn);
            if (!hasEmittableBlock(startTurn, groupRole)) {
                i++;
                continue;
            }
            int groupEnd = i + 1;
            while (groupEnd < n) {
                CanonicalTurn nextTurn = turns.get(groupEnd);
                String nextRole = wireRole(nextTurn);
                if (!hasEmittableBlock(nextTurn, nextRole)) {
                    groupEnd++;
                    continue;
                }
                if (!groupRole.equals(nextRole)) {
                    break;
                }
                groupEnd++;
            }

            gen.writeStartObject();
            gen.writeStringField("role", groupRole);
            gen.writeArrayFieldStart("content");
            if ("assistant".equals(groupRole)) {
                for (int k = i; k < groupEnd; k++) {
                    writeAssistantBlocks(gen, turns.get(k));
                }
            } else {
                // Anthropic requires tool_result blocks to precede text/image blocks in a user message.
                // 2-pass direct streaming eliminates intermediate ObjectNode lists and Comparator sorting!
                for (int k = i; k < groupEnd; k++) {
                    for (CanonicalContentPart p : turns.get(k).parts()) {
                        if (p instanceof CanonicalContentPart.ToolResultPart tr) {
                            writeToolResultBlock(gen, tr);
                        }
                    }
                }
                for (int k = i; k < groupEnd; k++) {
                    for (CanonicalContentPart p : turns.get(k).parts()) {
                        if (p instanceof CanonicalContentPart.TextPart t) {
                            if (!t.text().isEmpty()) {
                                writeTextBlock(gen, t.text(), t.cacheControl());
                            }
                        } else if (p instanceof CanonicalContentPart.ImagePart img) {
                            writeImageBlock(gen, img);
                        }
                    }
                }
            }
            gen.writeEndArray();
            gen.writeEndObject();

            i = groupEnd;
        }
        gen.writeEndArray();
    }

    private static boolean hasEmittableBlock(CanonicalTurn turn, String targetRole) {
        if ("assistant".equals(targetRole)) {
            for (CanonicalContentPart p : turn.parts()) {
                switch (p) {
                    case CanonicalContentPart.ThinkingPart th -> {
                        if (CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                                && th.signature() != null && !th.signature().isBlank()) {
                            return true;
                        }
                    }
                    case CanonicalContentPart.RedactedThinkingPart rt -> {
                        if (CodecSupport.isSameOrigin(rt.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                                && rt.opaqueData() != null) {
                            return true;
                        }
                    }
                    case CanonicalContentPart.TextPart t -> {
                        if (!t.text().isEmpty()) {
                            return true;
                        }
                    }
                    case CanonicalContentPart.RefusalPart r -> {
                        if (!r.refusal().isEmpty()) {
                            return true;
                        }
                    }
                    case CanonicalContentPart.ToolCallPart ignored -> {
                        return true;
                    }
                    default -> {}
                }
            }
            return false;
        }
        for (CanonicalContentPart p : turn.parts()) {
            if (p instanceof CanonicalContentPart.TextPart t && !t.text().isEmpty()) {
                return true;
            }
            if (p instanceof CanonicalContentPart.ImagePart || p instanceof CanonicalContentPart.ToolResultPart) {
                return true;
            }
        }
        return false;
    }

    private void writeAssistantBlocks(JsonGenerator gen, CanonicalTurn turn) throws IOException {
        for (CanonicalContentPart p : turn.parts()) {
            switch (p) {
                case CanonicalContentPart.ThinkingPart th -> {
                    if (CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                            && th.signature() != null && !th.signature().isBlank()) {
                        gen.writeStartObject();
                        gen.writeStringField("type", "thinking");
                        gen.writeStringField("thinking", th.text());
                        gen.writeStringField("signature", th.signature());
                        gen.writeEndObject();
                    }
                }
                case CanonicalContentPart.RedactedThinkingPart rt -> {
                    if (CodecSupport.isSameOrigin(rt.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                            && rt.opaqueData() != null) {
                        gen.writeStartObject();
                        gen.writeStringField("type", "redacted_thinking");
                        gen.writeStringField("data", rt.opaqueData());
                        gen.writeEndObject();
                    }
                }
                case CanonicalContentPart.TextPart t -> {
                    if (!t.text().isEmpty()) {
                        writeTextBlock(gen, t.text(), t.cacheControl());
                    }
                }
                case CanonicalContentPart.RefusalPart r -> {
                    if (!r.refusal().isEmpty()) {
                        writeTextBlock(gen, r.refusal(), null);
                    }
                }
                case CanonicalContentPart.ToolCallPart tc -> {
                    gen.writeStartObject();
                    gen.writeStringField("type", "tool_use");
                    gen.writeStringField("id", tc.callId() != null ? tc.callId() : CodecSupport.generateId("toolu_"));
                    gen.writeStringField("name", tc.name());
                    gen.writeFieldName("input");
                    CodecSupport.writeArgumentsAsObject(gen, mapper, tc);
                    gen.writeEndObject();
                }
                case CanonicalContentPart.ImagePart ignored -> {}
                case CanonicalContentPart.ToolResultPart ignored -> {}
            }
        }
    }

    private void writeTextBlock(JsonGenerator gen, String text, CacheControl cc) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "text");
        gen.writeStringField("text", text);
        if (cc != null) {
            gen.writeFieldName("cache_control");
            CodecSupport.writeCacheControl(gen, cc);
        }
        gen.writeEndObject();
    }

    private void writeImageBlock(JsonGenerator gen, CanonicalContentPart.ImagePart img) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "image");
        gen.writeObjectFieldStart("source");
        switch (img.source()) {
            case ImageSource.Base64Image b64 -> {
                gen.writeStringField("type", "base64");
                gen.writeStringField("media_type", b64.mediaType());
                gen.writeStringField("data", b64.base64Data());
            }
            case ImageSource.UrlImage u -> {
                gen.writeStringField("type", "url");
                gen.writeStringField("url", u.url());
            }
            case ImageSource.FileRefImage ref -> {
                gen.writeStringField("type", "file");
                gen.writeStringField("file_id", ref.fileRef());
            }
        }
        gen.writeEndObject();
        if (img.cacheControl() != null) {
            gen.writeFieldName("cache_control");
            CodecSupport.writeCacheControl(gen, img.cacheControl());
        }
        gen.writeEndObject();
    }

    private void writeToolResultBlock(JsonGenerator gen, CanonicalContentPart.ToolResultPart tr) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "tool_result");
        gen.writeStringField("tool_use_id", tr.callId() != null ? tr.callId() : "");
        if (tr.isError()) {
            gen.writeBooleanField("is_error", true);
        }
        List<CanonicalContentPart> children = tr.content();
        if (children.isEmpty()) {
            gen.writeStringField("content", "");
        } else if (children.size() == 1 && children.getFirst() instanceof CanonicalContentPart.TextPart t) {
            gen.writeStringField("content", t.text());
        } else {
            boolean anyContent = false;
            for (CanonicalContentPart child : children) {
                if ((child instanceof CanonicalContentPart.TextPart t && !t.text().isEmpty())
                        || child instanceof CanonicalContentPart.ImagePart) {
                    anyContent = true;
                    break;
                }
            }
            if (!anyContent) {
                gen.writeStringField("content", "");
            } else {
                gen.writeArrayFieldStart("content");
                for (CanonicalContentPart child : children) {
                    if (child instanceof CanonicalContentPart.TextPart t) {
                        if (!t.text().isEmpty()) {
                            writeTextBlock(gen, t.text(), null);
                        }
                    } else if (child instanceof CanonicalContentPart.ImagePart img) {
                        writeImageBlock(gen, img);
                    }
                }
                gen.writeEndArray();
            }
        }
        if (tr.cacheControl() != null) {
            gen.writeFieldName("cache_control");
            CodecSupport.writeCacheControl(gen, tr.cacheControl());
        }
        gen.writeEndObject();
    }

    private void writeTools(
            JsonGenerator gen,
            CanonicalRequest req,
            boolean thinkingEnabled,
            TranslationPolicy policy
    ) throws IOException {
        if (req.tools().isEmpty()) {
            return;
        }
        gen.writeArrayFieldStart("tools");
        for (CanonicalToolDefinition tool : req.tools()) {
            gen.writeStartObject();
            gen.writeStringField("name", tool.name());
            if (tool.description() != null) {
                gen.writeStringField("description", tool.description());
            }
            gen.writeFieldName("input_schema");
            if (tool.parametersSchema() != null && tool.parametersSchema().isObject()) {
                CodecSupport.writeJsonNode(gen, mapper, tool.parametersSchema());
            } else {
                CodecSupport.writeEmptyObjectSchema(gen);
            }
            if (tool.strict() != null) {
                gen.writeBooleanField("strict", tool.strict());
            }
            if (tool.cacheControl() != null) {
                gen.writeFieldName("cache_control");
                CodecSupport.writeCacheControl(gen, tool.cacheControl());
            }
            gen.writeEndObject();
        }
        gen.writeEndArray();

        CanonicalToolChoice tc = req.toolChoice();
        if (tc.mode() == CanonicalToolChoice.ToolChoiceMode.OMITTED && tc.parallelToolCalls() == null) {
            return;
        }
        CanonicalToolChoice.ToolChoiceMode mode = tc.mode() == CanonicalToolChoice.ToolChoiceMode.OMITTED
                ? CanonicalToolChoice.ToolChoiceMode.AUTO : tc.mode();
        if (thinkingEnabled && (mode == CanonicalToolChoice.ToolChoiceMode.REQUIRED || mode == CanonicalToolChoice.ToolChoiceMode.SPECIFIC)) {
            if (policy == TranslationPolicy.STRICT) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "Anthropic extended thinking is incompatible with forced tool_choice (any/tool)");
            }
            mode = CanonicalToolChoice.ToolChoiceMode.AUTO;
        }
        gen.writeObjectFieldStart("tool_choice");
        switch (mode) {
            case NONE -> gen.writeStringField("type", "none");
            case AUTO -> gen.writeStringField("type", "auto");
            case REQUIRED -> gen.writeStringField("type", "any");
            case SPECIFIC -> {
                gen.writeStringField("type", "tool");
                gen.writeStringField("name", tc.specificToolName());
            }
            case OMITTED -> gen.writeStringField("type", "auto");
        }
        if (Boolean.FALSE.equals(tc.parallelToolCalls()) && mode != CanonicalToolChoice.ToolChoiceMode.NONE) {
            gen.writeBooleanField("disable_parallel_tool_use", true);
        }
        gen.writeEndObject();
    }

    // ---- Usage & stop reason helpers ----

    static Optional<TokenUsage> parseUsageObject(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Optional.empty();
        }
        int input = Math.max(0, usage.path("input_tokens").asInt(0));
        int output = Math.max(0, usage.path("output_tokens").asInt(0));
        int cacheRead = Math.max(0, usage.path("cache_read_input_tokens").asInt(0));
        int cacheWrite = Math.max(0, usage.path("cache_creation_input_tokens").asInt(0));
        return Optional.of(TokenUsage.fromAnthropic(input, cacheRead, cacheWrite, output));
    }

    private ObjectNode encodeUsageObject(TokenUsage usage) {
        TokenUsage u = usage != null ? usage : TokenUsage.empty();
        ObjectNode node = mapper.createObjectNode();
        node.put("input_tokens", u.promptTokens());
        if (u.cachedWriteTokens() > 0) {
            node.put("cache_creation_input_tokens", u.cachedWriteTokens());
        }
        if (u.cachedReadTokens() > 0) {
            node.put("cache_read_input_tokens", u.cachedReadTokens());
        }
        node.put("output_tokens", u.totalOutputTokens());
        return node;
    }

    private static CanonicalStopReason mapStopReason(String stopReason, List<CanonicalContentPart> parts) {
        boolean hasTools = parts.stream().anyMatch(p -> p instanceof CanonicalContentPart.ToolCallPart);
        if (stopReason == null) {
            return hasTools ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN;
        }
        return switch (stopReason) {
            case "tool_use" -> CanonicalStopReason.TOOL_CALLS;
            case "max_tokens", "model_context_window_exceeded" -> CanonicalStopReason.MAX_TOKENS;
            case "stop_sequence" -> CanonicalStopReason.STOP_SEQUENCE;
            case "refusal" -> CanonicalStopReason.REFUSAL;
            default -> hasTools ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN;
        };
    }

    private static String encodeStopReason(CanonicalStopReason reason, boolean hasTools) {
        if (reason == null) {
            return hasTools ? "tool_use" : "end_turn";
        }
        return switch (reason) {
            case TOOL_CALLS -> "tool_use";
            case MAX_TOKENS -> "max_tokens";
            case STOP_SEQUENCE -> "stop_sequence";
            case REFUSAL, CONTENT_FILTER -> "refusal";
            case END_TURN, MALFORMED_TOOL_CALL, ERROR -> hasTools ? "tool_use" : "end_turn";
        };
    }

    // ---- Streaming Decoder ----

    private final class AnthropicStreamDecoder implements UpstreamStreamDecoder {

        private final String virtualModel;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private final Map<Integer, String> toolCallIdsByIndex = new HashMap<>();
        private TokenUsage mergedUsage = TokenUsage.empty();
        private boolean finished;
        private boolean sawToolCall;

        private AnthropicStreamDecoder(String virtualModel) {
            this.virtualModel = virtualModel;
        }

        @Override
        public List<CanonicalStreamEvent> feed(byte[] upstreamChunk) {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            splitter.push(upstreamChunk, ev -> processEvent(ev, out));
            return out;
        }

        @Override
        public List<CanonicalStreamEvent> complete() {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            splitter.flushRemainder(ev -> processEvent(ev, out));
            if (!finished) {
                finished = true;
                out.add(new CanonicalStreamEvent.MessageFinish(
                        sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN, null));
            }
            return out;
        }

        private record DeltaFrame(int index, String kind, String text) {
        }

        /**
         * Tree-free fast path for {@code content_block_delta} frames, which make up every token of an
         * Anthropic stream. Returns {@code null} for any other event type so the caller keeps the
         * generic {@link JsonNode} path.
         */
        private DeltaFrame scanDeltaFrame(byte[] source, int offset, int length) {
            try (JsonParser parser = mapper.getFactory().createParser(source, offset, length)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return null;
                }
                int index = 0;
                String deltaKind = null;
                String text = null;
                boolean sawType = false;
                boolean sawDelta = false;
                JsonToken token;
                while ((token = parser.nextToken()) == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    switch (field) {
                        case "type" -> {
                            if (value != JsonToken.VALUE_STRING
                                    || !"content_block_delta".equals(parser.getText())) {
                                return null;
                            }
                            sawType = true;
                        }
                        case "index" -> {
                            if (!value.isNumeric()) {
                                return null;
                            }
                            index = parser.getIntValue();
                        }
                        case "delta" -> {
                            if (value != JsonToken.START_OBJECT) {
                                return null;
                            }
                            sawDelta = true;
                            JsonToken deltaToken;
                            while ((deltaToken = parser.nextToken()) == JsonToken.FIELD_NAME) {
                                String deltaField = parser.currentName();
                                JsonToken deltaValue = parser.nextToken();
                                switch (deltaField) {
                                    case "type" -> {
                                        if (deltaValue != JsonToken.VALUE_STRING) {
                                            return null;
                                        }
                                        deltaKind = switch (parser.getText()) {
                                            case "text_delta" -> "text";
                                            case "thinking_delta" -> "thinking";
                                            case "signature_delta" -> "signature";
                                            case "input_json_delta" -> "partial_json";
                                            default -> null;
                                        };
                                        if (deltaKind == null) {
                                            return null;
                                        }
                                    }
                                    case "text", "thinking", "signature", "partial_json" -> {
                                        if (deltaValue == JsonToken.VALUE_NULL) {
                                            text = null;
                                        } else if (deltaValue == JsonToken.VALUE_STRING) {
                                            text = parser.getText();
                                        } else {
                                            return null;
                                        }
                                    }
                                    default -> {
                                        return null;
                                    }
                                }
                            }
                            if (deltaToken != JsonToken.END_OBJECT) {
                                return null;
                            }
                        }
                        default -> {
                            return null;
                        }
                    }
                }
                if (token != JsonToken.END_OBJECT || !sawType || !sawDelta || deltaKind == null) {
                    return null;
                }
                return new DeltaFrame(index, deltaKind, text);
            } catch (Exception ex) {
                return null;
            }
        }

        private void emitDeltaFrame(DeltaFrame frame, List<CanonicalStreamEvent> out) {
            String text = frame.text();
            if (text == null || text.isEmpty()) {
                return;
            }
            switch (frame.kind()) {
                case "text" -> out.add(new CanonicalStreamEvent.TextDelta(frame.index(), text));
                case "thinking" -> out.add(new CanonicalStreamEvent.ThinkingDelta(frame.index(), text));
                case "signature" -> out.add(new CanonicalStreamEvent.ThinkingSignatureDelta(
                        frame.index(), text, ProtocolKind.ANTHROPIC_MESSAGES));
                case "partial_json" -> out.add(new CanonicalStreamEvent.ToolCallArgsDelta(
                        frame.index(), toolCallIdsByIndex.get(frame.index()), text));
                default -> {
                }
            }
        }

        private void processEvent(SseEventSplitter.SseEvent ev, List<CanonicalStreamEvent> out) {
            if (!ev.hasData()) {
                return;
            }
            DeltaFrame fastFrame = scanDeltaFrame(ev.dataSource(), ev.dataOffset(), ev.dataLength());
            if (fastFrame != null) {
                emitDeltaFrame(fastFrame, out);
                return;
            }
            try {
                JsonNode root = CodecSupport.readTreeBytes(
                        mapper, ev.dataSource(), ev.dataOffset(), ev.dataLength());
                if (!root.isObject()) {
                    return;
                }
                JsonNode typeNode = root.get("type");
                String type = (typeNode == null || typeNode.isNull())
                        ? ev.eventNameString() : typeNode.asText("");
                switch (type) {
                    case "message_start" -> {
                        JsonNode msg = root.path("message");
                        String id = msg.path("id").asText(CodecSupport.generateId("msg_"));
                        String model = (virtualModel != null && !virtualModel.isBlank())
                                ? virtualModel : msg.path("model").asText("");
                        out.add(new CanonicalStreamEvent.MessageStart(id, model, CodecSupport.nowEpochSeconds()));
                        parseUsageObject(msg.get("usage")).ifPresent(u -> {
                            mergedUsage = mergedUsage.mergeMax(u);
                            out.add(new CanonicalStreamEvent.UsageUpdate(mergedUsage, false));
                        });
                    }
                    case "content_block_start" -> {
                        int idx = root.path("index").asInt(0);
                        JsonNode block = root.path("content_block");
                        String bType = block.path("type").asText("");
                        switch (bType) {
                            case "text" -> out.add(new CanonicalStreamEvent.BlockStart(idx, BlockKind.TEXT));
                            case "thinking" -> out.add(new CanonicalStreamEvent.BlockStart(idx, BlockKind.THINKING));
                            case "redacted_thinking" -> {
                                out.add(new CanonicalStreamEvent.BlockStart(idx, BlockKind.REDACTED_THINKING));
                                out.add(new CanonicalStreamEvent.RedactedThinking(
                                        idx, block.path("data").asText(""), ProtocolKind.ANTHROPIC_MESSAGES));
                            }
                            case "tool_use" -> {
                                sawToolCall = true;
                                String callId = block.path("id").asText(CodecSupport.generateId("toolu_"));
                                String name = block.path("name").asText("");
                                toolCallIdsByIndex.put(idx, callId);
                                out.add(new CanonicalStreamEvent.ToolCallStart(
                                        idx, callId, null, name, null, ProtocolKind.ANTHROPIC_MESSAGES));
                            }
                            default -> {}
                        }
                    }
                    case "content_block_delta" -> {
                        int idx = root.path("index").asInt(0);
                        JsonNode delta = root.path("delta");
                        String dType = delta.path("type").asText("");
                        switch (dType) {
                            case "text_delta" -> {
                                String t = delta.path("text").asText("");
                                if (!t.isEmpty()) {
                                    out.add(new CanonicalStreamEvent.TextDelta(idx, t));
                                }
                            }
                            case "thinking_delta" -> {
                                String th = delta.path("thinking").asText("");
                                if (!th.isEmpty()) {
                                    out.add(new CanonicalStreamEvent.ThinkingDelta(idx, th));
                                }
                            }
                            case "signature_delta" -> {
                                String sig = delta.path("signature").asText("");
                                if (!sig.isEmpty()) {
                                    out.add(new CanonicalStreamEvent.ThinkingSignatureDelta(
                                            idx, sig, ProtocolKind.ANTHROPIC_MESSAGES));
                                }
                            }
                            case "input_json_delta" -> {
                                String pj = delta.path("partial_json").asText("");
                                if (!pj.isEmpty()) {
                                    out.add(new CanonicalStreamEvent.ToolCallArgsDelta(
                                            idx, toolCallIdsByIndex.get(idx), pj));
                                }
                            }
                            default -> {}
                        }
                    }
                    case "content_block_stop" -> {
                        int idx = root.path("index").asInt(0);
                        toolCallIdsByIndex.remove(idx);
                        out.add(new CanonicalStreamEvent.BlockStop(idx));
                    }
                    case "message_delta" -> {
                        parseUsageObject(root.get("usage")).ifPresent(u -> {
                            mergedUsage = mergedUsage.mergeMax(u);
                            out.add(new CanonicalStreamEvent.UsageUpdate(mergedUsage, true));
                        });
                        JsonNode delta = root.path("delta");
                        String sr = CodecSupport.asNullableText(delta, "stop_reason");
                        String stopSeq = CodecSupport.asNullableText(delta, "stop_sequence");
                        CanonicalStopReason mapped = mapStopReason(sr, sawToolCall
                                ? List.of(new CanonicalContentPart.ToolCallPart("", null, "", null, "", null, ProtocolKind.ANTHROPIC_MESSAGES))
                                : List.of());
                        finished = true;
                        out.add(new CanonicalStreamEvent.MessageFinish(mapped, stopSeq));
                    }
                    case "error" -> {
                        JsonNode err = root.path("error");
                        out.add(new CanonicalStreamEvent.StreamError(
                                err.path("type").asText("api_error"),
                                err.path("message").asText("Anthropic stream error")
                        ));
                        finished = true;
                    }
                    default -> {}
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ---- Streaming Encoder (handles strictly sequential Anthropic block indices + parallel tool buffering) ----

    private final class AnthropicStreamEncoder implements DownstreamStreamEncoder {

        private final String model;
        private final CodecSupport.ResizableByteBuffer frameBuffer = new CodecSupport.ResizableByteBuffer(256);
        private String messageId = CodecSupport.generateId("msg_");
        private boolean started;
        private boolean finished;
        private int nextAnthropicIdx;
        private Integer activeSourceBlock;
        private int activeAnthropicIdx = -1;
        private BlockKind activeKind;
        private final Map<Integer, BufferedBlock> bufferedBlocks = new LinkedHashMap<>();
        private final Deque<Integer> completedBufferedQueue = new ArrayDeque<>();
        private boolean sawToolCall;
        private TokenUsage latestUsage = TokenUsage.empty();

        private static final class BufferedBlock {
            final BlockKind kind;
            final String callId;
            final String toolName;
            final List<String> deltas = new ArrayList<>();
            boolean closed;

            BufferedBlock(BlockKind kind, String callId, String toolName) {
                this.kind = kind;
                this.callId = callId;
                this.toolName = toolName;
            }
        }

        private AnthropicStreamEncoder(StreamRequestMetadata inboundMeta) {
            this.model = inboundMeta.model();
        }

        @Override
        public String contentType() {
            return "text/event-stream";
        }

        @Override
        public List<byte[]> encode(CanonicalStreamEvent event) {
            List<byte[]> frames = new ArrayList<>();
            switch (event) {
                case CanonicalStreamEvent.MessageStart start -> {
                    if (start.responseId() != null && !start.responseId().isBlank()) {
                        this.messageId = start.responseId();
                    }
                    ensureMessageStart(frames);
                }
                case CanonicalStreamEvent.UsageUpdate uu -> {
                    latestUsage = uu.cumulative() ? uu.usage() : latestUsage.mergeMax(uu.usage());
                }
                case CanonicalStreamEvent.BlockStart bs -> {
                    ensureMessageStart(frames);
                    if (bs.kind() == BlockKind.TEXT || bs.kind() == BlockKind.THINKING || bs.kind() == BlockKind.REFUSAL) {
                        BlockKind mappedKind = bs.kind() == BlockKind.REFUSAL ? BlockKind.TEXT : bs.kind();
                        openOrBufferBlock(bs.blockIndex(), mappedKind, null, null, frames);
                    }
                }
                case CanonicalStreamEvent.TextDelta td -> {
                    ensureMessageStart(frames);
                    handleDelta(td.blockIndex(), BlockKind.TEXT, td.textDelta(), frames);
                }
                case CanonicalStreamEvent.RefusalDelta ref -> {
                    ensureMessageStart(frames);
                    handleDelta(ref.blockIndex(), BlockKind.TEXT, ref.refusalDelta(), frames);
                }
                case CanonicalStreamEvent.ThinkingDelta th -> {
                    ensureMessageStart(frames);
                    handleDelta(th.blockIndex(), BlockKind.THINKING, th.thinkingDelta(), frames);
                }
                case CanonicalStreamEvent.ThinkingSignatureDelta sig -> {
                    if (CodecSupport.isSameOrigin(sig.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                            && activeSourceBlock != null && activeSourceBlock == sig.blockIndex()) {
                        ObjectNode delta = mapper.createObjectNode();
                        delta.put("type", "signature_delta");
                        delta.put("signature", sig.signatureDelta());
                        emitBlockDelta(activeAnthropicIdx, delta, frames);
                    }
                }
                case CanonicalStreamEvent.ToolCallStart tcs -> {
                    ensureMessageStart(frames);
                    sawToolCall = true;
                    openOrBufferBlock(
                            tcs.blockIndex(),
                            BlockKind.TOOL_CALL,
                            tcs.callId() != null ? tcs.callId() : CodecSupport.generateId("toolu_"),
                            tcs.name(),
                            frames
                    );
                }
                case CanonicalStreamEvent.ToolCallArgsDelta tcad -> {
                    ensureMessageStart(frames);
                    handleDelta(tcad.blockIndex(), BlockKind.TOOL_CALL, tcad.partialJsonDelta(), frames);
                }
                case CanonicalStreamEvent.BlockStop stop -> closeBlock(stop.blockIndex(), frames);
                case CanonicalStreamEvent.MessageFinish mf -> emitFinish(mf.stopReason(), mf.stopSequence(), frames);
                case CanonicalStreamEvent.StreamError err -> {
                    ObjectNode ev = mapper.createObjectNode();
                    ev.put("type", "error");
                    ObjectNode inner = mapper.createObjectNode();
                    inner.put("type", err.errorType() != null ? err.errorType() : "api_error");
                    inner.put("message", err.message() != null ? err.message() : "Upstream stream error");
                    ev.set("error", inner);
                    frames.add(SseEventSplitter.buildNamedEventFrame("error", CodecSupport.writeJsonString(mapper, ev)));
                    finished = true;
                }
                case CanonicalStreamEvent.RedactedThinking ignored -> {}
            }
            return frames;
        }

        @Override
        public List<byte[]> finish() {
            if (finished) {
                return List.of();
            }
            List<byte[]> frames = new ArrayList<>();
            emitFinish(sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN, null, frames);
            return frames;
        }

        private void ensureMessageStart(List<byte[]> frames) {
            if (started) {
                return;
            }
            started = true;
            ObjectNode msg = mapper.createObjectNode();
            msg.put("id", messageId);
            msg.put("type", "message");
            msg.put("role", "assistant");
            msg.set("content", mapper.createArrayNode());
            msg.put("model", model);
            msg.set("stop_reason", NullNode.getInstance());
            msg.set("stop_sequence", NullNode.getInstance());
            ObjectNode initialUsage = mapper.createObjectNode();
            initialUsage.put("input_tokens", latestUsage.promptTokens());
            initialUsage.put("output_tokens", 0);
            if (latestUsage.cachedReadTokens() > 0) {
                initialUsage.put("cache_read_input_tokens", latestUsage.cachedReadTokens());
            }
            if (latestUsage.cachedWriteTokens() > 0) {
                initialUsage.put("cache_creation_input_tokens", latestUsage.cachedWriteTokens());
            }
            msg.set("usage", initialUsage);

            ObjectNode ev = mapper.createObjectNode();
            ev.put("type", "message_start");
            ev.set("message", msg);
            frames.add(SseEventSplitter.buildNamedEventFrame("message_start", CodecSupport.writeJsonString(mapper, ev)));
        }

        private void openOrBufferBlock(int sourceIdx, BlockKind kind, String callId, String toolName, List<byte[]> frames) {
            if (activeSourceBlock == null) {
                activeSourceBlock = sourceIdx;
                activeAnthropicIdx = nextAnthropicIdx++;
                activeKind = kind;
                emitBlockStart(activeAnthropicIdx, kind, callId, toolName, frames);
            } else if (activeSourceBlock != sourceIdx) {
                bufferedBlocks.putIfAbsent(sourceIdx, new BufferedBlock(kind, callId, toolName));
            }
        }

        private void handleDelta(int sourceIdx, BlockKind kind, String text, List<byte[]> frames) {
            if (activeSourceBlock == null) {
                openOrBufferBlock(sourceIdx, kind, null, null, frames);
            }
            if (activeSourceBlock != null && activeSourceBlock == sourceIdx) {
                emitTypedDelta(activeAnthropicIdx, activeKind, text, frames);
            } else {
                BufferedBlock buf = bufferedBlocks.computeIfAbsent(sourceIdx, k -> new BufferedBlock(kind, null, null));
                buf.deltas.add(text);
            }
        }

        private void closeBlock(int sourceIdx, List<byte[]> frames) {
            if (activeSourceBlock != null && activeSourceBlock == sourceIdx) {
                emitBlockStop(activeAnthropicIdx, frames);
                activeSourceBlock = null;
                activeAnthropicIdx = -1;
                activeKind = null;
                flushBufferedBlocks(frames);
            } else {
                BufferedBlock buf = bufferedBlocks.get(sourceIdx);
                if (buf != null && !buf.closed) {
                    buf.closed = true;
                    completedBufferedQueue.addLast(sourceIdx);
                }
            }
        }

        private void flushBufferedBlocks(List<byte[]> frames) {
            while (!completedBufferedQueue.isEmpty()) {
                Integer nextSrc = completedBufferedQueue.pollFirst();
                BufferedBlock buf = bufferedBlocks.remove(nextSrc);
                if (buf != null) {
                    int idx = nextAnthropicIdx++;
                    emitBlockStart(idx, buf.kind, buf.callId, buf.toolName, frames);
                    for (String d : buf.deltas) {
                        emitTypedDelta(idx, buf.kind, d, frames);
                    }
                    emitBlockStop(idx, frames);
                }
            }
            // Promote the oldest still-open buffered block to active if any exists
            if (activeSourceBlock == null && !bufferedBlocks.isEmpty()) {
                var it = bufferedBlocks.entrySet().iterator();
                var entry = it.next();
                it.remove();
                BufferedBlock buf = entry.getValue();
                activeSourceBlock = entry.getKey();
                activeAnthropicIdx = nextAnthropicIdx++;
                activeKind = buf.kind;
                emitBlockStart(activeAnthropicIdx, buf.kind, buf.callId, buf.toolName, frames);
                for (String d : buf.deltas) {
                    emitTypedDelta(activeAnthropicIdx, buf.kind, d, frames);
                }
            }
        }

        private void emitFinish(CanonicalStopReason reason, String stopSeq, List<byte[]> frames) {
            if (finished) {
                return;
            }
            ensureMessageStart(frames);
            if (activeSourceBlock != null) {
                emitBlockStop(activeAnthropicIdx, frames);
                activeSourceBlock = null;
                activeAnthropicIdx = -1;
                activeKind = null;
            }
            for (BufferedBlock buf : bufferedBlocks.values()) {
                int idx = nextAnthropicIdx++;
                emitBlockStart(idx, buf.kind, buf.callId, buf.toolName, frames);
                for (String d : buf.deltas) {
                    emitTypedDelta(idx, buf.kind, d, frames);
                }
                emitBlockStop(idx, frames);
            }
            bufferedBlocks.clear();
            completedBufferedQueue.clear();

            ObjectNode delta = mapper.createObjectNode();
            delta.put("stop_reason", encodeStopReason(reason, sawToolCall));
            if (stopSeq != null) {
                delta.put("stop_sequence", stopSeq);
            } else {
                delta.set("stop_sequence", NullNode.getInstance());
            }
            ObjectNode msgDelta = mapper.createObjectNode();
            msgDelta.put("type", "message_delta");
            msgDelta.set("delta", delta);
            msgDelta.set("usage", encodeUsageObject(latestUsage));
            frames.add(SseEventSplitter.buildNamedEventFrame("message_delta", CodecSupport.writeJsonString(mapper, msgDelta)));

            ObjectNode msgStop = mapper.createObjectNode();
            msgStop.put("type", "message_stop");
            frames.add(SseEventSplitter.buildNamedEventFrame("message_stop", CodecSupport.writeJsonString(mapper, msgStop)));
            finished = true;
        }

        private void emitBlockStart(int idx, BlockKind kind, String callId, String toolName, List<byte[]> frames) {
            ObjectNode cb = mapper.createObjectNode();
            switch (kind) {
                case THINKING -> {
                    cb.put("type", "thinking");
                    cb.put("thinking", "");
                }
                case TOOL_CALL -> {
                    cb.put("type", "tool_use");
                    cb.put("id", callId != null ? callId : CodecSupport.generateId("toolu_"));
                    cb.put("name", toolName != null ? toolName : "");
                    cb.set("input", mapper.createObjectNode());
                }
                default -> {
                    cb.put("type", "text");
                    cb.put("text", "");
                }
            }
            ObjectNode ev = mapper.createObjectNode();
            ev.put("type", "content_block_start");
            ev.put("index", idx);
            ev.set("content_block", cb);
            frames.add(SseEventSplitter.buildNamedEventFrame("content_block_start", CodecSupport.writeJsonString(mapper, ev)));
        }

        private void emitTypedDelta(int idx, BlockKind kind, String fragment, List<byte[]> frames) {
            switch (kind) {
                case THINKING -> frames.add(buildDeltaFrame(idx, "thinking_delta", "thinking", fragment));
                case TOOL_CALL -> frames.add(buildDeltaFrame(idx, "input_json_delta", "partial_json", fragment));
                default -> frames.add(buildDeltaFrame(idx, "text_delta", "text", fragment));
            }
        }

        /**
         * Renders a {@code content_block_delta} frame straight into bytes: constant envelope, the block
         * index, then the delta object produced by Jackson (so escaping stays authoritative). The payload
         * buffer is reused across frames.
         */
        private byte[] buildDeltaFrame(int idx, String deltaType, String field, String fragment) {
            CodecSupport.ResizableByteBuffer buffer = frameBuffer;
            buffer.reset();
            buffer.writeBytes(DELTA_EVENT_PREFIX);
            buffer.writeBytes(Integer.toString(idx).getBytes(StandardCharsets.US_ASCII));
            buffer.writeBytes(DELTA_EVENT_DELTA_KEY);
            try (JsonGenerator generator = CodecSupport.newUtf8Generator(mapper, buffer)) {
                generator.writeStartObject();
                generator.writeStringField("type", deltaType);
                generator.writeStringField(field, fragment);
                generator.writeEndObject();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to encode Anthropic delta frame", ex);
            }
            buffer.writeBytes(DELTA_EVENT_TAIL);
            return buffer.toCopiedByteArray();
        }

        private void emitBlockDelta(int idx, ObjectNode delta, List<byte[]> frames) {
            ObjectNode ev = mapper.createObjectNode();
            ev.put("type", "content_block_delta");
            ev.put("index", idx);
            ev.set("delta", delta);
            frames.add(SseEventSplitter.buildNamedEventFrame("content_block_delta", CodecSupport.writeJsonString(mapper, ev)));
        }

        private void emitBlockStop(int idx, List<byte[]> frames) {
            ObjectNode ev = mapper.createObjectNode();
            ev.put("type", "content_block_stop");
            ev.put("index", idx);
            frames.add(SseEventSplitter.buildNamedEventFrame("content_block_stop", CodecSupport.writeJsonString(mapper, ev)));
        }
    }

}
