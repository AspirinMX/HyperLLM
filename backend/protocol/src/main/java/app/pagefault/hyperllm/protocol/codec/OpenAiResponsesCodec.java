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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bidirectional codec for OpenAI Responses API ({@code POST /v1/responses}).
 */
public final class OpenAiResponsesCodec implements ProtocolCodec {

    private final ObjectMapper mapper;

    public OpenAiResponsesCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProtocolKind kind() {
        return ProtocolKind.OPENAI_RESPONSES;
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
        CodecSupport.ResizableByteBuffer out = new CodecSupport.ResizableByteBuffer(
                CodecSupport.estimateEncodedCapacity(req));
        try (JsonGenerator gen = CodecSupport.newUtf8Generator(mapper, out)) {
            gen.writeStartObject();
            gen.writeStringField("model", context.upstreamModelName() != null && !context.upstreamModelName().isBlank()
                    ? context.upstreamModelName() : req.model());
            gen.writeBooleanField("stream", req.stream());

            if (!req.systemInstructions().isEmpty()) {
                String singleSys = null;
                StringBuilder sysSb = null;
                for (CanonicalSystemPart sp : req.systemInstructions()) {
                    if (!sp.text().isEmpty()) {
                        if (singleSys == null && sysSb == null) {
                            singleSys = sp.text();
                        } else {
                            if (sysSb == null) {
                                sysSb = new StringBuilder(singleSys.length() + sp.text().length() + 2);
                                sysSb.append(singleSys);
                                singleSys = null;
                            }
                            sysSb.append("\n\n").append(sp.text());
                        }
                    }
                }
                String instructions = sysSb != null ? sysSb.toString() : singleSys;
                if (instructions != null && !instructions.isEmpty()) {
                    gen.writeStringField("instructions", instructions);
                }
            }

            gen.writeArrayFieldStart("input");
            for (CanonicalTurn turn : req.turns()) {
                writeTurn(gen, turn);
            }
            gen.writeEndArray();

            ProviderCapabilities caps = context.capabilitiesFor(ProtocolKind.OPENAI_RESPONSES);
            CanonicalGenerationConfig g = req.generation();
            if (g.maxOutputTokens() != null && g.maxOutputTokens() > 0) {
                gen.writeNumberField("max_output_tokens", g.maxOutputTokens());
            }
            boolean allowSampling = !(caps.forbidsSamplingWithReasoning() && context.policy() != TranslationPolicy.STRICT);
            if (allowSampling) {
                if (g.temperature() != null) {
                    gen.writeNumberField("temperature", g.temperature());
                }
                if (g.topP() != null) {
                    gen.writeNumberField("top_p", g.topP());
                }
            }

            writeTools(gen, req);
            writeTextFormat(gen, req.responseFormat());
            writeReasoning(gen, req.thinking());

            if (req.cache().promptCacheKey() != null && !req.cache().promptCacheKey().isBlank()) {
                gen.writeStringField("prompt_cache_key", req.cache().promptCacheKey());
            }
            if (req.cache().promptCacheRetention() != null && !req.cache().promptCacheRetention().isBlank()) {
                gen.writeStringField("prompt_cache_retention", req.cache().promptCacheRetention());
            }
            if (req.options().serviceTier() != null && !req.options().serviceTier().isBlank()) {
                gen.writeStringField("service_tier", req.options().serviceTier());
            }
            if (req.options().store() != null) {
                gen.writeBooleanField("store", req.options().store());
            }
            if (req.options().user() != null && !req.options().user().isBlank()) {
                gen.writeStringField("user", req.options().user());
            }
            // Vendor-only members retained verbatim from the inbound body. They are present only on the
            // same-protocol path; cross-protocol translation strips them (hard-incompatible ones fail first).
            for (Map.Entry<String, String> vendorField : req.options().vendorRawFields().entrySet()) {
                gen.writeFieldName(vendorField.getKey());
                gen.writeRawValue(vendorField.getValue());
            }
            gen.writeEndObject();
        } catch (HyperLlmException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize OpenAI Responses request JSON", ex);
        }
        return out.toExactByteArray();
    }

    // ---- Streaming request decoding (zero inbound DOM tree for large input[]) ----

