package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.BlockKind;
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
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamPayload;
import app.pagefault.hyperllm.protocol.ir.ProviderCapabilities;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bidirectional codec for OpenAI Chat Completions ({@code POST /v1/chat/completions}).
 */
public final class OpenAiChatCodec implements ProtocolCodec {

    /** Constant tail of a single-delta chunk frame: {@code ,"finish_reason":null}]}\n\n}. */
    private static final byte[] DELTA_FRAME_TAIL =
            ",\"finish_reason\":null}]}\n\n".getBytes(StandardCharsets.US_ASCII);

    private final ObjectMapper mapper;

    public OpenAiChatCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProtocolKind kind() {
        return ProtocolKind.OPENAI_CHAT;
    }

    @Override
    public CanonicalRequest decodeRequest(byte[] rawBody, InboundRequestContext context) {
        try (JsonParser parser = CodecSupport.openObjectParser(mapper, rawBody)) {
            return decodeStreamingRoot(parser);
        } catch (HyperLlmException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body", ex);
        }
    }

    @Override
    public byte[] encodeRequest(CanonicalRequest req, UpstreamEncodingContext context) {
        return writeRequest(req, context).toExactByteArray();
    }

    @Override
    public PreparedUpstreamPayload encodeRequestPayload(CanonicalRequest req, UpstreamEncodingContext context) {
        CodecSupport.ResizableByteBuffer out = writeRequest(req, context);
        return PreparedUpstreamPayload.ofSlice(ProtocolKind.OPENAI_CHAT, out.backingArray(), out.length());
    }

    private CodecSupport.ResizableByteBuffer writeRequest(CanonicalRequest req, UpstreamEncodingContext context) {
        ProviderCapabilities caps = context.capabilitiesFor(ProtocolKind.OPENAI_CHAT);
        CodecSupport.ResizableByteBuffer out = new CodecSupport.ResizableByteBuffer(
                CodecSupport.estimateEncodedCapacity(req));
        try (JsonGenerator gen = CodecSupport.newUtf8Generator(mapper, out)) {
            gen.writeStartObject();
            gen.writeStringField("model", context.upstreamModelName() != null && !context.upstreamModelName().isBlank()
                    ? context.upstreamModelName() : req.model());
            gen.writeBooleanField("stream", req.stream());
            if (req.stream()) {
                gen.writeObjectFieldStart("stream_options");
                gen.writeBooleanField("include_usage", true);
                gen.writeEndObject();
            }

            gen.writeArrayFieldStart("messages");
            int emittedMessages = 0;
            for (CanonicalSystemPart sys : req.systemInstructions()) {
                if (!sys.text().isEmpty()) {
                    gen.writeStartObject();
                    gen.writeStringField("role", (sys.developerPriority() && caps.supportsDeveloperRole()) ? "developer" : "system");
                    gen.writeStringField("content", sys.text());
                    gen.writeEndObject();
                    emittedMessages++;
                }
            }
            for (CanonicalTurn turn : req.turns()) {
                emittedMessages += writeTurn(gen, turn, context.policy(), caps);
            }
            if (emittedMessages == 0) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Translated messages array cannot be empty");
            }
            gen.writeEndArray();

            writeGenerationConfig(gen, req, caps, context.policy());
            writeTools(gen, req, caps);
            writeResponseFormat(gen, req.responseFormat());

            if (caps.supportsReasoningEffort()) {
                String effort = CodecSupport.toOpenAiReasoningEffort(req.thinking());
                if (effort != null) {
                    gen.writeStringField("reasoning_effort", effort);
                }
            }
            if (req.cache().promptCacheKey() != null && !req.cache().promptCacheKey().isBlank()) {
                gen.writeStringField("prompt_cache_key", req.cache().promptCacheKey());
            }
            if (req.cache().promptCacheRetention() != null && !req.cache().promptCacheRetention().isBlank()) {
                gen.writeStringField("prompt_cache_retention", req.cache().promptCacheRetention());
            }
            if (req.options().user() != null && !req.options().user().isBlank()) {
                gen.writeStringField("user", req.options().user());
            }
            if (req.options().serviceTier() != null && !req.options().serviceTier().isBlank()) {
                gen.writeStringField("service_tier", req.options().serviceTier());
            }
            if (req.options().store() != null) {
                gen.writeBooleanField("store", req.options().store());
            }
            gen.writeEndObject();
        } catch (HyperLlmException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize OpenAI Chat request JSON", ex);
        }
        return out;
    }

    // ---- Streaming request decoding (zero inbound DOM tree for large messages[]) ----

    private CanonicalRequest decodeStreamingRoot(JsonParser parser) throws IOException {
        String model = null;
        boolean stream = false;
        boolean clientRequestedUsage = false;
        boolean hasMessagesArray = false;

        List<CanonicalSystemPart> systemParts = new ArrayList<>();
        List<CanonicalTurn> turns = new ArrayList<>();
        List<UnsupportedFeatureFlag> unsupported = new ArrayList<>();

        JsonNode toolsNode = null;
        JsonNode toolChoiceNode = null;
        Boolean parallelToolCalls = null;
        JsonNode responseFormatNode = null;
        Integer maxCompletionTokens = null;
        Integer maxTokens = null;
        Double temperature = null;
        Double topP = null;
        JsonNode stopNode = null;
        Long seed = null;
        Double presencePenalty = null;
        Double frequencyPenalty = null;
        Integer n = null;
        String reasoningEffort = null;
        String promptCacheKey = null;
        String promptCacheRetention = null;
        String user = null;
        String serviceTier = null;
        Boolean store = null;
        boolean hasAudioOutput = false;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken valTok = parser.nextToken();
            if (valTok == null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body");
            }
            switch (field) {
                case "model" -> model = CodecSupport.readNullableString(parser);
                case "stream" -> stream = (valTok == JsonToken.VALUE_TRUE)
                        || (valTok != JsonToken.VALUE_FALSE && skipAndFalse(parser));
                case "stream_options" -> {
                    if (valTok == JsonToken.START_OBJECT) {
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String optField = parser.currentName();
                            JsonToken optTok = parser.nextToken();
                            if ("include_usage".equals(optField) && optTok == JsonToken.VALUE_TRUE) {
                                clientRequestedUsage = true;
                            } else {
                                parser.skipChildren();
                            }
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
                case "messages" -> {
                    if (valTok == JsonToken.START_ARRAY) {
                        hasMessagesArray = true;
                        readStreamingMessages(parser, systemParts, turns, unsupported);
                    } else {
                        parser.skipChildren();
                    }
                }
                case "tools" -> toolsNode = mapper.readTree(parser);
                case "tool_choice" -> toolChoiceNode = mapper.readTree(parser);
                case "parallel_tool_calls" -> parallelToolCalls = CodecSupport.readNullableBoolean(parser);
                case "response_format" -> responseFormatNode = mapper.readTree(parser);
                case "max_completion_tokens" -> maxCompletionTokens = CodecSupport.readNullableIntegralInt(parser);
                case "max_tokens" -> maxTokens = CodecSupport.readNullableIntegralInt(parser);
                case "temperature" -> temperature = CodecSupport.readNullableDouble(parser);
                case "top_p" -> topP = CodecSupport.readNullableDouble(parser);
                case "stop" -> stopNode = mapper.readTree(parser);
                case "seed" -> seed = CodecSupport.readNullableIntegralLong(parser);
                case "presence_penalty" -> presencePenalty = CodecSupport.readNullableDouble(parser);
                case "frequency_penalty" -> frequencyPenalty = CodecSupport.readNullableDouble(parser);
                case "n" -> n = CodecSupport.readNullableIntegralInt(parser);
                case "reasoning_effort" -> reasoningEffort = CodecSupport.readNullableString(parser);
                case "prompt_cache_key" -> promptCacheKey = CodecSupport.readNullableString(parser);
                case "prompt_cache_retention" -> promptCacheRetention = CodecSupport.readNullableString(parser);
                case "user" -> user = CodecSupport.readNullableString(parser);
                case "service_tier" -> serviceTier = CodecSupport.readNullableString(parser);
                case "store" -> store = CodecSupport.readNullableBoolean(parser);
                case "audio" -> {
                    if (valTok != JsonToken.VALUE_NULL) {
                        hasAudioOutput = true;
                    }
                    parser.skipChildren();
                }
                case "modalities" -> {
                    JsonNode mod = mapper.readTree(parser);
                    if (mod != null && !mod.isNull() && mod.toString().contains("audio")) {
                        hasAudioOutput = true;
                    }
                }
                default -> parser.skipChildren();
            }
        }

        if (model == null || model.isBlank()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'model' is required and must be non-blank");
        }
        if (!hasMessagesArray || (systemParts.isEmpty() && turns.isEmpty())) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'messages' must be a non-empty array");
        }

        List<CanonicalToolDefinition> tools = decodeTools(toolsNode, unsupported);
        CanonicalToolChoice toolChoice = decodeToolChoice(toolChoiceNode, parallelToolCalls);
        CanonicalResponseFormat responseFormat = decodeResponseFormat(responseFormatNode);
        Integer effectiveMaxTokens = maxCompletionTokens != null ? maxCompletionTokens : maxTokens;
        List<String> stops = decodeStopSequences(stopNode);

        CanonicalGenerationConfig generation = new CanonicalGenerationConfig(
                effectiveMaxTokens, temperature, topP, null, stops, seed, presencePenalty, frequencyPenalty, n);
        CanonicalThinkingConfig thinking = decodeThinking(reasoningEffort);
        CanonicalCacheConfig cache = new CanonicalCacheConfig(null, promptCacheKey, promptCacheRetention, null);
        CanonicalProtocolOptions options = new CanonicalProtocolOptions(
                clientRequestedUsage, true, null, null, user, serviceTier, store);
        if (hasAudioOutput) {
            unsupported.add(UnsupportedFeatureFlag.hard("audio_output", "Audio modalities are outside text-output scope"));
        }

        return new CanonicalRequest(
                ProtocolKind.OPENAI_CHAT, model.trim(), stream, systemParts, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, unsupported
        );
    }

    private void readStreamingMessages(
            JsonParser parser,
            List<CanonicalSystemPart> systemParts,
            List<CanonicalTurn> turns,
            List<UnsupportedFeatureFlag> unsupported
    ) throws IOException {
        boolean sawNonSystem = false;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String role = "user";
            String plainTextContent = null;
            List<CanonicalContentPart> arrayContentParts = null;
            String refusal = null;
            String reasoningContent = null;
            String reasoning = null;
            JsonNode reasoningDetails = null;
            List<CanonicalContentPart.ToolCallPart> toolCalls = null;
            CanonicalContentPart.ToolCallPart legacyFnCall = null;
            String toolCallId = null;
            String name = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken t = parser.nextToken();
                switch (f) {
                    case "role" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            role = parser.getText().toLowerCase(Locale.ROOT);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "content" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            plainTextContent = parser.getText();
                        } else if (t == JsonToken.START_ARRAY) {
                            arrayContentParts = readStreamingUserContentArray(parser, unsupported);
                        } else if (t == JsonToken.VALUE_NULL) {
                            // null content
                        } else {
                            JsonNode fallback = mapper.readTree(parser);
                            plainTextContent = fallback.toString();
                        }
                    }
                    case "tool_call_id" -> toolCallId = CodecSupport.readNullableString(parser);
                    case "name" -> name = CodecSupport.readNullableString(parser);
                    case "refusal" -> refusal = CodecSupport.readNullableString(parser);
                    case "reasoning_content" -> reasoningContent = CodecSupport.readNullableString(parser);
                    case "reasoning" -> reasoning = CodecSupport.readNullableString(parser);
                    case "reasoning_details" -> reasoningDetails = mapper.readTree(parser);
                    case "tool_calls" -> {
                        if (t == JsonToken.START_ARRAY) {
                            toolCalls = readStreamingToolCalls(parser);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "function_call" -> {
                        if (t == JsonToken.START_OBJECT) {
                            legacyFnCall = readStreamingLegacyFunctionCall(parser);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    default -> parser.skipChildren();
                }
            }

            switch (role) {
                case "system", "developer" -> {
                    boolean devPriority = "developer".equals(role);
                    String text = plainTextContent != null ? plainTextContent
                            : (arrayContentParts != null ? joinTextParts(arrayContentParts) : "");
                    if (!sawNonSystem) {
                        systemParts.add(new CanonicalSystemPart(text, devPriority, null));
                    } else {
                        turns.add(new CanonicalTurn(
                                devPriority ? CanonicalRole.DEVELOPER : CanonicalRole.SYSTEM,
                                List.of(new CanonicalContentPart.TextPart(text))
                        ));
                    }
                }
                case "user" -> {
                    sawNonSystem = true;
                    List<CanonicalContentPart> parts = resolveContentParts(plainTextContent, arrayContentParts);
                    turns.add(new CanonicalTurn(CanonicalRole.USER, parts));
                }
                case "assistant" -> {
                    sawNonSystem = true;
                    ExtractedReasoning extracted = resolveExtractedReasoning(reasoningContent, reasoning, reasoningDetails);
                    boolean onlyPlainText = extracted == null
                            && (refusal == null || refusal.isEmpty())
                            && (toolCalls == null || toolCalls.isEmpty())
                            && legacyFnCall == null
                            && arrayContentParts == null;
                    if (onlyPlainText) {
                        List<CanonicalContentPart> parts = plainTextContent != null
                                ? List.of(new CanonicalContentPart.TextPart(plainTextContent))
                                : List.of();
                        turns.add(new CanonicalTurn(CanonicalRole.ASSISTANT, parts));
                    } else {
                        List<CanonicalContentPart> parts = new ArrayList<>(4);
                        if (extracted != null) {
                            parts.add(new CanonicalContentPart.ThinkingPart(
                                    extracted.text(), null, null, ProtocolKind.OPENAI_CHAT, extracted.wireField()));
                        }
                        if (plainTextContent != null) {
                            parts.add(new CanonicalContentPart.TextPart(plainTextContent));
                        } else if (arrayContentParts != null) {
                            parts.addAll(arrayContentParts);
                        }
                        if (refusal != null && !refusal.isEmpty()) {
                            parts.add(new CanonicalContentPart.RefusalPart(refusal));
                        }
                        if (toolCalls != null && !toolCalls.isEmpty()) {
                            parts.addAll(toolCalls);
                        } else if (legacyFnCall != null) {
                            parts.add(legacyFnCall);
                        }
                        turns.add(new CanonicalTurn(CanonicalRole.ASSISTANT, parts));
                    }
                }
                case "tool", "function" -> {
                    sawNonSystem = true;
                    List<CanonicalContentPart> resultContent = resolveContentParts(plainTextContent, arrayContentParts);
                    turns.add(new CanonicalTurn(
                            CanonicalRole.TOOL,
                            List.of(new CanonicalContentPart.ToolResultPart(toolCallId, name, resultContent, false, null))
                    ));
                }
                default -> throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Unsupported message role: " + role);
            }
        }
    }

    private static List<CanonicalContentPart> resolveContentParts(
            String plainTextContent,
            List<CanonicalContentPart> arrayContentParts
    ) {
        if (arrayContentParts != null) {
            return arrayContentParts;
        }
        if (plainTextContent != null) {
            return List.of(new CanonicalContentPart.TextPart(plainTextContent));
        }
        return List.of();
    }

    private List<CanonicalContentPart> readStreamingUserContentArray(
            JsonParser parser,
            List<UnsupportedFeatureFlag> unsupported
    ) throws IOException {
        List<CanonicalContentPart> parts = new ArrayList<>(4);
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonToken t = parser.currentToken();
            if (t == JsonToken.VALUE_STRING) {
                parts.add(new CanonicalContentPart.TextPart(parser.getText()));
                continue;
            }
            if (t != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String type = "text";
            String text = "";
            String refusal = "";
            String imgUrl = "";
            ImageDetail imgDetail = ImageDetail.AUTO;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String pf = parser.currentName();
                JsonToken pt = parser.nextToken();
                switch (pf) {
                    case "type" -> {
                        if (pt == JsonToken.VALUE_STRING) {
                            type = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "text" -> {
                        if (pt == JsonToken.VALUE_STRING) {
                            text = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "refusal" -> {
                        if (pt == JsonToken.VALUE_STRING) {
                            refusal = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "image_url" -> {
                        if (pt == JsonToken.VALUE_STRING) {
                            imgUrl = parser.getText();
                        } else if (pt == JsonToken.START_OBJECT) {
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String imf = parser.currentName();
                                JsonToken imt = parser.nextToken();
                                if ("url".equals(imf) && imt == JsonToken.VALUE_STRING) {
                                    imgUrl = parser.getText();
                                } else if ("detail".equals(imf) && imt == JsonToken.VALUE_STRING) {
                                    imgDetail = ImageDetail.parseOrDefault(parser.getText(), ImageDetail.AUTO);
                                } else {
                                    parser.skipChildren();
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
                case "text" -> parts.add(new CanonicalContentPart.TextPart(text));
                case "refusal" -> parts.add(new CanonicalContentPart.RefusalPart(refusal));
                case "image_url" -> parts.add(new CanonicalContentPart.ImagePart(
                        CodecSupport.parseImageUrlOrDataUri(imgUrl), imgDetail, null));
                default -> unsupported.add(UnsupportedFeatureFlag.degradable("content_part:" + type,
                        "OpenAI Chat content part type '" + type + "' is not supported for translation"));
            }
        }
        return parts;
    }

    private List<CanonicalContentPart.ToolCallPart> readStreamingToolCalls(JsonParser parser) throws IOException {
        List<CanonicalContentPart.ToolCallPart> list = new ArrayList<>(4);
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String id = null;
            String name = "";
            String rawArgs = "{}";
            String thoughtSig = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken t = parser.nextToken();
                switch (f) {
                    case "id" -> id = CodecSupport.readNullableString(parser);
                    case "function" -> {
                        if (t == JsonToken.START_OBJECT) {
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String ff = parser.currentName();
                                JsonToken ft = parser.nextToken();
                                if ("name".equals(ff) && ft == JsonToken.VALUE_STRING) {
                                    name = parser.getText();
                                } else if ("arguments".equals(ff) && ft == JsonToken.VALUE_STRING) {
                                    rawArgs = parser.getText();
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "extra_content" -> {
                        if (t == JsonToken.START_OBJECT) {
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String ef = parser.currentName();
                                JsonToken et = parser.nextToken();
                                if ("google".equals(ef) && et == JsonToken.START_OBJECT) {
                                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                                        String gf = parser.currentName();
                                        JsonToken gt = parser.nextToken();
                                        if ("thought_signature".equals(gf) && gt == JsonToken.VALUE_STRING) {
                                            thoughtSig = parser.getText();
                                        } else {
                                            parser.skipChildren();
                                        }
                                    }
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    default -> parser.skipChildren();
                }
            }
            ProtocolKind origin = (thoughtSig != null) ? ProtocolKind.GEMINI_GENERATE_CONTENT : ProtocolKind.OPENAI_CHAT;
            // Defer JSON object parsing of rawArgs (zero eager readTree on 100+ historical tool calls!)
            list.add(new CanonicalContentPart.ToolCallPart(id, null, name, null, rawArgs, thoughtSig, origin));
        }
        return list;
    }

    private CanonicalContentPart.ToolCallPart readStreamingLegacyFunctionCall(JsonParser parser) throws IOException {
        String name = "";
        String rawArgs = "{}";
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String f = parser.currentName();
            JsonToken t = parser.nextToken();
            if ("name".equals(f) && t == JsonToken.VALUE_STRING) {
                name = parser.getText();
            } else if ("arguments".equals(f) && t == JsonToken.VALUE_STRING) {
                rawArgs = parser.getText();
            } else {
                parser.skipChildren();
            }
        }
        return new CanonicalContentPart.ToolCallPart(null, null, name, null, rawArgs, null, ProtocolKind.OPENAI_CHAT);
    }

    private static ExtractedReasoning resolveExtractedReasoning(
            String reasoningContent,
            String reasoning,
            JsonNode reasoningDetails
    ) {
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            return new ExtractedReasoning(reasoningContent, "reasoning_content");
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            return new ExtractedReasoning(reasoning, "reasoning");
        }
        if (reasoningDetails != null && reasoningDetails.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : reasoningDetails) {
                if (!item.isObject()) {
                    continue;
                }
                String piece = CodecSupport.asNullableText(item, "text");
                if (piece == null || piece.isEmpty()) {
                    piece = CodecSupport.asNullableText(item, "summary");
                }
                if (piece != null && !piece.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(piece);
                }
            }
            if (!sb.isEmpty()) {
                return new ExtractedReasoning(sb.toString(), "reasoning_content");
            }
        }
        return null;
    }

    private static boolean skipAndFalse(JsonParser parser) throws IOException {
        parser.skipChildren();
        return false;
    }

    @Override
    public CanonicalResponse decodeResponse(byte[] responseBody, String virtualModel) {
        ObjectNode root = CodecSupport.parseResponseObjectRoot(mapper, responseBody);
        String id = root.path("id").asText("");
        if (id.isBlank()) {
            id = CodecSupport.generateId("chatcmpl_");
        }
        long created = root.path("created").asLong(CodecSupport.nowEpochSeconds());
        String model = (virtualModel != null && !virtualModel.isBlank())
                ? virtualModel : root.path("model").asText("");

        List<CanonicalContentPart> parts = new ArrayList<>();
        CanonicalStopReason stopReason = CanonicalStopReason.END_TURN;

        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice0 = choices.get(0);
            JsonNode msg = choice0.path("message");
            if (msg.isObject()) {
                ExtractedReasoning reasoning = extractReasoning(msg);
                if (reasoning != null) {
                    parts.add(new CanonicalContentPart.ThinkingPart(
                            reasoning.text(), null, null, ProtocolKind.OPENAI_CHAT, reasoning.wireField()));
                }
                JsonNode content = msg.get("content");
                if (content != null && !content.isNull()) {
                    if (content.isTextual()) {
                        if (!content.asText().isEmpty()) {
                            parts.add(new CanonicalContentPart.TextPart(content.asText()));
                        }
                    } else if (content.isArray()) {
                        String text = extractPlainText(content);
                        if (!text.isEmpty()) {
                            parts.add(new CanonicalContentPart.TextPart(text));
                        }
                    }
                }
                String refusal = CodecSupport.asNullableText(msg, "refusal");
                if (refusal != null && !refusal.isEmpty()) {
                    parts.add(new CanonicalContentPart.RefusalPart(refusal));
                }
                JsonNode toolCalls = msg.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        parts.add(decodeToolCall(tc));
                    }
                }
                JsonNode legacyFnCall = msg.get("function_call");
                if ((toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty())
                        && legacyFnCall != null && legacyFnCall.isObject()) {
                    parts.add(decodeLegacyFunctionCall(legacyFnCall));
                }
            }
            stopReason = mapFinishReason(CodecSupport.asNullableText(choice0, "finish_reason"), parts);
        }

        TokenUsage usage = parseUsageNode(root.get("usage")).orElse(TokenUsage.empty());
        return new CanonicalResponse(id, model, created, parts, stopReason, null, usage, ProtocolKind.OPENAI_CHAT);
    }

    @Override
    public byte[] encodeResponse(CanonicalResponse response) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", response.id() != null && !response.id().isBlank() ? response.id() : CodecSupport.generateId("chatcmpl-"));
        root.put("object", "chat.completion");
        root.put("created", response.createdEpochSeconds() > 0 ? response.createdEpochSeconds() : CodecSupport.nowEpochSeconds());
        root.put("model", response.virtualModel());

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        StringBuilder refusal = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();

        for (CanonicalContentPart part : response.content()) {
            switch (part) {
                case CanonicalContentPart.TextPart t -> text.append(t.text());
                case CanonicalContentPart.ThinkingPart th -> reasoning.append(th.text());
                case CanonicalContentPart.RefusalPart r -> refusal.append(r.refusal());
                case CanonicalContentPart.ToolCallPart tc -> toolCalls.add(encodeToolCall(tc));
                case CanonicalContentPart.ImagePart ignored -> {}
                case CanonicalContentPart.RedactedThinkingPart ignored -> {}
                case CanonicalContentPart.ToolResultPart ignored -> {}
            }
        }

        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        if (!text.isEmpty()) {
            message.put("content", text.toString());
        } else if (!toolCalls.isEmpty() || !refusal.isEmpty()) {
            message.set("content", NullNode.getInstance());
        } else {
            message.put("content", "");
        }
        if (!reasoning.isEmpty()) {
            message.put("reasoning_content", reasoning.toString());
        }
        if (!refusal.isEmpty()) {
            message.put("refusal", refusal.toString());
        }
        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }

        ObjectNode choice = mapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", encodeFinishReason(response.stopReason(), !toolCalls.isEmpty()));

        ArrayNode choices = mapper.createArrayNode();
        choices.add(choice);
        root.set("choices", choices);
        root.set("usage", encodeUsageNode(response.usage()));

        return CodecSupport.writeJsonBytes(mapper, root);
    }

    @Override
    public Optional<TokenUsage> extractNonStreamingUsage(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            return parseUsageNode(root.get("usage"));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public UpstreamStreamDecoder newStreamDecoder(String virtualModel) {
        return new OpenAiChatStreamDecoder(virtualModel);
    }

    @Override
    public DownstreamStreamEncoder newStreamEncoder(StreamRequestMetadata inboundMeta) {
        return new OpenAiChatStreamEncoder(inboundMeta);
    }

    // ---- Request decoding helpers ----

    private CanonicalRequest decodeRoot(ObjectNode root) {
        String model = CodecSupport.requireModel(root);
        JsonNode messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray() || messagesNode.isEmpty()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'messages' must be a non-empty array");
        }
        boolean stream = root.path("stream").asBoolean(false);
        boolean clientRequestedUsage = root.path("stream_options").path("include_usage").asBoolean(false);

        List<CanonicalSystemPart> systemParts = new ArrayList<>();
        List<CanonicalTurn> turns = new ArrayList<>();
        List<UnsupportedFeatureFlag> unsupported = new ArrayList<>();
        boolean sawNonSystem = false;

        for (JsonNode msg : messagesNode) {
            if (!msg.isObject()) {
                continue;
            }
            String role = msg.path("role").asText("user").toLowerCase(Locale.ROOT);
            switch (role) {
                case "system", "developer" -> {
                    boolean devPriority = "developer".equals(role);
                    String text = extractPlainText(msg.get("content"));
                    if (!sawNonSystem) {
                        systemParts.add(new CanonicalSystemPart(text, devPriority, null));
                    } else {
                        turns.add(new CanonicalTurn(
                                devPriority ? CanonicalRole.DEVELOPER : CanonicalRole.SYSTEM,
                                List.of(new CanonicalContentPart.TextPart(text))
                        ));
                    }
                }
                case "user" -> {
                    sawNonSystem = true;
                    List<CanonicalContentPart> parts = decodeUserContent(msg.get("content"), unsupported);
                    turns.add(new CanonicalTurn(CanonicalRole.USER, parts));
                }
                case "assistant" -> {
                    sawNonSystem = true;
                    List<CanonicalContentPart> parts = new ArrayList<>();
                    ExtractedReasoning reasoning = extractReasoning(msg);
                    if (reasoning != null) {
                        parts.add(new CanonicalContentPart.ThinkingPart(
                                reasoning.text(), null, null, ProtocolKind.OPENAI_CHAT, reasoning.wireField()));
                    }
                    JsonNode contentNode = msg.get("content");
                    if (contentNode != null && !contentNode.isNull()) {
                        parts.addAll(decodeUserContent(contentNode, unsupported));
                    }
                    String refusal = CodecSupport.asNullableText(msg, "refusal");
                    if (refusal != null && !refusal.isEmpty()) {
                        parts.add(new CanonicalContentPart.RefusalPart(refusal));
                    }
                    JsonNode toolCalls = msg.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            parts.add(decodeToolCall(tc));
                        }
                    }
                    JsonNode legacyFnCall = msg.get("function_call");
                    if ((toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty())
                            && legacyFnCall != null && legacyFnCall.isObject()) {
                        parts.add(decodeLegacyFunctionCall(legacyFnCall));
                    }
                    turns.add(new CanonicalTurn(CanonicalRole.ASSISTANT, parts));
                }
                case "tool" -> {
                    sawNonSystem = true;
                    String callId = CodecSupport.asNullableText(msg, "tool_call_id");
                    String toolName = CodecSupport.asNullableText(msg, "name");
                    List<CanonicalContentPart> resultContent = decodeUserContent(msg.get("content"), unsupported);
                    turns.add(new CanonicalTurn(
                            CanonicalRole.TOOL,
                            List.of(new CanonicalContentPart.ToolResultPart(callId, toolName, resultContent, false, null))
                    ));
                }
                case "function" -> {
                    sawNonSystem = true;
                    String toolName = CodecSupport.asNullableText(msg, "name");
                    String callId = CodecSupport.asNullableText(msg, "tool_call_id");
                    List<CanonicalContentPart> resultContent = decodeUserContent(msg.get("content"), unsupported);
                    turns.add(new CanonicalTurn(
                            CanonicalRole.TOOL,
                            List.of(new CanonicalContentPart.ToolResultPart(callId, toolName, resultContent, false, null))
                    ));
                }
                default -> throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Unsupported message role: " + role);
            }
        }

        List<CanonicalToolDefinition> tools = decodeTools(root.get("tools"), unsupported);
        CanonicalToolChoice toolChoice = decodeToolChoice(root.get("tool_choice"), CodecSupport.asNullableBoolean(root, "parallel_tool_calls"));
        CanonicalResponseFormat responseFormat = decodeResponseFormat(root.get("response_format"));

        Integer maxCompletionTokens = CodecSupport.asNullableInt(root, "max_completion_tokens");
        Integer maxTokens = maxCompletionTokens != null
                ? maxCompletionTokens
                : CodecSupport.asNullableInt(root, "max_tokens");
        List<String> stops = decodeStopSequences(root.get("stop"));
        CanonicalGenerationConfig generation = new CanonicalGenerationConfig(
                maxTokens,
                CodecSupport.asNullableDouble(root, "temperature"),
                CodecSupport.asNullableDouble(root, "top_p"),
                null,
                stops,
                CodecSupport.asNullableLong(root, "seed"),
                CodecSupport.asNullableDouble(root, "presence_penalty"),
                CodecSupport.asNullableDouble(root, "frequency_penalty"),
                CodecSupport.asNullableInt(root, "n")
        );

        CanonicalThinkingConfig thinking = decodeThinking(CodecSupport.asNullableText(root, "reasoning_effort"));
        CanonicalCacheConfig cache = new CanonicalCacheConfig(
                null,
                CodecSupport.asNullableText(root, "prompt_cache_key"),
                CodecSupport.asNullableText(root, "prompt_cache_retention"),
                null
        );
        CanonicalProtocolOptions options = new CanonicalProtocolOptions(
                clientRequestedUsage,
                true,
                null,
                null,
                CodecSupport.asNullableText(root, "user"),
                CodecSupport.asNullableText(root, "service_tier"),
                CodecSupport.asNullableBoolean(root, "store")
        );

        if (root.hasNonNull("audio") || (root.hasNonNull("modalities") && root.get("modalities").toString().contains("audio"))) {
            unsupported.add(UnsupportedFeatureFlag.hard("audio_output", "Audio modalities are outside text-output scope"));
        }

        return new CanonicalRequest(
                ProtocolKind.OPENAI_CHAT, model, stream, systemParts, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, unsupported
        );
    }

    private List<CanonicalContentPart> decodeUserContent(JsonNode contentNode, List<UnsupportedFeatureFlag> unsupported) {
        if (contentNode == null || contentNode.isNull()) {
            return List.of();
        }
        if (contentNode.isTextual()) {
            return List.of(new CanonicalContentPart.TextPart(contentNode.asText()));
        }
        if (!contentNode.isArray()) {
            return List.of(new CanonicalContentPart.TextPart(contentNode.toString()));
        }
        List<CanonicalContentPart> parts = new ArrayList<>(contentNode.size());
        for (JsonNode part : contentNode) {
            if (part.isTextual()) {
                parts.add(new CanonicalContentPart.TextPart(part.asText()));
                continue;
            }
            if (!part.isObject()) {
                continue;
            }
            String type = part.path("type").asText("text");
            switch (type) {
                case "text" -> parts.add(new CanonicalContentPart.TextPart(part.path("text").asText("")));
                case "refusal" -> parts.add(new CanonicalContentPart.RefusalPart(part.path("refusal").asText("")));
                case "image_url" -> {
                    JsonNode imgObj = part.get("image_url");
                    String url = (imgObj != null && imgObj.isObject())
                            ? imgObj.path("url").asText("")
                            : (imgObj != null && imgObj.isTextual() ? imgObj.asText() : "");
                    ImageDetail detail = (imgObj != null && imgObj.isObject())
                            ? ImageDetail.parseOrDefault(CodecSupport.asNullableText(imgObj, "detail"), ImageDetail.AUTO)
                            : ImageDetail.AUTO;
                    parts.add(new CanonicalContentPart.ImagePart(CodecSupport.parseImageUrlOrDataUri(url), detail, null));
                }
                default -> unsupported.add(UnsupportedFeatureFlag.degradable("content_part:" + type,
                        "OpenAI Chat content part type '" + type + "' is not supported for translation"));
            }
        }
        return parts;
    }

    private CanonicalContentPart.ToolCallPart decodeToolCall(JsonNode tc) {
        String id = CodecSupport.asNullableText(tc, "id");
        JsonNode fn = tc.path("function");
        String name = fn.path("name").asText("");
        String rawArgs = fn.path("arguments").asText("{}");
        String thoughtSig = null;
        JsonNode extraGoogle = tc.path("extra_content").path("google");
        if (extraGoogle.isObject() && extraGoogle.hasNonNull("thought_signature")) {
            thoughtSig = extraGoogle.get("thought_signature").asText();
        }
        ProtocolKind origin = (thoughtSig != null) ? ProtocolKind.GEMINI_GENERATE_CONTENT : ProtocolKind.OPENAI_CHAT;
        return new CanonicalContentPart.ToolCallPart(
                id, null, name, CodecSupport.parseArgumentsToObject(mapper, rawArgs), rawArgs, thoughtSig, origin
        );
    }

    private List<CanonicalToolDefinition> decodeTools(JsonNode toolsNode, List<UnsupportedFeatureFlag> unsupported) {
        if (toolsNode == null || !toolsNode.isArray()) {
            return List.of();
        }
        List<CanonicalToolDefinition> out = new ArrayList<>(toolsNode.size());
        for (JsonNode tool : toolsNode) {
            String type = tool.path("type").asText("function");
            if (!"function".equals(type)) {
                unsupported.add(UnsupportedFeatureFlag.degradable("builtin_tool:" + type, "Only custom function tools are translatable"));
                continue;
            }
            JsonNode fn = tool.path("function");
            out.add(new CanonicalToolDefinition(
                    fn.path("name").asText(""),
                    CodecSupport.asNullableText(fn, "description"),
                    fn.get("parameters"),
                    CodecSupport.asNullableBoolean(fn, "strict"),
                    null
            ));
        }
        return out;
    }

    private CanonicalToolChoice decodeToolChoice(JsonNode node, Boolean parallel) {
        if (node == null || node.isNull()) {
            return new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.OMITTED, null, parallel);
        }
        if (node.isTextual()) {
            return switch (node.asText().toLowerCase(Locale.ROOT)) {
                case "none" -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.NONE, null, parallel);
                case "required" -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.REQUIRED, null, parallel);
                default -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.AUTO, null, parallel);
            };
        }
        if (node.isObject() && "function".equals(node.path("type").asText())) {
            String name = node.path("function").path("name").asText(null);
            return new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.SPECIFIC, name, parallel);
        }
        return new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.AUTO, null, parallel);
    }

    private CanonicalResponseFormat decodeResponseFormat(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String type = node.path("type").asText("text");
        return switch (type) {
            case "json_object" -> new CanonicalResponseFormat.JsonObjectFormat();
            case "json_schema" -> {
                JsonNode js = node.path("json_schema");
                yield new CanonicalResponseFormat.JsonSchemaFormat(
                        CodecSupport.asNullableText(js, "name"),
                        CodecSupport.asNullableText(js, "description"),
                        js.get("schema"),
                        CodecSupport.asNullableBoolean(js, "strict")
                );
            }
            default -> new CanonicalResponseFormat.TextFormat();
        };
    }

    private static CanonicalThinkingConfig decodeThinking(String effortStr) {
        if (effortStr == null || effortStr.isBlank()) {
            return CanonicalThinkingConfig.omitted();
        }
        CanonicalThinkingConfig.ThinkingEffort effort = CanonicalThinkingConfig.ThinkingEffort.parseOrOmitted(effortStr);
        if (effort == CanonicalThinkingConfig.ThinkingEffort.NONE) {
            return new CanonicalThinkingConfig(CanonicalThinkingConfig.ThinkingMode.DISABLED, effort, null, null, null);
        }
        return new CanonicalThinkingConfig(CanonicalThinkingConfig.ThinkingMode.ENABLED, effort, null, null, null);
    }

    private static List<String> decodeStopSequences(JsonNode stopNode) {
        if (stopNode == null || stopNode.isNull()) {
            return List.of();
        }
        if (stopNode.isTextual()) {
            return List.of(stopNode.asText());
        }
        if (stopNode.isArray()) {
            List<String> out = new ArrayList<>(stopNode.size());
            for (JsonNode s : stopNode) {
                if (s.isTextual()) {
                    out.add(s.asText());
                }
            }
            return out;
        }
        return List.of();
    }

    private static String extractPlainText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                if (item.isObject() && item.hasNonNull("text")) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(item.get("text").asText());
                } else if (item.isTextual()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(item.asText());
                }
            }
            return sb.toString();
        }
        return node.toString();
    }

    private CanonicalContentPart.ToolCallPart decodeLegacyFunctionCall(JsonNode fn) {
        String name = fn.path("name").asText("");
        String rawArgs = fn.path("arguments").asText("{}");
        return new CanonicalContentPart.ToolCallPart(
                null, null, name, CodecSupport.parseArgumentsToObject(mapper, rawArgs), rawArgs, null, ProtocolKind.OPENAI_CHAT
        );
    }

    private record ExtractedReasoning(String text, String wireField) {
    }

    private static ExtractedReasoning extractReasoning(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String rc = CodecSupport.asNullableText(node, "reasoning_content");
        if (rc != null && !rc.isEmpty()) {
            return new ExtractedReasoning(rc, "reasoning_content");
        }
        String r = CodecSupport.asNullableText(node, "reasoning");
        if (r != null && !r.isEmpty()) {
            return new ExtractedReasoning(r, "reasoning");
        }
        JsonNode details = node.get("reasoning_details");
        if (details != null && details.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : details) {
                if (!item.isObject()) {
                    continue;
                }
                String piece = CodecSupport.asNullableText(item, "text");
                if (piece == null || piece.isEmpty()) {
                    piece = CodecSupport.asNullableText(item, "summary");
                }
                if (piece != null && !piece.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(piece);
                }
            }
            if (!sb.isEmpty()) {
                return new ExtractedReasoning(sb.toString(), "reasoning_content");
            }
        }
        return null;
    }

    private static String extractReasoningText(JsonNode node) {
        ExtractedReasoning extracted = extractReasoning(node);
        return extracted != null ? extracted.text() : null;
    }

    // ---- Streaming request encoding helpers (zero outbound DOM tree) ----

    private int writeTurn(
            JsonGenerator gen,
            CanonicalTurn turn,
            TranslationPolicy policy,
            ProviderCapabilities caps
    ) throws IOException {
        return switch (turn.role()) {
            case SYSTEM, DEVELOPER -> {
                String content = joinTextParts(turn.parts());
                gen.writeStartObject();
                boolean useDeveloper = turn.role() == CanonicalRole.DEVELOPER && caps.supportsDeveloperRole();
                gen.writeStringField("role", useDeveloper ? "developer" : "system");
                gen.writeStringField("content", content);
                gen.writeEndObject();
                yield 1;
            }
            case USER, TOOL -> writeUserOrToolTurn(gen, turn, policy);
            case ASSISTANT -> {
                writeAssistantTurn(gen, turn, caps);
                yield 1;
            }
        };
    }

    private int writeUserOrToolTurn(
            JsonGenerator gen,
            CanonicalTurn turn,
            TranslationPolicy policy
    ) throws IOException {
        List<CanonicalContentPart> parts = turn.parts();
        // Fast path: single plain text user turn (most common in large multi-turn histories)
        if (parts.size() == 1 && parts.getFirst() instanceof CanonicalContentPart.TextPart t) {
            gen.writeStartObject();
            gen.writeStringField("role", "user");
            gen.writeStringField("content", t.text());
            gen.writeEndObject();
            return 1;
        }

        int emitted = 0;
        List<CanonicalContentPart> hoistedImages = null;
        boolean hasNonToolPart = false;

        for (CanonicalContentPart p : parts) {
            if (p instanceof CanonicalContentPart.ToolResultPart tr) {
                hoistedImages = writeToolResultMessage(gen, tr, policy, hoistedImages);
                emitted++;
            } else {
                hasNonToolPart = true;
            }
        }

        if (hasNonToolPart || (hoistedImages != null && !hoistedImages.isEmpty())) {
            List<CanonicalContentPart> userParts;
            if (hoistedImages == null || hoistedImages.isEmpty()) {
                if (emitted == 0) {
                    userParts = parts;
                } else {
                    userParts = new ArrayList<>(parts.size());
                    for (CanonicalContentPart p : parts) {
                        if (!(p instanceof CanonicalContentPart.ToolResultPart)) {
                            userParts.add(p);
                        }
                    }
                }
            } else {
                userParts = new ArrayList<>(hoistedImages.size() + parts.size());
                userParts.addAll(hoistedImages);
                for (CanonicalContentPart p : parts) {
                    if (!(p instanceof CanonicalContentPart.ToolResultPart)) {
                        userParts.add(p);
                    }
                }
            }
            gen.writeStartObject();
            gen.writeStringField("role", "user");
            gen.writeFieldName("content");
            writeUserParts(gen, userParts);
            gen.writeEndObject();
            emitted++;
        }
        return emitted;
    }

    private List<CanonicalContentPart> writeToolResultMessage(
            JsonGenerator gen,
            CanonicalContentPart.ToolResultPart tr,
            TranslationPolicy policy,
            List<CanonicalContentPart> hoistedImages
    ) throws IOException {
        List<CanonicalContentPart> children = tr.content();
        String contentText;
        if (children.isEmpty()) {
            contentText = "";
        } else if (children.size() == 1 && children.getFirst() instanceof CanonicalContentPart.TextPart t) {
            contentText = (tr.isError() && !t.text().isEmpty()) ? "Error: " + t.text() : t.text();
        } else {
            StringBuilder textOut = new StringBuilder();
            for (CanonicalContentPart child : children) {
                if (child instanceof CanonicalContentPart.TextPart t) {
                    if (!textOut.isEmpty()) {
                        textOut.append('\n');
                    }
                    textOut.append(t.text());
                } else if (child instanceof CanonicalContentPart.ImagePart img) {
                    if (policy == TranslationPolicy.STRICT) {
                        throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                                "OpenAI Chat 'tool' messages do not support image parts in STRICT mode");
                    }
                    if (hoistedImages == null) {
                        hoistedImages = new ArrayList<>(4);
                    }
                    hoistedImages.add(new CanonicalContentPart.TextPart(
                            "[Image returned by tool " + (tr.toolName() != null ? tr.toolName() : "unknown")
                                    + " (" + tr.callId() + ")]"));
                    hoistedImages.add(img);
                }
            }
            contentText = (tr.isError() && !textOut.isEmpty()) ? "Error: " + textOut : textOut.toString();
        }

        gen.writeStartObject();
        gen.writeStringField("role", "tool");
        gen.writeStringField("tool_call_id", tr.callId() != null ? tr.callId() : "");
        gen.writeStringField("content", contentText);
        gen.writeEndObject();
        return hoistedImages;
    }

    private void writeAssistantTurn(
            JsonGenerator gen,
            CanonicalTurn turn,
            ProviderCapabilities caps
    ) throws IOException {
        List<CanonicalContentPart> parts = turn.parts();
        // Fast path: single plain text assistant turn (avoids StringBuilder allocation & copy)
        if (parts.size() == 1 && parts.getFirst() instanceof CanonicalContentPart.TextPart t) {
            gen.writeStartObject();
            gen.writeStringField("role", "assistant");
            gen.writeStringField("content", t.text());
            gen.writeEndObject();
            return;
        }

        String singleText = null;
        StringBuilder textSb = null;
        String singleThinking = null;
        StringBuilder thinkingSb = null;
        String observedThinkingField = null;
        String singleRefusal = null;
        StringBuilder refusalSb = null;
        boolean hasToolCalls = false;

        for (CanonicalContentPart p : parts) {
            switch (p) {
                case CanonicalContentPart.TextPart t -> {
                    if (!t.text().isEmpty()) {
                        if (singleText == null && textSb == null) {
                            singleText = t.text();
                        } else {
                            if (textSb == null) {
                                textSb = new StringBuilder(singleText.length() + t.text().length());
                                textSb.append(singleText);
                                singleText = null;
                            }
                            textSb.append(t.text());
                        }
                    }
                }
                case CanonicalContentPart.RefusalPart r -> {
                    if (!r.refusal().isEmpty()) {
                        if (singleRefusal == null && refusalSb == null) {
                            singleRefusal = r.refusal();
                        } else {
                            if (refusalSb == null) {
                                refusalSb = new StringBuilder(singleRefusal.length() + r.refusal().length());
                                refusalSb.append(singleRefusal);
                                singleRefusal = null;
                            }
                            refusalSb.append(r.refusal());
                        }
                    }
                }
                case CanonicalContentPart.ToolCallPart ignored -> hasToolCalls = true;
                case CanonicalContentPart.ThinkingPart th -> {
                    if (!caps.openAiNativeProvider() && !th.text().isEmpty()) {
                        String targetField = resolveTargetReasoningField(caps, th);
                        if (targetField != null) {
                            if (observedThinkingField == null) {
                                observedThinkingField = targetField;
                            }
                            if (singleThinking == null && thinkingSb == null) {
                                singleThinking = th.text();
                            } else {
                                if (thinkingSb == null) {
                                    thinkingSb = new StringBuilder(singleThinking.length() + th.text().length() + 1);
                                    thinkingSb.append(singleThinking);
                                    singleThinking = null;
                                }
                                thinkingSb.append('\n').append(th.text());
                            }
                        }
                    }
                }
                case CanonicalContentPart.RedactedThinkingPart ignored -> {}
                case CanonicalContentPart.ImagePart ignored -> {}
                case CanonicalContentPart.ToolResultPart ignored -> {}
            }
        }

        String finalText = textSb != null ? textSb.toString() : singleText;
        String finalThinking = thinkingSb != null ? thinkingSb.toString() : singleThinking;
        String finalRefusal = refusalSb != null ? refusalSb.toString() : singleRefusal;

        gen.writeStartObject();
        gen.writeStringField("role", "assistant");
        if (finalText != null && !finalText.isEmpty()) {
            gen.writeStringField("content", finalText);
        } else if (hasToolCalls || (finalRefusal != null && !finalRefusal.isEmpty())) {
            gen.writeNullField("content");
        } else {
            gen.writeStringField("content", "");
        }
        if (observedThinkingField != null && finalThinking != null && !finalThinking.isEmpty()) {
            gen.writeStringField(observedThinkingField, finalThinking);
        }
        if (finalRefusal != null && !finalRefusal.isEmpty()) {
            gen.writeStringField("refusal", finalRefusal);
        }
        if (hasToolCalls) {
            gen.writeArrayFieldStart("tool_calls");
            for (CanonicalContentPart p : parts) {
                if (p instanceof CanonicalContentPart.ToolCallPart tc) {
                    writeToolCall(gen, tc);
                }
            }
            gen.writeEndArray();
        }
        gen.writeEndObject();
    }

    private static String joinTextParts(List<CanonicalContentPart> parts) {
        String single = null;
        StringBuilder sb = null;
        for (CanonicalContentPart p : parts) {
            if (p instanceof CanonicalContentPart.TextPart t && !t.text().isEmpty()) {
                if (single == null && sb == null) {
                    single = t.text();
                } else {
                    if (sb == null) {
                        sb = new StringBuilder(single.length() + t.text().length() + 1);
                        sb.append(single);
                        single = null;
                    }
                    sb.append('\n').append(t.text());
                }
            }
        }
        return sb != null ? sb.toString() : (single != null ? single : "");
    }

    private static String resolveTargetReasoningField(
            ProviderCapabilities caps,
            CanonicalContentPart.ThinkingPart th
    ) {
        if (caps.interleavedReasoningField() != null && !caps.interleavedReasoningField().isBlank()) {
            return caps.interleavedReasoningField();
        }
        if (th.wireField() != null && !th.wireField().isBlank()) {
            return th.wireField();
        }
        return null;
    }

    private void writeUserParts(JsonGenerator gen, List<CanonicalContentPart> parts) throws IOException {
        if (parts.size() == 1 && parts.getFirst() instanceof CanonicalContentPart.TextPart t) {
            gen.writeString(t.text());
            return;
        }
        gen.writeStartArray();
        for (CanonicalContentPart p : parts) {
            if (p instanceof CanonicalContentPart.TextPart t) {
                gen.writeStartObject();
                gen.writeStringField("type", "text");
                gen.writeStringField("text", t.text());
                gen.writeEndObject();
            } else if (p instanceof CanonicalContentPart.ImagePart img) {
                gen.writeStartObject();
                gen.writeStringField("type", "image_url");
                gen.writeObjectFieldStart("image_url");
                switch (img.source()) {
                    case ImageSource.Base64Image b64 -> gen.writeStringField("url", b64.toDataUri());
                    case ImageSource.UrlImage u -> gen.writeStringField("url", u.url());
                    case ImageSource.FileRefImage ref -> throw new HyperLlmException(
                            ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                            "OpenAI Chat does not accept file references: " + ref.fileRef());
                }
                if (img.detail() != null && img.detail() != ImageDetail.AUTO) {
                    gen.writeStringField("detail", img.detail() == ImageDetail.ORIGINAL ? "high" : img.detail().toOpenAiValue());
                }
                gen.writeEndObject();
                gen.writeEndObject();
            }
        }
        gen.writeEndArray();
    }

    private void writeToolCall(JsonGenerator gen, CanonicalContentPart.ToolCallPart tc) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("id", tc.callId() != null && !tc.callId().isBlank() ? tc.callId() : CodecSupport.generateId("call_"));
        gen.writeStringField("type", "function");
        gen.writeObjectFieldStart("function");
        gen.writeStringField("name", tc.name());
        gen.writeStringField("arguments", CodecSupport.serializeArguments(mapper, tc));
        gen.writeEndObject();
        if (tc.thoughtSignature() != null && !tc.thoughtSignature().isBlank()) {
            gen.writeObjectFieldStart("extra_content");
            gen.writeObjectFieldStart("google");
            gen.writeStringField("thought_signature", tc.thoughtSignature());
            gen.writeEndObject();
            gen.writeEndObject();
        }
        gen.writeEndObject();
    }

    private ObjectNode encodeToolCall(CanonicalContentPart.ToolCallPart tc) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", tc.callId() != null && !tc.callId().isBlank() ? tc.callId() : CodecSupport.generateId("call_"));
        node.put("type", "function");
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", tc.name());
        fn.put("arguments", CodecSupport.serializeArguments(mapper, tc));
        node.set("function", fn);
        if (tc.thoughtSignature() != null && !tc.thoughtSignature().isBlank()) {
            ObjectNode google = mapper.createObjectNode();
            google.put("thought_signature", tc.thoughtSignature());
            ObjectNode extra = mapper.createObjectNode();
            extra.set("google", google);
            node.set("extra_content", extra);
        }
        return node;
    }

    private void writeGenerationConfig(
            JsonGenerator gen,
            CanonicalRequest req,
            ProviderCapabilities caps,
            TranslationPolicy policy
    ) throws IOException {
        CanonicalGenerationConfig g = req.generation();
        if (g.maxOutputTokens() != null && g.maxOutputTokens() > 0) {
            gen.writeNumberField(caps.useMaxCompletionTokens() ? "max_completion_tokens" : "max_tokens", g.maxOutputTokens());
        }
        boolean allowSampling = !(caps.forbidsSamplingWithReasoning() && policy != TranslationPolicy.STRICT);
        if (allowSampling) {
            if (g.temperature() != null) {
                gen.writeNumberField("temperature", g.temperature());
            }
            if (g.topP() != null) {
                gen.writeNumberField("top_p", g.topP());
            }
        }
        if (!g.stopSequences().isEmpty()) {
            gen.writeArrayFieldStart("stop");
            int limit = Math.min(g.stopSequences().size(), 4);
            for (int i = 0; i < limit; i++) {
                gen.writeString(g.stopSequences().get(i));
            }
            gen.writeEndArray();
        }
        if (g.seed() != null) {
            gen.writeNumberField("seed", g.seed());
        }
        if (g.presencePenalty() != null) {
            gen.writeNumberField("presence_penalty", g.presencePenalty());
        }
        if (g.frequencyPenalty() != null) {
            gen.writeNumberField("frequency_penalty", g.frequencyPenalty());
        }
        if (g.candidateCount() != null && g.candidateCount() > 1) {
            gen.writeNumberField("n", g.candidateCount());
        }
    }

    private void writeTools(JsonGenerator gen, CanonicalRequest req, ProviderCapabilities caps) throws IOException {
        if (req.tools().isEmpty()) {
            return;
        }
        gen.writeArrayFieldStart("tools");
        for (CanonicalToolDefinition tool : req.tools()) {
            gen.writeStartObject();
            gen.writeStringField("type", "function");
            gen.writeObjectFieldStart("function");
            gen.writeStringField("name", tool.name());
            if (tool.description() != null) {
                gen.writeStringField("description", tool.description());
            }
            gen.writeFieldName("parameters");
            if (tool.parametersSchema() != null && tool.parametersSchema().isObject()) {
                CodecSupport.writeJsonNode(gen, mapper, tool.parametersSchema());
            } else {
                CodecSupport.writeEmptyObjectSchema(gen);
            }
            if (tool.strict() != null && caps.supportsStrictToolSchema()) {
                gen.writeBooleanField("strict", tool.strict());
            }
            gen.writeEndObject();
            gen.writeEndObject();
        }
        gen.writeEndArray();

        CanonicalToolChoice tc = req.toolChoice();
        switch (tc.mode()) {
            case NONE -> gen.writeStringField("tool_choice", "none");
            case AUTO -> gen.writeStringField("tool_choice", "auto");
            case REQUIRED -> gen.writeStringField("tool_choice", "required");
            case SPECIFIC -> {
                gen.writeObjectFieldStart("tool_choice");
                gen.writeStringField("type", "function");
                gen.writeObjectFieldStart("function");
                gen.writeStringField("name", tc.specificToolName());
                gen.writeEndObject();
                gen.writeEndObject();
            }
            case OMITTED -> {}
        }
        if (tc.parallelToolCalls() != null) {
            gen.writeBooleanField("parallel_tool_calls", tc.parallelToolCalls());
        }
    }

    private void writeResponseFormat(JsonGenerator gen, CanonicalResponseFormat format) throws IOException {
        if (format == null) {
            return;
        }
        gen.writeObjectFieldStart("response_format");
        switch (format) {
            case CanonicalResponseFormat.TextFormat ignored -> gen.writeStringField("type", "text");
            case CanonicalResponseFormat.JsonObjectFormat ignored -> gen.writeStringField("type", "json_object");
            case CanonicalResponseFormat.JsonSchemaFormat js -> {
                gen.writeStringField("type", "json_schema");
                gen.writeObjectFieldStart("json_schema");
                gen.writeStringField("name", js.name());
                if (js.description() != null) {
                    gen.writeStringField("description", js.description());
                }
                if (js.schema() != null) {
                    gen.writeFieldName("schema");
                    CodecSupport.writeJsonNode(gen, mapper, js.schema());
                }
                if (js.strict() != null) {
                    gen.writeBooleanField("strict", js.strict());
                }
                gen.writeEndObject();
            }
        }
        gen.writeEndObject();
    }

    // ---- Usage & stop reason helpers ----

    static Optional<TokenUsage> parseUsageNode(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Optional.empty();
        }
        int prompt = Math.max(0, usage.path("prompt_tokens").asInt(0));
        int completion = Math.max(0, usage.path("completion_tokens").asInt(0));
        int cachedRead = Math.max(0, usage.path("prompt_tokens_details").path("cached_tokens").asInt(0));
        int cachedWrite = Math.max(0, usage.path("prompt_tokens_details").path("cache_write_tokens").asInt(0));
        int reasoning = Math.max(0, usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0));
        return Optional.of(TokenUsage.fromOpenAiInclusive(prompt, cachedRead, cachedWrite, completion, reasoning));
    }

    private ObjectNode encodeUsageNode(TokenUsage usage) {
        TokenUsage u = usage != null ? usage : TokenUsage.empty();
        ObjectNode node = mapper.createObjectNode();
        node.put("prompt_tokens", u.totalInputTokens());
        node.put("completion_tokens", u.totalOutputTokens());
        node.put("total_tokens", u.totalTokens());
        ObjectNode promptDetails = mapper.createObjectNode();
        promptDetails.put("cached_tokens", u.cachedReadTokens());
        if (u.cachedWriteTokens() > 0) {
            promptDetails.put("cache_write_tokens", u.cachedWriteTokens());
        }
        node.set("prompt_tokens_details", promptDetails);
        ObjectNode completionDetails = mapper.createObjectNode();
        completionDetails.put("reasoning_tokens", u.reasoningTokens());
        node.set("completion_tokens_details", completionDetails);
        return node;
    }

    private static CanonicalStopReason mapFinishReason(String reason, List<CanonicalContentPart> parts) {
        boolean hasRefusal = parts.stream().anyMatch(p -> p instanceof CanonicalContentPart.RefusalPart);
        boolean hasTools = parts.stream().anyMatch(p -> p instanceof CanonicalContentPart.ToolCallPart);
        if (reason == null) {
            return hasTools ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN;
        }
        return switch (reason) {
            case "tool_calls", "function_call" -> CanonicalStopReason.TOOL_CALLS;
            case "length" -> CanonicalStopReason.MAX_TOKENS;
            case "content_filter" -> CanonicalStopReason.CONTENT_FILTER;
            default -> hasRefusal ? CanonicalStopReason.REFUSAL
                    : (hasTools ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN);
        };
    }

    private static String encodeFinishReason(CanonicalStopReason reason, boolean hasToolCalls) {
        if (reason == null) {
            return hasToolCalls ? "tool_calls" : "stop";
        }
        return switch (reason) {
            case TOOL_CALLS -> "tool_calls";
            case MAX_TOKENS -> "length";
            case CONTENT_FILTER -> "content_filter";
            case END_TURN, STOP_SEQUENCE, REFUSAL, MALFORMED_TOOL_CALL, ERROR ->
                    hasToolCalls ? "tool_calls" : "stop";
        };
    }

    // ---- Streaming Decoder ----

    private final class OpenAiChatStreamDecoder implements UpstreamStreamDecoder {

        private final String virtualModel;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private boolean started;
        private boolean finished;
        private int nextBlockIndex;
        private int activeBlockIndex = -1;
        private BlockKind activeBlockKind;
        private final Map<Integer, ToolSlot> toolSlots = new java.util.LinkedHashMap<>();
        private final Map<String, ToolSlot> toolSlotsById = new HashMap<>();
        private Integer lastToolSlotKey;
        private int syntheticSlotKey = -1;
        private CanonicalStopReason pendingStopReason;
        private boolean sawToolCall;
        private boolean sawRefusal;

        private record ToolSlot(int blockIndex, String callId) {
        }

        private OpenAiChatStreamDecoder(String virtualModel) {
            this.virtualModel = virtualModel;
        }

        @Override
        public List<CanonicalStreamEvent> feed(byte[] upstreamChunk) {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            splitter.push(upstreamChunk, event -> processEvent(event, out));
            return out;
        }

        @Override
        public List<CanonicalStreamEvent> complete() {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            splitter.flushRemainder(event -> processEvent(event, out));
            emitFinalClose(out);
            return out;
        }

        private void processEvent(SseEventSplitter.SseEvent event, List<CanonicalStreamEvent> out) {
            if (!event.hasData()) {
                return;
            }
            if (SseEventSplitter.isDoneMarker(event)) {
                emitFinalClose(out);
                return;
            }
            TextFrame fastFrame = scanTextFrame(event.dataSource(), event.dataOffset(), event.dataLength());
            if (fastFrame != null) {
                emitTextFrame(fastFrame, out);
                return;
            }
            try {
                JsonNode root = CodecSupport.readTreeBytes(
                        mapper, event.dataSource(), event.dataOffset(), event.dataLength());
                if (!root.isObject()) {
                    return;
                }
                if (!started) {
                    String id = root.path("id").asText("");
                    if (id.isBlank()) {
                        id = CodecSupport.generateId("chatcmpl-");
                    }
                    long created = root.path("created").asLong(CodecSupport.nowEpochSeconds());
                    String model = (virtualModel != null && !virtualModel.isBlank())
                            ? virtualModel : root.path("model").asText("");
                    out.add(new CanonicalStreamEvent.MessageStart(id, model, created));
                    started = true;
                }

                JsonNode choices = root.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty()) {
                    JsonNode choice0 = choices.get(0);
                    JsonNode delta = choice0.get("delta");
                    if (delta != null && delta.isObject()) {
                        String reasoning = extractReasoningText(delta);
                        if (reasoning != null && !reasoning.isEmpty()) {
                            int idx = ensureActiveBlock(BlockKind.THINKING, out);
                            out.add(new CanonicalStreamEvent.ThinkingDelta(idx, reasoning));
                        }
                        String content = CodecSupport.asNullableText(delta, "content");
                        if (content != null && !content.isEmpty()) {
                            int idx = ensureActiveBlock(BlockKind.TEXT, out);
                            out.add(new CanonicalStreamEvent.TextDelta(idx, content));
                        }
                        String refusal = CodecSupport.asNullableText(delta, "refusal");
                        if (refusal != null && !refusal.isEmpty()) {
                            sawRefusal = true;
                            int idx = ensureActiveBlock(BlockKind.REFUSAL, out);
                            out.add(new CanonicalStreamEvent.RefusalDelta(idx, refusal));
                        }
                        JsonNode toolCalls = delta.get("tool_calls");
                        if (toolCalls != null && toolCalls.isArray()) {
                            closeActiveNonToolBlock(out);
                            for (JsonNode tc : toolCalls) {
                                handleToolCallDelta(tc, out);
                            }
                        }
                        JsonNode legacyFnCall = delta.get("function_call");
                        if ((toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty())
                                && legacyFnCall != null && legacyFnCall.isObject()) {
                            closeActiveNonToolBlock(out);
                            ObjectNode wrapped = mapper.createObjectNode();
                            wrapped.put("index", 0);
                            wrapped.set("function", legacyFnCall);
                            handleToolCallDelta(wrapped, out);
                        }
                    }
                    String finishReason = CodecSupport.asNullableText(choice0, "finish_reason");
                    if (finishReason != null && !finishReason.isBlank()) {
                        pendingStopReason = switch (finishReason) {
                            case "tool_calls", "function_call" -> CanonicalStopReason.TOOL_CALLS;
                            case "length" -> CanonicalStopReason.MAX_TOKENS;
                            case "content_filter" -> CanonicalStopReason.CONTENT_FILTER;
                            default -> sawRefusal ? CanonicalStopReason.REFUSAL
                                    : (sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN);
                        };
                    }
                }

                Optional<TokenUsage> usage = parseUsageNode(root.get("usage"));
                if (usage.isPresent()) {
                    out.add(new CanonicalStreamEvent.UsageUpdate(usage.get(), true));
                    if (pendingStopReason != null) {
                        emitFinalClose(out);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private record ChoiceFrame(String reasoning, String content, String refusal, String finishReason) {
        }

        private record TextFrame(String id, String model, Long created, String reasoning,
                                 String content, String refusal, String finishReason) {
        }

        /**
         * Tree-free fast path for the per-token frame shape
         * ({@code choices[0].delta.{content,reasoning_content,refusal}} plus an optional
         * {@code finish_reason}). Returns {@code null} for every other shape - tool calls, usage chunks,
         * role chunks, malformed input - so the caller keeps the generic {@link JsonNode} path.
         */
        private TextFrame scanTextFrame(byte[] source, int offset, int length) {
            try (JsonParser parser = mapper.getFactory().createParser(source, offset, length)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return null;
                }
                String id = null;
                String model = null;
                Long created = null;
                String reasoning = null;
                String content = null;
                String refusal = null;
                String finishReason = null;
                boolean sawChoices = false;
                JsonToken token;
                while ((token = parser.nextToken()) == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    switch (field) {
                        case "id" -> {
                            if (value != JsonToken.VALUE_STRING) {
                                return null;
                            }
                            id = parser.getText();
                        }
                        case "model" -> {
                            if (value != JsonToken.VALUE_STRING) {
                                return null;
                            }
                            model = parser.getText();
                        }
                        case "created" -> {
                            if (!value.isNumeric()) {
                                return null;
                            }
                            created = parser.getLongValue();
                        }
                        case "object", "system_fingerprint", "service_tier" -> parser.skipChildren();
                        case "choices" -> {
                            if (value != JsonToken.START_ARRAY || sawChoices) {
                                return null;
                            }
                            sawChoices = true;
                            if (parser.nextToken() != JsonToken.START_OBJECT) {
                                return null;
                            }
                            ChoiceFrame choice = scanChoice(parser);
                            if (choice == null) {
                                return null;
                            }
                            reasoning = choice.reasoning();
                            content = choice.content();
                            refusal = choice.refusal();
                            finishReason = choice.finishReason();
                            if (parser.nextToken() != JsonToken.END_ARRAY) {
                                return null;
                            }
                        }
                        default -> {
                            return null;
                        }
                    }
                }
                if (token != JsonToken.END_OBJECT || !sawChoices) {
                    return null;
                }
                return new TextFrame(id, model, created, reasoning, content, refusal, finishReason);
            } catch (Exception ex) {
                return null;
            }
        }

        private ChoiceFrame scanChoice(JsonParser parser) throws IOException {
            String reasoning = null;
            String content = null;
            String refusal = null;
            String finishReason = null;
            JsonToken token;
            while ((token = parser.nextToken()) == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "index" -> {
                        if (!value.isNumeric()) {
                            return null;
                        }
                    }
                    case "finish_reason" -> {
                        if (value == JsonToken.VALUE_NULL) {
                            finishReason = null;
                        } else if (value == JsonToken.VALUE_STRING) {
                            finishReason = parser.getText();
                        } else {
                            return null;
                        }
                    }
                    case "delta" -> {
                        if (value != JsonToken.START_OBJECT) {
                            return null;
                        }
                        JsonToken deltaToken;
                        while ((deltaToken = parser.nextToken()) == JsonToken.FIELD_NAME) {
                            String deltaField = parser.currentName();
                            JsonToken deltaValue = parser.nextToken();
                            switch (deltaField) {
                                case "content" -> {
                                    if (deltaValue == JsonToken.VALUE_NULL) {
                                        content = null;
                                    } else if (deltaValue == JsonToken.VALUE_STRING) {
                                        content = parser.getText();
                                    } else {
                                        return null;
                                    }
                                }
                                case "reasoning_content" -> {
                                    if (deltaValue == JsonToken.VALUE_NULL) {
                                        reasoning = null;
                                    } else if (deltaValue == JsonToken.VALUE_STRING) {
                                        reasoning = parser.getText();
                                    } else {
                                        return null;
                                    }
                                }
                                case "refusal" -> {
                                    if (deltaValue == JsonToken.VALUE_NULL) {
                                        refusal = null;
                                    } else if (deltaValue == JsonToken.VALUE_STRING) {
                                        refusal = parser.getText();
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
            if (token != JsonToken.END_OBJECT) {
                return null;
            }
            return new ChoiceFrame(reasoning, content, refusal, finishReason);
        }

        private void emitTextFrame(TextFrame frame, List<CanonicalStreamEvent> out) {
            if (!started) {
                String id = frame.id();
                if (id == null || id.isBlank()) {
                    id = CodecSupport.generateId("chatcmpl-");
                }
                long created = frame.created() != null ? frame.created() : CodecSupport.nowEpochSeconds();
                String model = (virtualModel != null && !virtualModel.isBlank())
                        ? virtualModel : (frame.model() != null ? frame.model() : "");
                out.add(new CanonicalStreamEvent.MessageStart(id, model, created));
                started = true;
            }
            String reasoning = frame.reasoning();
            if (reasoning != null && !reasoning.isEmpty()) {
                int idx = ensureActiveBlock(BlockKind.THINKING, out);
                out.add(new CanonicalStreamEvent.ThinkingDelta(idx, reasoning));
            }
            String content = frame.content();
            if (content != null && !content.isEmpty()) {
                int idx = ensureActiveBlock(BlockKind.TEXT, out);
                out.add(new CanonicalStreamEvent.TextDelta(idx, content));
            }
            String refusal = frame.refusal();
            if (refusal != null && !refusal.isEmpty()) {
                sawRefusal = true;
                int idx = ensureActiveBlock(BlockKind.REFUSAL, out);
                out.add(new CanonicalStreamEvent.RefusalDelta(idx, refusal));
            }
            String finishReason = frame.finishReason();
            if (finishReason != null && !finishReason.isBlank()) {
                pendingStopReason = switch (finishReason) {
                    case "tool_calls", "function_call" -> CanonicalStopReason.TOOL_CALLS;
                    case "length" -> CanonicalStopReason.MAX_TOKENS;
                    case "content_filter" -> CanonicalStopReason.CONTENT_FILTER;
                    default -> sawRefusal ? CanonicalStopReason.REFUSAL
                            : (sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN);
                };
            }
        }

        private void handleToolCallDelta(JsonNode tc, List<CanonicalStreamEvent> out) {
            boolean hasIndex = tc.hasNonNull("index") && tc.get("index").isIntegralNumber();
            String incomingId = CodecSupport.asNullableText(tc, "id");
            int slotKey;
            ToolSlot slot = null;

            if (hasIndex) {
                int tcIndex = tc.get("index").asInt();
                ToolSlot existing = toolSlots.get(tcIndex);
                if (existing != null && incomingId != null && !incomingId.isBlank()
                        && !incomingId.equals(existing.callId())) {
                    // Provider reused index 0 for a distinct parallel tool call ID
                    slot = toolSlotsById.get(incomingId);
                    slotKey = syntheticSlotKey--;
                } else {
                    slot = existing;
                    slotKey = tcIndex;
                }
            } else if (incomingId != null && !incomingId.isBlank()) {
                slot = toolSlotsById.get(incomingId);
                slotKey = syntheticSlotKey--;
            } else if (lastToolSlotKey != null) {
                slotKey = lastToolSlotKey;
                slot = toolSlots.get(slotKey);
            } else {
                slotKey = syntheticSlotKey--;
            }

            if (slot == null) {
                String callId = (incomingId != null && !incomingId.isBlank())
                        ? incomingId : CodecSupport.generateId("call_");
                String name = tc.path("function").path("name").asText("");
                String thoughtSig = null;
                JsonNode g = tc.path("extra_content").path("google");
                if (g.isObject() && g.hasNonNull("thought_signature")) {
                    thoughtSig = g.get("thought_signature").asText();
                }
                int blockIdx = nextBlockIndex++;
                slot = new ToolSlot(blockIdx, callId);
                toolSlots.put(slotKey, slot);
                toolSlotsById.put(callId, slot);
                lastToolSlotKey = slotKey;
                sawToolCall = true;
                out.add(new CanonicalStreamEvent.ToolCallStart(
                        blockIdx, callId, null, name, thoughtSig,
                        thoughtSig != null ? ProtocolKind.GEMINI_GENERATE_CONTENT : ProtocolKind.OPENAI_CHAT
                ));
            }
            String argDelta = CodecSupport.asNullableText(tc.path("function"), "arguments");
            if (argDelta != null && !argDelta.isEmpty()) {
                out.add(new CanonicalStreamEvent.ToolCallArgsDelta(slot.blockIndex(), slot.callId(), argDelta));
            }
        }

        private int ensureActiveBlock(BlockKind kind, List<CanonicalStreamEvent> out) {
            if (activeBlockIndex >= 0 && activeBlockKind == kind) {
                return activeBlockIndex;
            }
            closeActiveNonToolBlock(out);
            activeBlockIndex = nextBlockIndex++;
            activeBlockKind = kind;
            out.add(new CanonicalStreamEvent.BlockStart(activeBlockIndex, kind));
            return activeBlockIndex;
        }

        private void closeActiveNonToolBlock(List<CanonicalStreamEvent> out) {
            if (activeBlockIndex >= 0) {
                out.add(new CanonicalStreamEvent.BlockStop(activeBlockIndex));
                activeBlockIndex = -1;
                activeBlockKind = null;
            }
        }

        private void emitFinalClose(List<CanonicalStreamEvent> out) {
            if (finished) {
                return;
            }
            finished = true;
            closeActiveNonToolBlock(out);
            for (ToolSlot slot : toolSlots.values()) {
                out.add(new CanonicalStreamEvent.BlockStop(slot.blockIndex()));
            }
            toolSlots.clear();
            CanonicalStopReason reason = pendingStopReason != null
                    ? pendingStopReason
                    : (sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN);
            out.add(new CanonicalStreamEvent.MessageFinish(reason, null));
        }
    }

    // ---- Streaming Encoder ----

    private final class OpenAiChatStreamEncoder implements DownstreamStreamEncoder {

        private final boolean clientRequestedUsage;
        private String responseId = CodecSupport.generateId("chatcmpl-");
        private String model;
        private long created = CodecSupport.nowEpochSeconds();
        private boolean roleSent;
        private final CodecSupport.ResizableByteBuffer frameBuffer = new CodecSupport.ResizableByteBuffer(320);
        private byte[] deltaPrefix;
        private boolean doneSent;
        private int nextToolIdx;
        private final Map<Integer, Integer> blockToToolIndex = new HashMap<>();
        private boolean sawToolCall;
        private TokenUsage latestUsage;

        private OpenAiChatStreamEncoder(StreamRequestMetadata inboundMeta) {
            this.clientRequestedUsage = inboundMeta.clientRequestedUsage();
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
                        this.responseId = start.responseId();
                    }
                    if (start.virtualModel() != null && !start.virtualModel().isBlank()) {
                        this.model = start.virtualModel();
                    }
                    if (start.createdEpochSeconds() > 0) {
                        this.created = start.createdEpochSeconds();
                    }
                    deltaPrefix = null;
                    ensureRoleChunk(frames);
                }
                case CanonicalStreamEvent.TextDelta td -> {
                    ensureRoleChunk(frames);
                    frames.add(buildDeltaFrame("content", td.textDelta()));
                }
                case CanonicalStreamEvent.ThinkingDelta th -> {
                    ensureRoleChunk(frames);
                    frames.add(buildDeltaFrame("reasoning_content", th.thinkingDelta()));
                }
                case CanonicalStreamEvent.RefusalDelta ref -> {
                    ensureRoleChunk(frames);
                    frames.add(buildDeltaFrame("refusal", ref.refusalDelta()));
                }
                case CanonicalStreamEvent.ToolCallStart tcs -> {
                    ensureRoleChunk(frames);
                    sawToolCall = true;
                    int toolIdx = nextToolIdx++;
                    blockToToolIndex.put(tcs.blockIndex(), toolIdx);
                    ObjectNode tcObj = mapper.createObjectNode();
                    tcObj.put("index", toolIdx);
                    tcObj.put("id", tcs.callId() != null ? tcs.callId() : CodecSupport.generateId("call_"));
                    tcObj.put("type", "function");
                    ObjectNode fn = mapper.createObjectNode();
                    fn.put("name", tcs.name());
                    fn.put("arguments", "");
                    tcObj.set("function", fn);
                    if (tcs.thoughtSignature() != null && !tcs.thoughtSignature().isBlank()) {
                        ObjectNode google = mapper.createObjectNode();
                        google.put("thought_signature", tcs.thoughtSignature());
                        ObjectNode extra = mapper.createObjectNode();
                        extra.set("google", google);
                        tcObj.set("extra_content", extra);
                    }
                    ArrayNode arr = mapper.createArrayNode();
                    arr.add(tcObj);
                    ObjectNode delta = mapper.createObjectNode();
                    delta.set("tool_calls", arr);
                    frames.add(buildChunkFrame(delta, null));
                }
                case CanonicalStreamEvent.ToolCallArgsDelta tcad -> {
                    Integer toolIdx = blockToToolIndex.get(tcad.blockIndex());
                    if (toolIdx != null) {
                        ObjectNode tcObj = mapper.createObjectNode();
                        tcObj.put("index", toolIdx);
                        ObjectNode fn = mapper.createObjectNode();
                        fn.put("arguments", tcad.partialJsonDelta());
                        tcObj.set("function", fn);
                        ArrayNode arr = mapper.createArrayNode();
                        arr.add(tcObj);
                        ObjectNode delta = mapper.createObjectNode();
                        delta.set("tool_calls", arr);
                        frames.add(buildChunkFrame(delta, null));
                    }
                }
                case CanonicalStreamEvent.UsageUpdate uu -> {
                    latestUsage = uu.cumulative() ? uu.usage()
                            : (latestUsage == null ? uu.usage() : latestUsage.mergeMax(uu.usage()));
                }
                case CanonicalStreamEvent.MessageFinish mf -> {
                    if (!doneSent) {
                        ensureRoleChunk(frames);
                        frames.add(buildChunkFrame(mapper.createObjectNode(), encodeFinishReason(mf.stopReason(), sawToolCall)));
                        if (clientRequestedUsage && latestUsage != null) {
                            frames.add(buildUsageOnlyFrame(latestUsage));
                        }
                        frames.add(SseEventSplitter.buildDataFrame("[DONE]"));
                        doneSent = true;
                    }
                }
                case CanonicalStreamEvent.StreamError err -> {
                    ObjectNode errorWrap = mapper.createObjectNode();
                    ObjectNode inner = mapper.createObjectNode();
                    inner.put("message", err.message() != null ? err.message() : "Upstream stream error");
                    inner.put("type", err.errorType() != null ? err.errorType() : "server_error");
                    errorWrap.set("error", inner);
                    frames.add(SseEventSplitter.buildDataFrame(CodecSupport.writeJsonString(mapper, errorWrap)));
                }
                case CanonicalStreamEvent.BlockStart ignored -> {}
                case CanonicalStreamEvent.BlockStop ignored -> {}
                case CanonicalStreamEvent.ThinkingSignatureDelta ignored -> {}
                case CanonicalStreamEvent.RedactedThinking ignored -> {}
            }
            return frames;
        }

        @Override
        public List<byte[]> finish() {
            if (doneSent) {
                return List.of();
            }
            List<byte[]> frames = new ArrayList<>();
            ensureRoleChunk(frames);
            frames.add(buildChunkFrame(mapper.createObjectNode(), sawToolCall ? "tool_calls" : "stop"));
            if (clientRequestedUsage && latestUsage != null) {
                frames.add(buildUsageOnlyFrame(latestUsage));
            }
            frames.add(SseEventSplitter.buildDataFrame("[DONE]"));
            doneSent = true;
            return frames;
        }

        private void ensureRoleChunk(List<byte[]> frames) {
            if (!roleSent) {
                roleSent = true;
                ObjectNode delta = mapper.createObjectNode();
                delta.put("role", "assistant");
                delta.put("content", "");
                frames.add(buildChunkFrame(delta, null));
            }
        }

        /**
         * Everything up to (and including) the {@code "delta":} key, rendered once per stream: the chunk
         * envelope is identical for every token, so only the delta object is generated per frame.
         */
        private byte[] deltaPrefix() {
            byte[] cached = deltaPrefix;
            if (cached != null) {
                return cached;
            }
            StringBuilder json = new StringBuilder(180);
            json.append("{\"id\":").append(quoteJson(responseId))
                    .append(",\"object\":\"chat.completion.chunk\",\"created\":").append(created)
                    .append(",\"model\":").append(model == null ? "null" : quoteJson(model))
                    .append(",\"choices\":[{\"index\":0,\"delta\":");
            byte[] built = ("data: " + json).getBytes(StandardCharsets.UTF_8);
            deltaPrefix = built;
            return built;
        }

        private String quoteJson(String value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to quote streaming envelope field", ex);
            }
        }

        /**
         * Emits a single-field delta chunk directly into bytes: template prefix + Jackson-escaped delta
         * object + constant suffix, with the payload buffer reused across frames.
         */
        private byte[] buildDeltaFrame(String field, String text) {
            CodecSupport.ResizableByteBuffer buffer = frameBuffer;
            buffer.reset();
            buffer.writeBytes(deltaPrefix());
            try (JsonGenerator generator = CodecSupport.newUtf8Generator(mapper, buffer)) {
                generator.writeStartObject();
                generator.writeStringField(field, text);
                generator.writeEndObject();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to encode streaming delta frame", ex);
            }
            buffer.writeBytes(DELTA_FRAME_TAIL);
            return buffer.toCopiedByteArray();
        }

        private byte[] buildChunkFrame(ObjectNode delta, String finishReason) {
            ObjectNode chunk = mapper.createObjectNode();
            chunk.put("id", responseId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", model);
            ObjectNode choice = mapper.createObjectNode();
            choice.put("index", 0);
            choice.set("delta", delta);
            if (finishReason != null) {
                choice.put("finish_reason", finishReason);
            } else {
                choice.set("finish_reason", NullNode.getInstance());
            }
            ArrayNode choices = mapper.createArrayNode();
            choices.add(choice);
            chunk.set("choices", choices);
            return SseEventSplitter.buildDataFrame(CodecSupport.writeJsonString(mapper, chunk));
        }

        private byte[] buildUsageOnlyFrame(TokenUsage usage) {
            ObjectNode chunk = mapper.createObjectNode();
            chunk.put("id", responseId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", model);
            chunk.set("choices", mapper.createArrayNode());
            chunk.set("usage", encodeUsageNode(usage));
            return SseEventSplitter.buildDataFrame(CodecSupport.writeJsonString(mapper, chunk));
        }
    }

}