    private CanonicalRequest decodeStreamingRoot(JsonParser parser) throws IOException {
        String model = null;
        boolean stream = false;
        boolean inputPresent = false;
        boolean inputIsNull = false;

        List<UnsupportedFeatureFlag> unsupported = new ArrayList<>();
        Map<String, String> vendorRawFields = new LinkedHashMap<>();
        List<CanonicalSystemPart> systemParts = new ArrayList<>();
        List<CanonicalSystemPart> leadingInputSystemParts = new ArrayList<>();
        List<CanonicalTurn> turns = new ArrayList<>();

        JsonNode toolsNode = null;
        JsonNode toolChoiceNode = null;
        Boolean parallelToolCalls = null;
        JsonNode textNode = null;
        Integer maxOutputTokens = null;
        Double temperature = null;
        Double topP = null;
        JsonNode reasoningNode = null;
        String promptCacheKey = null;
        String promptCacheRetention = null;
        String user = null;
        String serviceTier = null;
        Boolean store = null;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken valTok = parser.nextToken();
            if (valTok == null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body");
            }
            switch (field) {
                case "model" -> model = CodecSupport.readNullableString(parser);
                case "stream" -> {
                    if (valTok == JsonToken.VALUE_TRUE) {
                        stream = true;
                    } else if (valTok != JsonToken.VALUE_FALSE) {
                        parser.skipChildren();
                    }
                }
                case "previous_response_id" -> {
                    JsonNode previousResponseId = mapper.readTree(parser);
                    if (previousResponseId != null && previousResponseId.isTextual()
                            && !previousResponseId.asText().isBlank()) {
                        unsupported.add(UnsupportedFeatureFlag.hard("previous_response_id",
                                "Server-side response chaining requires the OpenAI Responses upstream protocol"));
                        vendorRawFields.put("previous_response_id", previousResponseId.toString());
                    }
                }
                case "conversation" -> {
                    JsonNode conversation = mapper.readTree(parser);
                    if (conversation != null && !conversation.isNull()) {
                        unsupported.add(UnsupportedFeatureFlag.hard("conversation",
                                "Server-side conversation state requires the OpenAI Responses upstream protocol"));
                        vendorRawFields.put("conversation", conversation.toString());
                    }
                }
                case "background" -> {
                    JsonNode background = mapper.readTree(parser);
                    if (background != null && background.asBoolean(false)) {
                        unsupported.add(UnsupportedFeatureFlag.hard("background",
                                "Asynchronous background execution requires the OpenAI Responses upstream protocol"));
                        vendorRawFields.put("background", background.toString());
                    }
                }
                case "instructions" -> {
                    String instr = CodecSupport.readNullableString(parser);
                    if (instr != null && !instr.isBlank()) {
                        systemParts.add(new CanonicalSystemPart(instr, true, null));
                    }
                }
                case "input" -> {
                    inputPresent = true;
                    if (valTok == JsonToken.VALUE_NULL) {
                        inputIsNull = true;
                    } else if (valTok == JsonToken.VALUE_STRING) {
                        turns.add(new CanonicalTurn(CanonicalRole.USER,
                                List.of(new CanonicalContentPart.TextPart(parser.getText()))));
                    } else if (valTok == JsonToken.START_ARRAY) {
                        readStreamingInputItems(parser, leadingInputSystemParts, turns, unsupported);
                    } else {
                        parser.skipChildren();
                        throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                                "Field 'input' must be a string or an array");
                    }
                }
                case "tools" -> toolsNode = mapper.readTree(parser);
                case "tool_choice" -> toolChoiceNode = mapper.readTree(parser);
                case "parallel_tool_calls" -> parallelToolCalls = CodecSupport.readNullableBoolean(parser);
                case "text" -> textNode = mapper.readTree(parser);
                case "max_output_tokens" -> maxOutputTokens = CodecSupport.readNullableIntegralInt(parser);
                case "temperature" -> temperature = CodecSupport.readNullableDouble(parser);
                case "top_p" -> topP = CodecSupport.readNullableDouble(parser);
                case "reasoning" -> reasoningNode = mapper.readTree(parser);
                case "prompt_cache_key" -> promptCacheKey = CodecSupport.readNullableString(parser);
                case "prompt_cache_retention" -> promptCacheRetention = CodecSupport.readNullableString(parser);
                case "user" -> user = CodecSupport.readNullableString(parser);
                case "service_tier" -> serviceTier = CodecSupport.readNullableString(parser);
                case "store" -> store = CodecSupport.readNullableBoolean(parser);
                default -> parser.skipChildren();
            }
        }

        if (model == null || model.isBlank()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'model' is required and must be non-blank");
        }
        if (!inputPresent || inputIsNull) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'input' is required");
        }
        if (!leadingInputSystemParts.isEmpty()) {
            systemParts.addAll(leadingInputSystemParts);
        }

        List<CanonicalToolDefinition> tools = decodeTools(toolsNode, unsupported);
        List<String> vendorRawTools = retainBuiltinTools(toolsNode);
        CanonicalToolChoice toolChoice = decodeToolChoice(toolChoiceNode, parallelToolCalls);
        CanonicalResponseFormat responseFormat = textNode != null ? decodeTextFormat(textNode.get("format")) : null;
        CanonicalGenerationConfig generation = new CanonicalGenerationConfig(
                maxOutputTokens, temperature, topP, null, List.of(), null, null, null, null);
        CanonicalThinkingConfig thinking = decodeReasoning(reasoningNode);
        CanonicalCacheConfig cache = new CanonicalCacheConfig(null, promptCacheKey, promptCacheRetention, null);
        CanonicalProtocolOptions options = new CanonicalProtocolOptions(
                true, true, null, null, user, serviceTier, store, vendorRawFields, vendorRawTools);

        return new CanonicalRequest(
                ProtocolKind.OPENAI_RESPONSES, model.trim(), stream, systemParts, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, unsupported
        );
    }

    private void readStreamingInputItems(
            JsonParser parser,
            List<CanonicalSystemPart> leadingSystemParts,
            List<CanonicalTurn> turns,
            List<UnsupportedFeatureFlag> unsupported
    ) throws IOException {
        boolean sawNonSystem = false;
        List<CanonicalContentPart> pendingAssistant = new ArrayList<>();

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String type = "";
            String role = "user";
            String callId = null;
            String toolCallId = null;
            String itemId = null;
            String name = "";
            String rawArgs = "{}";
            String encryptedContent = null;
            String plainContent = null;
            List<CanonicalContentPart> arrayContent = null;
            String plainOutput = null;
            List<CanonicalContentPart> arrayOutput = null;
            String summaryText = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken t = parser.nextToken();
                switch (f) {
                    case "type" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            type = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "role" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            role = parser.getText().toLowerCase(Locale.ROOT);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "call_id" -> callId = CodecSupport.readNullableString(parser);
                    case "tool_call_id" -> toolCallId = CodecSupport.readNullableString(parser);
                    case "id" -> itemId = CodecSupport.readNullableString(parser);
                    case "name" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            name = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "arguments" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            rawArgs = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "encrypted_content" -> encryptedContent = CodecSupport.readNullableString(parser);
                    case "content" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            plainContent = parser.getText();
                        } else if (t == JsonToken.START_ARRAY) {
                            arrayContent = readStreamingMessageContentArray(parser, unsupported);
                        } else if (t != JsonToken.VALUE_NULL) {
                            plainContent = mapper.readTree(parser).toString();
                        }
                    }
                    case "output" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            plainOutput = parser.getText();
                        } else if (t == JsonToken.START_ARRAY) {
                            arrayOutput = readStreamingMessageContentArray(parser, unsupported);
                        } else if (t != JsonToken.VALUE_NULL) {
                            plainOutput = mapper.readTree(parser).toString();
                        }
                    }
                    case "summary" -> {
                        if (t == JsonToken.START_ARRAY) {
                            summaryText = readStreamingSummaryTexts(parser);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    default -> parser.skipChildren();
                }
            }

            if (type.isEmpty() || "message".equals(type)) {
                switch (role) {
                    case "system", "developer" -> {
                        boolean devPriority = "developer".equals(role);
                        flushPendingAssistant(pendingAssistant, turns);
                        String text = extractPlainFromStreaming(plainContent, arrayContent);
                        if (!sawNonSystem) {
                            leadingSystemParts.add(new CanonicalSystemPart(text, devPriority, null));
                        } else {
                            turns.add(new CanonicalTurn(
                                    devPriority ? CanonicalRole.DEVELOPER : CanonicalRole.SYSTEM,
                                    List.of(new CanonicalContentPart.TextPart(text))
                            ));
                        }
                    }
                    case "user" -> {
                        sawNonSystem = true;
                        flushPendingAssistant(pendingAssistant, turns);
                        turns.add(new CanonicalTurn(CanonicalRole.USER, resolveParts(plainContent, arrayContent)));
                    }
                    case "assistant" -> {
                        sawNonSystem = true;
                        pendingAssistant.addAll(resolveParts(plainContent, arrayContent));
                    }
                    case "tool" -> {
                        sawNonSystem = true;
                        flushPendingAssistant(pendingAssistant, turns);
                        String effectiveCallId = callId != null ? callId : toolCallId;
                        turns.add(new CanonicalTurn(
                                CanonicalRole.TOOL,
                                List.of(new CanonicalContentPart.ToolResultPart(
                                        effectiveCallId, name.isEmpty() ? null : name,
                                        resolveParts(plainContent, arrayContent), false, null))
                        ));
                    }
                    default -> throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                            "Unsupported Responses message role: " + role);
                }
            } else if ("function_call".equals(type)) {
                sawNonSystem = true;
                // Defer JSON object parsing of rawArgs (zero eager readTree!)
                pendingAssistant.add(new CanonicalContentPart.ToolCallPart(
                        callId != null ? callId : itemId,
                        itemId,
                        name,
                        null,
                        rawArgs,
                        null,
                        ProtocolKind.OPENAI_RESPONSES
                ));
            } else if ("function_call_output".equals(type)) {
                sawNonSystem = true;
                flushPendingAssistant(pendingAssistant, turns);
                turns.add(new CanonicalTurn(
                        CanonicalRole.TOOL,
                        List.of(new CanonicalContentPart.ToolResultPart(
                                callId, null, resolveParts(plainOutput, arrayOutput), false, null))
                ));
            } else if ("reasoning".equals(type)) {
                sawNonSystem = true;
                String reasoningText = (summaryText != null && !summaryText.isEmpty())
                        ? summaryText
                        : extractPlainFromStreaming(plainContent, arrayContent);
                if (reasoningText != null && !reasoningText.isEmpty()) {
                    pendingAssistant.add(new CanonicalContentPart.ThinkingPart(
                            reasoningText, null, encryptedContent, ProtocolKind.OPENAI_RESPONSES));
                } else if (encryptedContent != null && !encryptedContent.isBlank()) {
                    pendingAssistant.add(new CanonicalContentPart.RedactedThinkingPart(
                            encryptedContent, ProtocolKind.OPENAI_RESPONSES));
                }
            } else {
                unsupported.add(UnsupportedFeatureFlag.degradable("input_item:" + type,
                        "Responses input item type '" + type + "' is not supported across protocol translation"));
            }
        }
        flushPendingAssistant(pendingAssistant, turns);
    }

    private List<CanonicalContentPart> readStreamingMessageContentArray(
            JsonParser parser,
            List<UnsupportedFeatureFlag> unsupported
    ) throws IOException {
        List<CanonicalContentPart> parts = new ArrayList<>(4);
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String type = "";
            String text = "";
            String refusal = "";
            String fileId = null;
            String imgUrl = null;
            ImageDetail detail = ImageDetail.AUTO;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken t = parser.nextToken();
                switch (f) {
                    case "type" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            type = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "text" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            text = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "refusal" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            refusal = parser.getText();
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "file_id" -> fileId = CodecSupport.readNullableString(parser);
                    case "detail" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            detail = ImageDetail.parseOrDefault(parser.getText(), ImageDetail.AUTO);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "image_url" -> {
                        if (t == JsonToken.VALUE_STRING) {
                            imgUrl = parser.getText();
                        } else if (t == JsonToken.START_OBJECT) {
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String imf = parser.currentName();
                                JsonToken imt = parser.nextToken();
                                if ("url".equals(imf) && imt == JsonToken.VALUE_STRING) {
                                    imgUrl = parser.getText();
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
                case "input_text", "output_text", "text", "summary_text" ->
                        parts.add(new CanonicalContentPart.TextPart(text));
                case "refusal" -> parts.add(new CanonicalContentPart.RefusalPart(refusal));
                case "input_image" -> {
                    if (fileId != null && !fileId.isBlank()) {
                        parts.add(new CanonicalContentPart.ImagePart(
                                new ImageSource.FileRefImage(ProtocolKind.OPENAI_RESPONSES, fileId, null), detail, null));
                    } else if (imgUrl != null) {
                        parts.add(new CanonicalContentPart.ImagePart(
                                CodecSupport.parseImageUrlOrDataUri(imgUrl), detail, null));
                    } else {
                        throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                                "input_image requires either 'image_url' or 'file_id'");
                    }
                }
                default -> unsupported.add(UnsupportedFeatureFlag.degradable("content_part:" + type,
                        "Responses content part '" + type + "' is not supported across protocol translation"));
            }
        }
        return parts;
    }

    private String readStreamingSummaryTexts(JsonParser parser) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken t = parser.nextToken();
                if ("text".equals(f) && t == JsonToken.VALUE_STRING) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(parser.getText());
                } else {
                    parser.skipChildren();
                }
            }
        }
        return sb.toString();
    }

    private static List<CanonicalContentPart> resolveParts(String plain, List<CanonicalContentPart> arr) {
        if (arr != null) {
            return arr;
        }
        if (plain != null) {
            return List.of(new CanonicalContentPart.TextPart(plain));
        }
        return List.of();
    }

    private static String extractPlainFromStreaming(String plain, List<CanonicalContentPart> arr) {
        if (plain != null) {
            return plain;
        }
        if (arr == null || arr.isEmpty()) {
            return "";
        }
        String single = null;
        StringBuilder sb = null;
        for (CanonicalContentPart p : arr) {
            if (p instanceof CanonicalContentPart.TextPart t) {
                if (single == null && sb == null) {
                    single = t.text();
                } else {
                    if (sb == null) {
                        sb = new StringBuilder(single.length() + t.text().length() + 1);
                        sb.append(single);
                        single = null;
                    }
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(t.text());
                }
            }
        }
        return sb != null ? sb.toString() : (single != null ? single : "");
    }

    @Override
    public CanonicalResponse decodeResponse(byte[] responseBody, String virtualModel) {
        ObjectNode root = CodecSupport.parseResponseObjectRoot(mapper, responseBody);
        String id = root.path("id").asText("");
        if (id.isBlank()) {
            id = CodecSupport.generateId("resp_");
        }
        long created = root.path("created_at").asLong(CodecSupport.nowEpochSeconds());
        String model = (virtualModel != null && !virtualModel.isBlank())
                ? virtualModel : root.path("model").asText("");

        List<CanonicalContentPart> parts = new ArrayList<>();
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                decodeOutputItem(item, parts);
            }
        }

        CanonicalStopReason stopReason = mapStatusToStopReason(
                CodecSupport.asNullableText(root, "status"),
                root.path("incomplete_details").path("reason").asText(null),
                parts
        );
        TokenUsage usage = parseUsageNode(root.get("usage")).orElse(TokenUsage.empty());
        return new CanonicalResponse(id, model, created, parts, stopReason, null, usage, ProtocolKind.OPENAI_RESPONSES);
    }

    @Override
    public byte[] encodeResponse(CanonicalResponse response) {
        ObjectNode root = mapper.createObjectNode();
        String respId = (response.id() != null && !response.id().isBlank())
                ? response.id() : CodecSupport.generateId("resp_");
        root.put("id", respId);
        root.put("object", "response");
        root.put("created_at", response.createdEpochSeconds() > 0 ? response.createdEpochSeconds() : CodecSupport.nowEpochSeconds());
        root.put("model", response.virtualModel());

        ArrayNode outputArr = mapper.createArrayNode();
        ArrayNode msgContent = mapper.createArrayNode();

        for (CanonicalContentPart part : response.content()) {
            switch (part) {
                case CanonicalContentPart.ThinkingPart th -> {
                    ObjectNode reasoningItem = mapper.createObjectNode();
                    reasoningItem.put("id", CodecSupport.generateId("rs_"));
                    reasoningItem.put("type", "reasoning");
                    ArrayNode summaryArr = mapper.createArrayNode();
                    if (!th.text().isEmpty()) {
                        ObjectNode s = mapper.createObjectNode();
                        s.put("type", "summary_text");
                        s.put("text", th.text());
                        summaryArr.add(s);
                    }
                    reasoningItem.set("summary", summaryArr);
                    if (CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.OPENAI_RESPONSES)
                            && th.encryptedContent() != null) {
                        reasoningItem.put("encrypted_content", th.encryptedContent());
                    }
                    outputArr.add(reasoningItem);
                }
                case CanonicalContentPart.RedactedThinkingPart rt -> {
                    if (CodecSupport.isSameOrigin(rt.originProtocol(), ProtocolKind.OPENAI_RESPONSES)
                            && rt.opaqueData() != null) {
                        ObjectNode reasoningItem = mapper.createObjectNode();
                        reasoningItem.put("id", CodecSupport.generateId("rs_"));
                        reasoningItem.put("type", "reasoning");
                        reasoningItem.set("summary", mapper.createArrayNode());
                        reasoningItem.put("encrypted_content", rt.opaqueData());
                        outputArr.add(reasoningItem);
                    }
                }
                case CanonicalContentPart.TextPart t -> {
                    ObjectNode textPart = mapper.createObjectNode();
                    textPart.put("type", "output_text");
                    textPart.put("text", t.text());
                    textPart.set("annotations", mapper.createArrayNode());
                    msgContent.add(textPart);
                }
                case CanonicalContentPart.RefusalPart r -> {
                    ObjectNode refPart = mapper.createObjectNode();
                    refPart.put("type", "refusal");
                    refPart.put("refusal", r.refusal());
                    msgContent.add(refPart);
                }
                case CanonicalContentPart.ToolCallPart tc -> {
                    if (!msgContent.isEmpty()) {
                        outputArr.add(buildMessageOutputItem(msgContent));
                        msgContent = mapper.createArrayNode();
                    }
                    outputArr.add(buildFunctionCallItem(tc));
                }
                case CanonicalContentPart.ImagePart ignored -> {}
                case CanonicalContentPart.ToolResultPart ignored -> {}
            }
        }
        if (!msgContent.isEmpty()) {
            outputArr.add(buildMessageOutputItem(msgContent));
        }

        applyStatusFields(root, response.stopReason());
        root.set("output", outputArr);
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
        return new OpenAiResponsesStreamDecoder(virtualModel);
    }

    @Override
    public DownstreamStreamEncoder newStreamEncoder(StreamRequestMetadata inboundMeta) {
        return new OpenAiResponsesStreamEncoder(inboundMeta);
    }

    // ---- Request decoding helpers ----

    private void decodeInputItems(
            JsonNode inputNode,
            List<CanonicalSystemPart> systemParts,
            List<CanonicalTurn> turns,
            List<UnsupportedFeatureFlag> unsupported
    ) {
        if (inputNode.isTextual()) {
            turns.add(new CanonicalTurn(CanonicalRole.USER, List.of(new CanonicalContentPart.TextPart(inputNode.asText()))));
            return;
        }
        if (!inputNode.isArray()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'input' must be a string or an array");
        }
        boolean sawNonSystem = false;
        List<CanonicalContentPart> pendingAssistant = new ArrayList<>();

        for (JsonNode item : inputNode) {
            if (!item.isObject()) {
                continue;
            }
            String type = item.path("type").asText("");
            if (type.isEmpty() || "message".equals(type)) {
                String role = item.path("role").asText("user").toLowerCase(Locale.ROOT);
                switch (role) {
                    case "system", "developer" -> {
                        boolean devPriority = "developer".equals(role);
                        flushPendingAssistant(pendingAssistant, turns);
                        String text = extractTextFromContent(item.get("content"));
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
                        flushPendingAssistant(pendingAssistant, turns);
                        List<CanonicalContentPart> parts = decodeMessageContent(item.get("content"), unsupported);
                        turns.add(new CanonicalTurn(CanonicalRole.USER, parts));
                    }
                    case "assistant" -> {
                        sawNonSystem = true;
                        pendingAssistant.addAll(decodeMessageContent(item.get("content"), unsupported));
                    }
                    case "tool" -> {
                        sawNonSystem = true;
                        flushPendingAssistant(pendingAssistant, turns);
                        String callId = CodecSupport.asNullableText(item, "call_id");
                        if (callId == null) {
                            callId = CodecSupport.asNullableText(item, "tool_call_id");
                        }
                        String name = CodecSupport.asNullableText(item, "name");
                        List<CanonicalContentPart> parts = decodeMessageContent(item.get("content"), unsupported);
                        turns.add(new CanonicalTurn(
                                CanonicalRole.TOOL,
                                List.of(new CanonicalContentPart.ToolResultPart(callId, name, parts, false, null))
                        ));
                    }
                    default -> throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                            "Unsupported Responses message role: " + role);
                }
            } else if ("function_call".equals(type)) {
                sawNonSystem = true;
                String callId = CodecSupport.asNullableText(item, "call_id");
                String itemId = CodecSupport.asNullableText(item, "id");
                String name = item.path("name").asText("");
                String rawArgs = item.path("arguments").asText("{}");
                pendingAssistant.add(new CanonicalContentPart.ToolCallPart(
                        callId != null ? callId : itemId,
                        itemId,
                        name,
                        CodecSupport.parseArgumentsToObject(mapper, rawArgs),
                        rawArgs,
                        null,
                        ProtocolKind.OPENAI_RESPONSES
                ));
            } else if ("function_call_output".equals(type)) {
                sawNonSystem = true;
                flushPendingAssistant(pendingAssistant, turns);
                String callId = CodecSupport.asNullableText(item, "call_id");
                List<CanonicalContentPart> outParts = decodeMessageContent(item.get("output"), unsupported);
                turns.add(new CanonicalTurn(
                        CanonicalRole.TOOL,
                        List.of(new CanonicalContentPart.ToolResultPart(callId, null, outParts, false, null))
                ));
            } else if ("reasoning".equals(type)) {
                sawNonSystem = true;
                decodeReasoningItem(item, pendingAssistant);
            } else {
                unsupported.add(UnsupportedFeatureFlag.degradable("input_item:" + type,
                        "Responses input item type '" + type + "' is not supported across protocol translation"));
            }
        }
        flushPendingAssistant(pendingAssistant, turns);
    }

    private static void flushPendingAssistant(List<CanonicalContentPart> pending, List<CanonicalTurn> turns) {
        if (!pending.isEmpty()) {
            turns.add(new CanonicalTurn(CanonicalRole.ASSISTANT, List.copyOf(pending)));
            pending.clear();
        }
    }

    private List<CanonicalContentPart> decodeMessageContent(JsonNode contentNode, List<UnsupportedFeatureFlag> unsupported) {
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
            if (!part.isObject()) {
                continue;
            }
            String type = part.path("type").asText("");
            switch (type) {
                case "input_text", "output_text", "text" ->
                        parts.add(new CanonicalContentPart.TextPart(part.path("text").asText("")));
                case "refusal" ->
                        parts.add(new CanonicalContentPart.RefusalPart(part.path("refusal").asText("")));
                case "input_image" -> {
                    ImageDetail detail = ImageDetail.parseOrDefault(CodecSupport.asNullableText(part, "detail"), ImageDetail.AUTO);
                    String fileId = CodecSupport.asNullableText(part, "file_id");
                    JsonNode imageUrlNode = part.get("image_url");
                    if (fileId != null && !fileId.isBlank()) {
                        parts.add(new CanonicalContentPart.ImagePart(
                                new ImageSource.FileRefImage(ProtocolKind.OPENAI_RESPONSES, fileId, null), detail, null));
                    } else if (imageUrlNode != null && imageUrlNode.isTextual()) {
                        parts.add(new CanonicalContentPart.ImagePart(
                                CodecSupport.parseImageUrlOrDataUri(imageUrlNode.asText()), detail, null));
                    } else if (imageUrlNode != null && imageUrlNode.isObject() && imageUrlNode.hasNonNull("url")) {
                        // Defensive compatibility if a caller sends {url: ...}
                        parts.add(new CanonicalContentPart.ImagePart(
                                CodecSupport.parseImageUrlOrDataUri(imageUrlNode.get("url").asText()), detail, null));
                    } else {
                        throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                                "input_image requires either 'image_url' or 'file_id'");
                    }
                }
                default -> unsupported.add(UnsupportedFeatureFlag.degradable("content_part:" + type,
                        "Responses content part '" + type + "' is not supported across protocol translation"));
            }
        }
        return parts;
    }

    private void decodeReasoningItem(JsonNode item, List<CanonicalContentPart> target) {
        StringBuilder summaryText = new StringBuilder();
        JsonNode summary = item.get("summary");
        if (summary != null && summary.isArray()) {
            for (JsonNode s : summary) {
                if (s.isObject() && s.hasNonNull("text")) {
                    if (!summaryText.isEmpty()) {
                        summaryText.append('\n');
                    }
                    summaryText.append(s.get("text").asText());
                }
            }
        }
        JsonNode content = item.get("content");
        if (summaryText.isEmpty() && content != null && content.isArray()) {
            for (JsonNode c : content) {
                if (c.isObject() && c.hasNonNull("text")) {
                    if (!summaryText.isEmpty()) {
                        summaryText.append('\n');
                    }
                    summaryText.append(c.get("text").asText());
                }
            }
        }
        String encrypted = CodecSupport.asNullableText(item, "encrypted_content");
        if (!summaryText.isEmpty()) {
            target.add(new CanonicalContentPart.ThinkingPart(
                    summaryText.toString(), null, encrypted, ProtocolKind.OPENAI_RESPONSES));
        } else if (encrypted != null && !encrypted.isBlank()) {
            target.add(new CanonicalContentPart.RedactedThinkingPart(encrypted, ProtocolKind.OPENAI_RESPONSES));
        }
    }

    private void decodeOutputItem(JsonNode item, List<CanonicalContentPart> out) {
        if (item == null || !item.isObject()) {
            return;
        }
        String type = item.path("type").asText("");
        switch (type) {
            case "reasoning" -> decodeReasoningItem(item, out);
            case "message" -> {
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode part : content) {
                        String pType = part.path("type").asText("");
                        if ("output_text".equals(pType) || "text".equals(pType)) {
                            out.add(new CanonicalContentPart.TextPart(part.path("text").asText("")));
                        } else if ("refusal".equals(pType)) {
                            out.add(new CanonicalContentPart.RefusalPart(part.path("refusal").asText("")));
                        }
                    }
                }
            }
            case "function_call" -> {
                String callId = CodecSupport.asNullableText(item, "call_id");
                String itemId = CodecSupport.asNullableText(item, "id");
                String name = item.path("name").asText("");
                String rawArgs = item.path("arguments").asText("{}");
                out.add(new CanonicalContentPart.ToolCallPart(
                        callId != null ? callId : itemId,
                        itemId,
                        name,
                        CodecSupport.parseArgumentsToObject(mapper, rawArgs),
                        rawArgs,
                        null,
                        ProtocolKind.OPENAI_RESPONSES
                ));
            }
            default -> {}
        }
    }

    private List<CanonicalToolDefinition> decodeTools(JsonNode toolsNode, List<UnsupportedFeatureFlag> unsupported) {
        if (toolsNode == null || !toolsNode.isArray()) {
            return List.of();
        }
        List<CanonicalToolDefinition> out = new ArrayList<>(toolsNode.size());
        for (JsonNode tool : toolsNode) {
            String type = tool.path("type").asText("");
            if (!"function".equals(type)) {
                unsupported.add(UnsupportedFeatureFlag.degradable("builtin_tool:" + type,
                        "OpenAI Responses built-in tool '" + type + "' cannot be translated across vendors"));
                continue;
            }
            out.add(new CanonicalToolDefinition(
                    tool.path("name").asText(""),
                    CodecSupport.asNullableText(tool, "description"),
                    tool.get("parameters"),
                    CodecSupport.asNullableBoolean(tool, "strict"),
                    null
            ));
        }
        return out;
    }

    /**
     * Retains built-in (non-function) tool declarations verbatim so a same-protocol re-encode can emit them.
     * They remain flagged as degradable, so cross-protocol translation still drops them.
     */
    private static List<String> retainBuiltinTools(JsonNode toolsNode) {
        if (toolsNode == null || !toolsNode.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(2);
        for (JsonNode tool : toolsNode) {
            if (!"function".equals(tool.path("type").asText(""))) {
                out.add(tool.toString());
            }
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
            return new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.SPECIFIC,
                    CodecSupport.asNullableText(node, "name"), parallel);
        }
        return new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.AUTO, null, parallel);
    }

    private CanonicalResponseFormat decodeTextFormat(JsonNode formatNode) {
        if (formatNode == null || !formatNode.isObject()) {
            return null;
        }
        String type = formatNode.path("type").asText("text");
        return switch (type) {
            case "json_object" -> new CanonicalResponseFormat.JsonObjectFormat();
            case "json_schema" -> new CanonicalResponseFormat.JsonSchemaFormat(
                    CodecSupport.asNullableText(formatNode, "name"),
                    CodecSupport.asNullableText(formatNode, "description"),
                    formatNode.get("schema"),
                    CodecSupport.asNullableBoolean(formatNode, "strict")
            );
            default -> new CanonicalResponseFormat.TextFormat();
        };
    }

    private CanonicalThinkingConfig decodeReasoning(JsonNode node) {
        if (node == null || !node.isObject()) {
            return CanonicalThinkingConfig.omitted();
        }
        var effort = CanonicalThinkingConfig.ThinkingEffort.parseOrOmitted(CodecSupport.asNullableText(node, "effort"));
        String summary = CodecSupport.asNullableText(node, "summary");
        var mode = (effort == CanonicalThinkingConfig.ThinkingEffort.NONE)
                ? CanonicalThinkingConfig.ThinkingMode.DISABLED
                : CanonicalThinkingConfig.ThinkingMode.ENABLED;
        Boolean includeThoughts = summary != null && !summary.isBlank() ? Boolean.TRUE : null;
        return new CanonicalThinkingConfig(mode, effort, null, includeThoughts, summary);
    }

    private static String extractTextFromContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : content) {
                if (p.isObject() && p.hasNonNull("text")) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(p.get("text").asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    // ---- Streaming request encoding helpers (zero outbound DOM tree) ----

    private void writeTurn(JsonGenerator gen, CanonicalTurn turn) throws IOException {
        switch (turn.role()) {
            case SYSTEM, DEVELOPER -> {
                gen.writeStartObject();
                gen.writeStringField("type", "message");
                gen.writeStringField("role", turn.role() == CanonicalRole.SYSTEM ? "system" : "developer");
                gen.writeArrayFieldStart("content");
                for (CanonicalContentPart p : turn.parts()) {
                    if (p instanceof CanonicalContentPart.TextPart t) {
                        gen.writeStartObject();
                        gen.writeStringField("type", "input_text");
                        gen.writeStringField("text", t.text());
                        gen.writeEndObject();
                    }
                }
                gen.writeEndArray();
                gen.writeEndObject();
            }
            case USER, TOOL -> {
                List<CanonicalContentPart> pending = null;
                for (CanonicalContentPart p : turn.parts()) {
                    if (p instanceof CanonicalContentPart.ToolResultPart tr) {
                        if (pending != null && !pending.isEmpty()) {
                            writeUserMessageInputItem(gen, pending);
                            pending.clear();
                        }
                        writeFunctionCallOutputItem(gen, tr);
                    } else if (p instanceof CanonicalContentPart.TextPart || p instanceof CanonicalContentPart.ImagePart) {
                        if (pending == null) {
                            pending = new ArrayList<>(4);
                        }
                        pending.add(p);
                    }
                }
                if (pending != null && !pending.isEmpty()) {
                    writeUserMessageInputItem(gen, pending);
                }
            }
            case ASSISTANT -> {
                List<CanonicalContentPart> pending = null;
                for (CanonicalContentPart p : turn.parts()) {
                    switch (p) {
                        case CanonicalContentPart.ThinkingPart th -> {
                            if (CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.OPENAI_RESPONSES)
                                    && th.encryptedContent() != null) {
                                gen.writeStartObject();
                                gen.writeStringField("type", "reasoning");
                                gen.writeArrayFieldStart("summary");
                                if (!th.text().isEmpty()) {
                                    gen.writeStartObject();
                                    gen.writeStringField("type", "summary_text");
                                    gen.writeStringField("text", th.text());
                                    gen.writeEndObject();
                                }
                                gen.writeEndArray();
                                gen.writeStringField("encrypted_content", th.encryptedContent());
                                gen.writeEndObject();
                            }
                        }
                        case CanonicalContentPart.RedactedThinkingPart rt -> {
                            if (CodecSupport.isSameOrigin(rt.originProtocol(), ProtocolKind.OPENAI_RESPONSES)
                                    && rt.opaqueData() != null) {
                                gen.writeStartObject();
                                gen.writeStringField("type", "reasoning");
                                gen.writeArrayFieldStart("summary");
                                gen.writeEndArray();
                                gen.writeStringField("encrypted_content", rt.opaqueData());
                                gen.writeEndObject();
                            }
                        }
                        case CanonicalContentPart.TextPart t -> {
                            if (pending == null) {
                                pending = new ArrayList<>(4);
                            }
                            pending.add(t);
                        }
                        case CanonicalContentPart.RefusalPart ref -> {
                            if (pending == null) {
                                pending = new ArrayList<>(4);
                            }
                            pending.add(ref);
                        }
                        case CanonicalContentPart.ToolCallPart tc -> {
                            if (pending != null && !pending.isEmpty()) {
                                writeAssistantMessageInputItem(gen, pending);
                                pending.clear();
                            }
                            writeFunctionCallItem(gen, tc);
                        }
                        case CanonicalContentPart.ImagePart ignored -> {}
                        case CanonicalContentPart.ToolResultPart ignored -> {}
                    }
                }
                if (pending != null && !pending.isEmpty()) {
                    writeAssistantMessageInputItem(gen, pending);
                }
            }
        }
    }

    private void writeUserMessageInputItem(JsonGenerator gen, List<CanonicalContentPart> parts) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "message");
        gen.writeStringField("role", "user");
        gen.writeArrayFieldStart("content");
        for (CanonicalContentPart p : parts) {
            if (p instanceof CanonicalContentPart.TextPart t) {
                gen.writeStartObject();
                gen.writeStringField("type", "input_text");
                gen.writeStringField("text", t.text());
                gen.writeEndObject();
            } else if (p instanceof CanonicalContentPart.ImagePart img) {
                writeInputImagePart(gen, img);
            }
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }

    private void writeAssistantMessageInputItem(JsonGenerator gen, List<CanonicalContentPart> parts) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "message");
        gen.writeStringField("role", "assistant");
        gen.writeArrayFieldStart("content");
        for (CanonicalContentPart p : parts) {
            if (p instanceof CanonicalContentPart.TextPart t) {
                gen.writeStartObject();
                gen.writeStringField("type", "output_text");
                gen.writeStringField("text", t.text());
                gen.writeEndObject();
            } else if (p instanceof CanonicalContentPart.RefusalPart ref) {
                gen.writeStartObject();
                gen.writeStringField("type", "refusal");
                gen.writeStringField("refusal", ref.refusal());
                gen.writeEndObject();
            }
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }

    private ObjectNode buildMessageOutputItem(ArrayNode content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("id", CodecSupport.generateId("msg_"));
        msg.put("type", "message");
        msg.put("status", "completed");
        msg.put("role", "assistant");
        msg.set("content", content);
        return msg;
    }

    private void writeFunctionCallItem(JsonGenerator gen, CanonicalContentPart.ToolCallPart tc) throws IOException {
        String callId = (tc.callId() != null && !tc.callId().isBlank()) ? tc.callId() : CodecSupport.generateId("call_");
        String itemId = (tc.itemId() != null && !tc.itemId().isBlank()) ? tc.itemId() : CodecSupport.generateId("fc_");
        gen.writeStartObject();
        gen.writeStringField("id", itemId);
        gen.writeStringField("type", "function_call");
        gen.writeStringField("status", "completed");
        gen.writeStringField("call_id", callId);
        gen.writeStringField("name", tc.name());
        gen.writeStringField("arguments", CodecSupport.serializeArguments(mapper, tc));
        gen.writeEndObject();
    }

    private ObjectNode buildFunctionCallItem(CanonicalContentPart.ToolCallPart tc) {
        ObjectNode item = mapper.createObjectNode();
        String callId = (tc.callId() != null && !tc.callId().isBlank()) ? tc.callId() : CodecSupport.generateId("call_");
        item.put("id", tc.itemId() != null && !tc.itemId().isBlank() ? tc.itemId() : CodecSupport.generateId("fc_"));
        item.put("type", "function_call");
        item.put("status", "completed");
        item.put("call_id", callId);
        item.put("name", tc.name());
        item.put("arguments", CodecSupport.serializeArguments(mapper, tc));
        return item;
    }

    private void writeFunctionCallOutputItem(JsonGenerator gen, CanonicalContentPart.ToolResultPart tr) throws IOException {
        List<CanonicalContentPart> children = tr.content();
        boolean hasImage = false;
        for (CanonicalContentPart c : children) {
            if (c instanceof CanonicalContentPart.ImagePart) {
                hasImage = true;
                break;
            }
        }
        gen.writeStartObject();
        gen.writeStringField("type", "function_call_output");
        gen.writeStringField("call_id", tr.callId() != null ? tr.callId() : "");
        if (!hasImage) {
            String outputText;
            if (children.isEmpty()) {
                outputText = "";
            } else if (children.size() == 1 && children.getFirst() instanceof CanonicalContentPart.TextPart t) {
                outputText = (tr.isError() && !t.text().isEmpty()) ? "Error: " + t.text() : t.text();
            } else {
                StringBuilder text = new StringBuilder();
                for (CanonicalContentPart c : children) {
                    if (c instanceof CanonicalContentPart.TextPart t) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(t.text());
                    }
                }
                outputText = (tr.isError() && !text.isEmpty()) ? "Error: " + text : text.toString();
            }
            gen.writeStringField("output", outputText);
        } else {
            gen.writeArrayFieldStart("output");
            for (CanonicalContentPart c : children) {
                if (c instanceof CanonicalContentPart.TextPart t) {
                    gen.writeStartObject();
                    gen.writeStringField("type", "input_text");
                    gen.writeStringField("text", tr.isError() ? "Error: " + t.text() : t.text());
                    gen.writeEndObject();
                } else if (c instanceof CanonicalContentPart.ImagePart img) {
                    writeInputImagePart(gen, img);
                }
            }
            gen.writeEndArray();
        }
        gen.writeEndObject();
    }

    private void writeInputImagePart(JsonGenerator gen, CanonicalContentPart.ImagePart img) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", "input_image");
        gen.writeStringField("detail", (img.detail() != null ? img.detail() : ImageDetail.AUTO).toOpenAiValue());
        switch (img.source()) {
            case ImageSource.Base64Image b64 -> gen.writeStringField("image_url", b64.toDataUri());
            case ImageSource.UrlImage u -> gen.writeStringField("image_url", u.url());
            case ImageSource.FileRefImage ref -> gen.writeStringField("file_id", ref.fileRef());
        }
        gen.writeEndObject();
    }

    private void writeTools(JsonGenerator gen, CanonicalRequest req) throws IOException {
        List<String> vendorTools = req.options().vendorRawTools();
        if (req.tools().isEmpty() && vendorTools.isEmpty()) {
            return;
        }
        gen.writeArrayFieldStart("tools");
        for (CanonicalToolDefinition tool : req.tools()) {
            gen.writeStartObject();
            gen.writeStringField("type", "function");
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
            if (tool.strict() != null) {
                gen.writeBooleanField("strict", tool.strict());
            }
            gen.writeEndObject();
        }
        // Built-in vendor tools (e.g. web_search) retained verbatim from the inbound body.
        for (String vendorTool : vendorTools) {
            gen.writeRawValue(vendorTool);
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
                gen.writeStringField("name", tc.specificToolName());
                gen.writeEndObject();
            }
            case OMITTED -> {}
        }
        if (tc.parallelToolCalls() != null) {
            gen.writeBooleanField("parallel_tool_calls", tc.parallelToolCalls());
        }
    }

    private void writeTextFormat(JsonGenerator gen, CanonicalResponseFormat format) throws IOException {
        if (format == null) {
            return;
        }
        gen.writeObjectFieldStart("text");
        gen.writeObjectFieldStart("format");
        switch (format) {
            case CanonicalResponseFormat.TextFormat ignored -> gen.writeStringField("type", "text");
            case CanonicalResponseFormat.JsonObjectFormat ignored -> gen.writeStringField("type", "json_object");
            case CanonicalResponseFormat.JsonSchemaFormat js -> {
                gen.writeStringField("type", "json_schema");
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
            }
        }
        gen.writeEndObject();
        gen.writeEndObject();
    }

    private void writeReasoning(JsonGenerator gen, CanonicalThinkingConfig thinking) throws IOException {
        String effort = CodecSupport.toOpenAiReasoningEffort(thinking);
        if (effort == null && thinking.summaryMode() == null && !Boolean.TRUE.equals(thinking.includeThoughts())) {
            return;
        }
        gen.writeObjectFieldStart("reasoning");
        if (effort != null) {
            gen.writeStringField("effort", effort);
        }
        if (thinking.summaryMode() != null && !thinking.summaryMode().isBlank()) {
            gen.writeStringField("summary", thinking.summaryMode());
        } else if (Boolean.TRUE.equals(thinking.includeThoughts())) {
            gen.writeStringField("summary", "auto");
        }
        gen.writeEndObject();
    }

    // ---- Usage & status helpers ----

    static Optional<TokenUsage> parseUsageNode(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Optional.empty();
        }
        int input = Math.max(0, usage.path("input_tokens").asInt(0));
        int output = Math.max(0, usage.path("output_tokens").asInt(0));
        int cachedRead = Math.max(0, usage.path("input_tokens_details").path("cached_tokens").asInt(0));
        int cachedWrite = Math.max(0, usage.path("input_tokens_details").path("cache_write_tokens").asInt(0));
        int reasoning = Math.max(0, usage.path("output_tokens_details").path("reasoning_tokens").asInt(0));
        return Optional.of(TokenUsage.fromOpenAiInclusive(input, cachedRead, cachedWrite, output, reasoning));
    }

    private ObjectNode encodeUsageNode(TokenUsage usage) {
        TokenUsage u = usage != null ? usage : TokenUsage.empty();
        ObjectNode node = mapper.createObjectNode();
        node.put("input_tokens", u.totalInputTokens());
        ObjectNode inDetails = mapper.createObjectNode();
        inDetails.put("cached_tokens", u.cachedReadTokens());
        if (u.cachedWriteTokens() > 0) {
            inDetails.put("cache_write_tokens", u.cachedWriteTokens());
        }
        node.set("input_tokens_details", inDetails);
        node.put("output_tokens", u.totalOutputTokens());
        ObjectNode outDetails = mapper.createObjectNode();
        outDetails.put("reasoning_tokens", u.reasoningTokens());
        node.set("output_tokens_details", outDetails);
        node.put("total_tokens", u.totalTokens());
        return node;
    }

    private static CanonicalStopReason mapStatusToStopReason(
            String status, String incompleteReason, List<CanonicalContentPart> parts) {
        boolean hasTools = parts.stream().anyMatch(p -> p instanceof CanonicalContentPart.ToolCallPart);
        boolean hasRefusal = parts.stream().anyMatch(p -> p instanceof CanonicalContentPart.RefusalPart);
        if ("incomplete".equals(status)) {
            return "content_filter".equals(incompleteReason)
                    ? CanonicalStopReason.CONTENT_FILTER
                    : CanonicalStopReason.MAX_TOKENS;
        }
        if ("failed".equals(status)) {
            return CanonicalStopReason.ERROR;
        }
        if (hasTools) {
            return CanonicalStopReason.TOOL_CALLS;
        }
        if (hasRefusal) {
            return CanonicalStopReason.REFUSAL;
        }
        return CanonicalStopReason.END_TURN;
    }

    private void applyStatusFields(ObjectNode root, CanonicalStopReason reason) {
        if (reason == CanonicalStopReason.MAX_TOKENS) {
            root.put("status", "incomplete");
            ObjectNode inc = mapper.createObjectNode();
            inc.put("reason", "max_output_tokens");
            root.set("incomplete_details", inc);
        } else if (reason == CanonicalStopReason.CONTENT_FILTER) {
            root.put("status", "incomplete");
            ObjectNode inc = mapper.createObjectNode();
            inc.put("reason", "content_filter");
            root.set("incomplete_details", inc);
        } else if (reason == CanonicalStopReason.ERROR || reason == CanonicalStopReason.MALFORMED_TOOL_CALL) {
            root.put("status", "failed");
            root.set("incomplete_details", NullNode.getInstance());
        } else {
            root.put("status", "completed");
            root.set("incomplete_details", NullNode.getInstance());
        }
    }

    // ---- Streaming Decoder ----

    private final class OpenAiResponsesStreamDecoder implements UpstreamStreamDecoder {

        private final String virtualModel;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private boolean started;
        private boolean finished;
        private int nextBlockIndex;
        private final Map<Integer, ItemState> outputIndexMap = new HashMap<>();
        private boolean sawToolCall;
        private boolean sawRefusal;

        private record ItemState(int blockIndex, BlockKind kind, String callId) {
        }

        private OpenAiResponsesStreamDecoder(String virtualModel) {
            this.virtualModel = virtualModel;
        }

        @Override
        public List<CanonicalStreamEvent> feed(byte[] upstreamChunk) {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            for (SseEventSplitter.SseEvent event : splitter.push(upstreamChunk)) {
                processEvent(event, out);
            }
            return out;
        }

        @Override
        public List<CanonicalStreamEvent> complete() {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            for (SseEventSplitter.SseEvent event : splitter.flushRemainder()) {
                processEvent(event, out);
            }
            if (!finished && started) {
                finished = true;
                out.add(new CanonicalStreamEvent.MessageFinish(
                        sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN, null));
            }
            return out;
        }

        private void processEvent(SseEventSplitter.SseEvent event, List<CanonicalStreamEvent> out) {
            String data = event.dataPayload();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            try {
                JsonNode root = mapper.readTree(data);
                if (!root.isObject()) {
                    return;
                }
                String type = root.path("type").asText(event.eventName());
                switch (type) {
                    case "response.created" -> {
                        if (!started) {
                            JsonNode resp = root.path("response");
                            String id = resp.path("id").asText(CodecSupport.generateId("resp_"));
                            long created = resp.path("created_at").asLong(CodecSupport.nowEpochSeconds());
                            String model = (virtualModel != null && !virtualModel.isBlank())
                                    ? virtualModel : resp.path("model").asText("");
                            out.add(new CanonicalStreamEvent.MessageStart(id, model, created));
                            started = true;
                        }
                    }
                    case "response.output_item.added" -> {
                        ensureStarted(out);
                        int outputIdx = root.path("output_index").asInt(0);
                        JsonNode item = root.path("item");
                        String itemType = item.path("type").asText("");
                        int blockIdx = nextBlockIndex++;
                        switch (itemType) {
                            case "message" -> {
                                outputIndexMap.put(outputIdx, new ItemState(blockIdx, BlockKind.TEXT, null));
                                out.add(new CanonicalStreamEvent.BlockStart(blockIdx, BlockKind.TEXT));
                            }
                            case "reasoning" -> {
                                outputIndexMap.put(outputIdx, new ItemState(blockIdx, BlockKind.THINKING, null));
                                out.add(new CanonicalStreamEvent.BlockStart(blockIdx, BlockKind.THINKING));
                            }
                            case "function_call" -> {
                                sawToolCall = true;
                                String callId = CodecSupport.asNullableText(item, "call_id");
                                String itemId = CodecSupport.asNullableText(item, "id");
                                if (callId == null || callId.isBlank()) {
                                    callId = itemId != null ? itemId : CodecSupport.generateId("call_");
                                }
                                String name = item.path("name").asText("");
                                outputIndexMap.put(outputIdx, new ItemState(blockIdx, BlockKind.TOOL_CALL, callId));
                                out.add(new CanonicalStreamEvent.ToolCallStart(
                                        blockIdx, callId, itemId, name, null, ProtocolKind.OPENAI_RESPONSES));
                            }
                            default -> {}
                        }
                    }
                    case "response.output_text.delta" -> {
                        ensureStarted(out);
                        ItemState state = ensureItemState(root.path("output_index").asInt(0), BlockKind.TEXT, out);
                        String delta = root.path("delta").asText("");
                        if (!delta.isEmpty()) {
                            out.add(new CanonicalStreamEvent.TextDelta(state.blockIndex(), delta));
                        }
                    }
                    case "response.refusal.delta" -> {
                        ensureStarted(out);
                        sawRefusal = true;
                        ItemState state = ensureItemState(root.path("output_index").asInt(0), BlockKind.REFUSAL, out);
                        String delta = root.path("delta").asText("");
                        if (!delta.isEmpty()) {
                            out.add(new CanonicalStreamEvent.RefusalDelta(state.blockIndex(), delta));
                        }
                    }
                    case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                        ensureStarted(out);
                        ItemState state = ensureItemState(root.path("output_index").asInt(0), BlockKind.THINKING, out);
                        String delta = root.path("delta").asText("");
                        if (!delta.isEmpty()) {
                            out.add(new CanonicalStreamEvent.ThinkingDelta(state.blockIndex(), delta));
                        }
                    }
                    case "response.function_call_arguments.delta" -> {
                        int outputIdx = root.path("output_index").asInt(0);
                        ItemState state = outputIndexMap.get(outputIdx);
                        String delta = root.path("delta").asText("");
                        if (state != null && !delta.isEmpty()) {
                            out.add(new CanonicalStreamEvent.ToolCallArgsDelta(state.blockIndex(), state.callId(), delta));
                        }
                    }
                    case "response.output_item.done" -> {
                        int outputIdx = root.path("output_index").asInt(0);
                        ItemState state = outputIndexMap.remove(outputIdx);
                        if (state != null) {
                            out.add(new CanonicalStreamEvent.BlockStop(state.blockIndex()));
                        }
                    }
                    case "response.completed", "response.incomplete" -> {
                        JsonNode resp = root.path("response");
                        parseUsageNode(resp.get("usage"))
                                .ifPresent(u -> out.add(new CanonicalStreamEvent.UsageUpdate(u, true)));
                        for (ItemState leftover : outputIndexMap.values()) {
                            out.add(new CanonicalStreamEvent.BlockStop(leftover.blockIndex()));
                        }
                        outputIndexMap.clear();
                        CanonicalStopReason stopReason;
                        if ("response.incomplete".equals(type)) {
                            String reason = resp.path("incomplete_details").path("reason").asText("");
                            stopReason = "content_filter".equals(reason)
                                    ? CanonicalStopReason.CONTENT_FILTER : CanonicalStopReason.MAX_TOKENS;
                        } else if (sawToolCall || hasFunctionCallOutput(resp.get("output"))) {
                            stopReason = CanonicalStopReason.TOOL_CALLS;
                        } else if (sawRefusal) {
                            stopReason = CanonicalStopReason.REFUSAL;
                        } else {
                            stopReason = CanonicalStopReason.END_TURN;
                        }
                        finished = true;
                        out.add(new CanonicalStreamEvent.MessageFinish(stopReason, null));
                    }
                    case "response.failed", "error" -> {
                        String msg = root.path("response").path("error").path("message")
                                .asText(root.path("message").asText("Responses stream failed"));
                        out.add(new CanonicalStreamEvent.StreamError("server_error", msg));
                        finished = true;
                    }
                    default -> {}
                }
            } catch (Exception ignored) {
            }
        }

        private void ensureStarted(List<CanonicalStreamEvent> out) {
            if (!started) {
                started = true;
                out.add(new CanonicalStreamEvent.MessageStart(
                        CodecSupport.generateId("resp_"), virtualModel, CodecSupport.nowEpochSeconds()));
            }
        }

        private ItemState ensureItemState(int outputIdx, BlockKind kind, List<CanonicalStreamEvent> out) {
            ItemState existing = outputIndexMap.get(outputIdx);
            if (existing != null) {
                return existing;
            }
            int blockIdx = nextBlockIndex++;
            ItemState created = new ItemState(blockIdx, kind, null);
            outputIndexMap.put(outputIdx, created);
            out.add(new CanonicalStreamEvent.BlockStart(blockIdx, kind));
            return created;
        }

        private static boolean hasFunctionCallOutput(JsonNode output) {
            if (output == null || !output.isArray()) {
                return false;
            }
            for (JsonNode item : output) {
                if ("function_call".equals(item.path("type").asText())) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---- Streaming Encoder ----

    private final class OpenAiResponsesStreamEncoder implements DownstreamStreamEncoder {

        private final String model;
        private String responseId = CodecSupport.generateId("resp_");
        private long created = CodecSupport.nowEpochSeconds();
        private boolean initialized;
        private boolean finished;
        private int seq;
        private int nextOutputIndex;
        private final Map<Integer, ActiveItem> activeItems = new HashMap<>();
        private final List<ObjectNode> completedItems = new ArrayList<>();
        private TokenUsage latestUsage = TokenUsage.empty();

        private static final class ActiveItem {
            final int outputIndex;
            final BlockKind kind;
            final String itemId;
            final String callId;
            final String toolName;
            final StringBuilder buffer = new StringBuilder();
            boolean partAdded;

            ActiveItem(int outputIndex, BlockKind kind, String itemId, String callId, String toolName) {
                this.outputIndex = outputIndex;
                this.kind = kind;
                this.itemId = itemId;
                this.callId = callId;
                this.toolName = toolName;
            }
        }

        private OpenAiResponsesStreamEncoder(StreamRequestMetadata inboundMeta) {
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
                    if (start.createdEpochSeconds() > 0) {
                        this.created = start.createdEpochSeconds();
                    }
                    ensureInitialized(frames);
                }
                case CanonicalStreamEvent.BlockStart bs -> {
                    ensureInitialized(frames);
                    if (bs.kind() == BlockKind.TEXT || bs.kind() == BlockKind.THINKING || bs.kind() == BlockKind.REFUSAL) {
                        openItem(bs.blockIndex(), bs.kind(), null, null, null, frames);
                    }
                }
                case CanonicalStreamEvent.TextDelta td -> {
                    ensureInitialized(frames);
                    ActiveItem item = activeItems.get(td.blockIndex());
                    if (item == null) {
                        item = openItem(td.blockIndex(), BlockKind.TEXT, null, null, null, frames);
                    }
                    item.buffer.append(td.textDelta());
                    ObjectNode ev = mapper.createObjectNode();
                    ev.put("type", "response.output_text.delta");
                    ev.put("sequence_number", seq++);
                    ev.put("item_id", item.itemId);
                    ev.put("output_index", item.outputIndex);
                    ev.put("content_index", 0);
                    ev.put("delta", td.textDelta());
                    frames.add(SseEventSplitter.buildNamedEventFrame("response.output_text.delta", CodecSupport.writeJsonString(mapper, ev)));
                }
                case CanonicalStreamEvent.RefusalDelta ref -> {
                    ensureInitialized(frames);
                    ActiveItem item = activeItems.get(ref.blockIndex());
                    if (item == null) {
                        item = openItem(ref.blockIndex(), BlockKind.REFUSAL, null, null, null, frames);
                    }
                    item.buffer.append(ref.refusalDelta());
                    ObjectNode ev = mapper.createObjectNode();
                    ev.put("type", "response.refusal.delta");
                    ev.put("sequence_number", seq++);
                    ev.put("item_id", item.itemId);
                    ev.put("output_index", item.outputIndex);
                    ev.put("content_index", 0);
                    ev.put("delta", ref.refusalDelta());
                    frames.add(SseEventSplitter.buildNamedEventFrame("response.refusal.delta", CodecSupport.writeJsonString(mapper, ev)));
                }
                case CanonicalStreamEvent.ThinkingDelta th -> {
                    ensureInitialized(frames);
                    ActiveItem item = activeItems.get(th.blockIndex());
                    if (item == null) {
                        item = openItem(th.blockIndex(), BlockKind.THINKING, null, null, null, frames);
                    }
                    item.buffer.append(th.thinkingDelta());
                    ObjectNode ev = mapper.createObjectNode();
                    ev.put("type", "response.reasoning_summary_text.delta");
                    ev.put("sequence_number", seq++);
                    ev.put("item_id", item.itemId);
                    ev.put("output_index", item.outputIndex);
                    ev.put("summary_index", 0);
                    ev.put("delta", th.thinkingDelta());
                    frames.add(SseEventSplitter.buildNamedEventFrame("response.reasoning_summary_text.delta",
                            CodecSupport.writeJsonString(mapper, ev)));
                }
                case CanonicalStreamEvent.ToolCallStart tcs -> {
                    ensureInitialized(frames);
                    openItem(tcs.blockIndex(), BlockKind.TOOL_CALL,
                            tcs.itemId() != null ? tcs.itemId() : CodecSupport.generateId("fc_"),
                            tcs.callId() != null ? tcs.callId() : CodecSupport.generateId("call_"),
                            tcs.name(), frames);
                }
                case CanonicalStreamEvent.ToolCallArgsDelta tcad -> {
                    ActiveItem item = activeItems.get(tcad.blockIndex());
                    if (item != null) {
                        item.buffer.append(tcad.partialJsonDelta());
                        ObjectNode ev = mapper.createObjectNode();
                        ev.put("type", "response.function_call_arguments.delta");
                        ev.put("sequence_number", seq++);
                        ev.put("item_id", item.itemId);
                        ev.put("output_index", item.outputIndex);
                        ev.put("delta", tcad.partialJsonDelta());
                        frames.add(SseEventSplitter.buildNamedEventFrame("response.function_call_arguments.delta",
                                CodecSupport.writeJsonString(mapper, ev)));
                    }
                }
                case CanonicalStreamEvent.BlockStop stop -> closeItem(stop.blockIndex(), frames);
                case CanonicalStreamEvent.UsageUpdate uu -> {
                    latestUsage = uu.cumulative() ? uu.usage() : latestUsage.mergeMax(uu.usage());
                }
                case CanonicalStreamEvent.MessageFinish mf -> emitTerminal(mf.stopReason(), frames);
                case CanonicalStreamEvent.StreamError err -> {
                    ensureInitialized(frames);
                    ObjectNode ev = mapper.createObjectNode();
                    ev.put("type", "error");
                    ev.put("sequence_number", seq++);
                    ev.put("message", err.message() != null ? err.message() : "Upstream stream error");
                    frames.add(SseEventSplitter.buildNamedEventFrame("error", CodecSupport.writeJsonString(mapper, ev)));
                    finished = true;
                }
                case CanonicalStreamEvent.ThinkingSignatureDelta ignored -> {}
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
            emitTerminal(CanonicalStopReason.END_TURN, frames);
            return frames;
        }

        private void ensureInitialized(List<byte[]> frames) {
            if (initialized) {
                return;
            }
            initialized = true;
            ObjectNode initialResp = buildResponseEnvelope("in_progress", null, false);
            emitResponseLifecycle("response.created", initialResp, frames);
            emitResponseLifecycle("response.in_progress", initialResp, frames);
        }

        private ActiveItem openItem(
                int blockIdx, BlockKind kind, String itemId, String callId, String toolName, List<byte[]> frames) {
            String resolvedItemId = itemId != null ? itemId : switch (kind) {
                case THINKING, REDACTED_THINKING -> CodecSupport.generateId("rs_");
                case TOOL_CALL -> CodecSupport.generateId("fc_");
                default -> CodecSupport.generateId("msg_");
            };
            int outIdx = nextOutputIndex++;
            ActiveItem item = new ActiveItem(outIdx, kind, resolvedItemId, callId, toolName);
            activeItems.put(blockIdx, item);

            ObjectNode addedItem = mapper.createObjectNode();
            addedItem.put("id", resolvedItemId);
            addedItem.put("status", "in_progress");
            switch (kind) {
                case TEXT, REFUSAL -> {
                    addedItem.put("type", "message");
                    addedItem.put("role", "assistant");
                    addedItem.set("content", mapper.createArrayNode());
                }
                case THINKING, REDACTED_THINKING -> {
                    addedItem.put("type", "reasoning");
                    addedItem.set("summary", mapper.createArrayNode());
                }
                case TOOL_CALL -> {
                    addedItem.put("type", "function_call");
                    addedItem.put("call_id", callId);
                    addedItem.put("name", toolName);
                    addedItem.put("arguments", "");
                }
            }
            ObjectNode ev = mapper.createObjectNode();
            ev.put("type", "response.output_item.added");
            ev.put("sequence_number", seq++);
            ev.put("output_index", outIdx);
            ev.set("item", addedItem);
            frames.add(SseEventSplitter.buildNamedEventFrame("response.output_item.added", CodecSupport.writeJsonString(mapper, ev)));

            if (kind == BlockKind.TEXT || kind == BlockKind.REFUSAL) {
                item.partAdded = true;
                ObjectNode part = mapper.createObjectNode();
                if (kind == BlockKind.TEXT) {
                    part.put("type", "output_text");
                    part.put("text", "");
                    part.set("annotations", mapper.createArrayNode());
                } else {
                    part.put("type", "refusal");
                    part.put("refusal", "");
                }
                ObjectNode partEv = mapper.createObjectNode();
                partEv.put("type", "response.content_part.added");
                partEv.put("sequence_number", seq++);
                partEv.put("item_id", resolvedItemId);
                partEv.put("output_index", outIdx);
                partEv.put("content_index", 0);
                partEv.set("part", part);
                frames.add(SseEventSplitter.buildNamedEventFrame("response.content_part.added",
                        CodecSupport.writeJsonString(mapper, partEv)));
            } else if (kind == BlockKind.THINKING) {
                item.partAdded = true;
                ObjectNode part = mapper.createObjectNode();
                part.put("type", "summary_text");
                part.put("text", "");
                ObjectNode partEv = mapper.createObjectNode();
                partEv.put("type", "response.reasoning_summary_part.added");
                partEv.put("sequence_number", seq++);
                partEv.put("item_id", resolvedItemId);
                partEv.put("output_index", outIdx);
                partEv.put("summary_index", 0);
                partEv.set("part", part);
                frames.add(SseEventSplitter.buildNamedEventFrame("response.reasoning_summary_part.added",
                        CodecSupport.writeJsonString(mapper, partEv)));
            }
            return item;
        }

        private void closeItem(int blockIdx, List<byte[]> frames) {
            ActiveItem item = activeItems.remove(blockIdx);
            if (item == null) {
                return;
            }
            ObjectNode doneItem = mapper.createObjectNode();
            doneItem.put("id", item.itemId);
            doneItem.put("status", "completed");

            switch (item.kind) {
                case TEXT -> {
                    String full = item.buffer.toString();
                    ObjectNode textDone = mapper.createObjectNode();
                    textDone.put("type", "response.output_text.done");
                    textDone.put("sequence_number", seq++);
                    textDone.put("item_id", item.itemId);
                    textDone.put("output_index", item.outputIndex);
                    textDone.put("content_index", 0);
                    textDone.put("text", full);
                    frames.add(SseEventSplitter.buildNamedEventFrame("response.output_text.done",
                            CodecSupport.writeJsonString(mapper, textDone)));

                    ObjectNode part = mapper.createObjectNode();
                    part.put("type", "output_text");
                    part.put("text", full);
                    part.set("annotations", mapper.createArrayNode());
                    ObjectNode partDone = mapper.createObjectNode();
                    partDone.put("type", "response.content_part.done");
                    partDone.put("sequence_number", seq++);
                    partDone.put("item_id", item.itemId);
                    partDone.put("output_index", item.outputIndex);
                    partDone.put("content_index", 0);
                    partDone.set("part", part);
                    frames.add(SseEventSplitter.buildNamedEventFrame("response.content_part.done",
                            CodecSupport.writeJsonString(mapper, partDone)));

                    doneItem.put("type", "message");
                    doneItem.put("role", "assistant");
                    ArrayNode content = mapper.createArrayNode();
                    content.add(part);
                    doneItem.set("content", content);
                }
                case REFUSAL -> {
                    String full = item.buffer.toString();
                    ObjectNode refDone = mapper.createObjectNode();
                    refDone.put("type", "response.refusal.done");
                    refDone.put("sequence_number", seq++);
                    refDone.put("item_id", item.itemId);
                    refDone.put("output_index", item.outputIndex);
                    refDone.put("content_index", 0);
                    refDone.put("refusal", full);
                    frames.add(SseEventSplitter.buildNamedEventFrame("response.refusal.done",
                            CodecSupport.writeJsonString(mapper, refDone)));

                    ObjectNode part = mapper.createObjectNode();
                    part.put("type", "refusal");
                    part.put("refusal", full);
                    doneItem.put("type", "message");
                    doneItem.put("role", "assistant");
                    ArrayNode content = mapper.createArrayNode();
                    content.add(part);
                    doneItem.set("content", content);
                }
                case THINKING, REDACTED_THINKING -> {
                    String full = item.buffer.toString();
                    ObjectNode sumDone = mapper.createObjectNode();
                    sumDone.put("type", "response.reasoning_summary_text.done");
                    sumDone.put("sequence_number", seq++);
                    sumDone.put("item_id", item.itemId);
                    sumDone.put("output_index", item.outputIndex);
                    sumDone.put("summary_index", 0);
                    sumDone.put("text", full);
                    frames.add(SseEventSplitter.buildNamedEventFrame("response.reasoning_summary_text.done",
                            CodecSupport.writeJsonString(mapper, sumDone)));

                    ObjectNode part = mapper.createObjectNode();
                    part.put("type", "summary_text");
                    part.put("text", full);
                    doneItem.put("type", "reasoning");
                    ArrayNode summary = mapper.createArrayNode();
                    summary.add(part);
                    doneItem.set("summary", summary);
                }
                case TOOL_CALL -> {
                    String args = item.buffer.isEmpty() ? "{}" : item.buffer.toString();
                    ObjectNode argsDone = mapper.createObjectNode();
                    argsDone.put("type", "response.function_call_arguments.done");
                    argsDone.put("sequence_number", seq++);
                    argsDone.put("item_id", item.itemId);
                    argsDone.put("output_index", item.outputIndex);
                    argsDone.put("arguments", args);
                    frames.add(SseEventSplitter.buildNamedEventFrame("response.function_call_arguments.done",
                            CodecSupport.writeJsonString(mapper, argsDone)));

                    doneItem.put("type", "function_call");
                    doneItem.put("call_id", item.callId);
                    doneItem.put("name", item.toolName);
                    doneItem.put("arguments", args);
                }
            }

            completedItems.add(doneItem);
            ObjectNode itemDoneEv = mapper.createObjectNode();
            itemDoneEv.put("type", "response.output_item.done");
            itemDoneEv.put("sequence_number", seq++);
            itemDoneEv.put("output_index", item.outputIndex);
            itemDoneEv.set("item", doneItem);
            frames.add(SseEventSplitter.buildNamedEventFrame("response.output_item.done",
                    CodecSupport.writeJsonString(mapper, itemDoneEv)));
        }

        private void emitTerminal(CanonicalStopReason reason, List<byte[]> frames) {
            if (finished) {
                return;
            }
            ensureInitialized(frames);
            for (Integer key : List.copyOf(activeItems.keySet())) {
                closeItem(key, frames);
            }
            finished = true;
            boolean incomplete = (reason == CanonicalStopReason.MAX_TOKENS || reason == CanonicalStopReason.CONTENT_FILTER);
            String eventType = incomplete ? "response.incomplete" : "response.completed";
            String status = incomplete ? "incomplete" : "completed";
            ObjectNode finalResp = buildResponseEnvelope(status, reason, true);
            emitResponseLifecycle(eventType, finalResp, frames);
        }

        private ObjectNode buildResponseEnvelope(String status, CanonicalStopReason reason, boolean includeOutputAndUsage) {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("id", responseId);
            resp.put("object", "response");
            resp.put("created_at", created);
            resp.put("status", status);
            resp.put("model", model);
            if ("incomplete".equals(status)) {
                ObjectNode inc = mapper.createObjectNode();
                inc.put("reason", reason == CanonicalStopReason.CONTENT_FILTER ? "content_filter" : "max_output_tokens");
                resp.set("incomplete_details", inc);
            } else {
                resp.set("incomplete_details", NullNode.getInstance());
            }
            ArrayNode output = mapper.createArrayNode();
            if (includeOutputAndUsage) {
                for (ObjectNode item : completedItems) {
                    output.add(item);
                }
                resp.set("usage", encodeUsageNode(latestUsage));
            }
            resp.set("output", output);
            return resp;
        }

        private void emitResponseLifecycle(String eventType, ObjectNode resp, List<byte[]> frames) {
            ObjectNode ev = mapper.createObjectNode();
            ev.put("type", eventType);
            ev.put("sequence_number", seq++);
            ev.set("response", resp);
            frames.add(SseEventSplitter.buildNamedEventFrame(eventType, CodecSupport.writeJsonString(mapper, ev)));
        }
    }


}
