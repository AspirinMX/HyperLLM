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
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UnsupportedFeatureFlag;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.StreamBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
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
        ObjectNode root = CodecSupport.parseObjectRoot(mapper, rawBody);
        String model = CodecSupport.requireModel(root);
        boolean stream = root.path("stream").asBoolean(false);

        List<UnsupportedFeatureFlag> unsupported = new ArrayList<>();
        if (root.hasNonNull("previous_response_id") && !root.get("previous_response_id").asText("").isBlank()) {
            unsupported.add(UnsupportedFeatureFlag.hard("previous_response_id",
                    "Server-side response chaining requires OpenAI Responses passthrough"));
        }
        if (root.hasNonNull("conversation")) {
            unsupported.add(UnsupportedFeatureFlag.hard("conversation",
                    "Server-side conversation state requires OpenAI Responses passthrough"));
        }
        if (root.path("background").asBoolean(false)) {
            unsupported.add(UnsupportedFeatureFlag.hard("background",
                    "Asynchronous background execution requires OpenAI Responses passthrough"));
        }

        List<CanonicalSystemPart> systemParts = new ArrayList<>();
        String instructions = CodecSupport.asNullableText(root, "instructions");
        if (instructions != null && !instructions.isBlank()) {
            systemParts.add(new CanonicalSystemPart(instructions, true, null));
        }

        JsonNode inputNode = root.get("input");
        if (inputNode == null || inputNode.isNull()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'input' is required");
        }
        List<CanonicalTurn> turns = new ArrayList<>();
        decodeInputItems(inputNode, systemParts, turns, unsupported);

        List<CanonicalToolDefinition> tools = decodeTools(root.get("tools"), unsupported);
        CanonicalToolChoice toolChoice = decodeToolChoice(
                root.get("tool_choice"), CodecSupport.asNullableBoolean(root, "parallel_tool_calls"));
        CanonicalResponseFormat responseFormat = decodeTextFormat(root.path("text").get("format"));

        CanonicalGenerationConfig generation = new CanonicalGenerationConfig(
                CodecSupport.asNullableInt(root, "max_output_tokens"),
                CodecSupport.asNullableDouble(root, "temperature"),
                CodecSupport.asNullableDouble(root, "top_p"),
                null,
                List.of(),
                null,
                null,
                null,
                null
        );

        CanonicalThinkingConfig thinking = decodeReasoning(root.get("reasoning"));
        CanonicalCacheConfig cache = new CanonicalCacheConfig(
                null,
                CodecSupport.asNullableText(root, "prompt_cache_key"),
                CodecSupport.asNullableText(root, "prompt_cache_retention"),
                null
        );
        CanonicalProtocolOptions options = new CanonicalProtocolOptions(
                true,
                true,
                null,
                null,
                CodecSupport.asNullableText(root, "user"),
                CodecSupport.asNullableText(root, "service_tier"),
                CodecSupport.asNullableBoolean(root, "store")
        );

        return new CanonicalRequest(
                ProtocolKind.OPENAI_RESPONSES, model, stream, systemParts, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, unsupported
        );
    }

    @Override
    public byte[] preparePassthroughRequest(byte[] rawBody, CanonicalRequest decoded, UpstreamEncodingContext context) {
        ObjectNode root = CodecSupport.parseObjectRoot(mapper, rawBody);
        CodecSupport.rewriteModelIfPresent(root, context.upstreamModelName());
        return CodecSupport.writeJsonBytes(mapper, root);
    }

    @Override
    public byte[] encodeRequest(CanonicalRequest req, UpstreamEncodingContext context) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", context.upstreamModelName() != null && !context.upstreamModelName().isBlank()
                ? context.upstreamModelName() : req.model());
        root.put("stream", req.stream());

        if (!req.systemInstructions().isEmpty()) {
            StringBuilder sys = new StringBuilder();
            for (CanonicalSystemPart sp : req.systemInstructions()) {
                if (!sp.text().isEmpty()) {
                    if (!sys.isEmpty()) {
                        sys.append("\n\n");
                    }
                    sys.append(sp.text());
                }
            }
            if (!sys.isEmpty()) {
                root.put("instructions", sys.toString());
            }
        }

        ArrayNode inputArr = mapper.createArrayNode();
        for (CanonicalTurn turn : req.turns()) {
            encodeTurn(inputArr, turn);
        }
        root.set("input", inputArr);

        ProviderCapabilities caps = context.capabilitiesFor(ProtocolKind.OPENAI_RESPONSES);
        CanonicalGenerationConfig g = req.generation();
        if (g.maxOutputTokens() != null && g.maxOutputTokens() > 0) {
            root.put("max_output_tokens", g.maxOutputTokens());
        }
        boolean allowSampling = !(caps.forbidsSamplingWithReasoning() && context.policy() != TranslationPolicy.STRICT);
        if (allowSampling) {
            if (g.temperature() != null) {
                root.put("temperature", g.temperature());
            }
            if (g.topP() != null) {
                root.put("top_p", g.topP());
            }
        }

        encodeTools(root, req);
        encodeTextFormat(root, req.responseFormat());
        encodeReasoning(root, req.thinking());

        if (req.cache().promptCacheKey() != null && !req.cache().promptCacheKey().isBlank()) {
            root.put("prompt_cache_key", req.cache().promptCacheKey());
        }
        if (req.cache().promptCacheRetention() != null && !req.cache().promptCacheRetention().isBlank()) {
            root.put("prompt_cache_retention", req.cache().promptCacheRetention());
        }
        if (req.options().serviceTier() != null && !req.options().serviceTier().isBlank()) {
            root.put("service_tier", req.options().serviceTier());
        }
        if (req.options().store() != null) {
            root.put("store", req.options().store());
        }
        if (req.options().user() != null && !req.options().user().isBlank()) {
            root.put("user", req.options().user());
        }

        return CodecSupport.writeJsonBytes(mapper, root);
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
    public DownstreamStreamEncoder newStreamEncoder(CanonicalRequest inboundRequest) {
        return new OpenAiResponsesStreamEncoder(inboundRequest);
    }

    @Override
    public StreamBridge newPassthroughStreamBridge(CanonicalRequest request, TokenEstimator tokenEstimator) {
        return new OpenAiResponsesPassthroughBridge(request, tokenEstimator);
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

    // ---- Request encoding helpers ----

    private void encodeTurn(ArrayNode inputArr, CanonicalTurn turn) {
        switch (turn.role()) {
            case SYSTEM, DEVELOPER -> {
                ArrayNode content = mapper.createArrayNode();
                for (CanonicalContentPart p : turn.parts()) {
                    if (p instanceof CanonicalContentPart.TextPart t) {
                        ObjectNode tp = mapper.createObjectNode();
                        tp.put("type", "input_text");
                        tp.put("text", t.text());
                        content.add(tp);
                    }
                }
                ObjectNode msg = mapper.createObjectNode();
                msg.put("type", "message");
                msg.put("role", turn.role() == CanonicalRole.SYSTEM ? "system" : "developer");
                msg.set("content", content);
                inputArr.add(msg);
            }
            case USER, TOOL -> {
                ArrayNode userParts = mapper.createArrayNode();
                for (CanonicalContentPart p : turn.parts()) {
                    if (p instanceof CanonicalContentPart.ToolResultPart tr) {
                        if (!userParts.isEmpty()) {
                            inputArr.add(buildMessageInputItem("user", userParts));
                            userParts = mapper.createArrayNode();
                        }
                        inputArr.add(buildFunctionCallOutputItem(tr));
                    } else if (p instanceof CanonicalContentPart.TextPart t) {
                        ObjectNode tp = mapper.createObjectNode();
                        tp.put("type", "input_text");
                        tp.put("text", t.text());
                        userParts.add(tp);
                    } else if (p instanceof CanonicalContentPart.ImagePart img) {
                        userParts.add(buildInputImagePart(img));
                    }
                }
                if (!userParts.isEmpty()) {
                    inputArr.add(buildMessageInputItem("user", userParts));
                }
            }
            case ASSISTANT -> {
                ArrayNode assistantParts = mapper.createArrayNode();
                for (CanonicalContentPart p : turn.parts()) {
                    switch (p) {
                        case CanonicalContentPart.ThinkingPart th -> {
                            if (CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.OPENAI_RESPONSES)
                                    && th.encryptedContent() != null) {
                                ObjectNode r = mapper.createObjectNode();
                                r.put("type", "reasoning");
                                ArrayNode summary = mapper.createArrayNode();
                                if (!th.text().isEmpty()) {
                                    ObjectNode s = mapper.createObjectNode();
                                    s.put("type", "summary_text");
                                    s.put("text", th.text());
                                    summary.add(s);
                                }
                                r.set("summary", summary);
                                r.put("encrypted_content", th.encryptedContent());
                                inputArr.add(r);
                            }
                        }
                        case CanonicalContentPart.RedactedThinkingPart rt -> {
                            if (CodecSupport.isSameOrigin(rt.originProtocol(), ProtocolKind.OPENAI_RESPONSES)
                                    && rt.opaqueData() != null) {
                                ObjectNode r = mapper.createObjectNode();
                                r.put("type", "reasoning");
                                r.set("summary", mapper.createArrayNode());
                                r.put("encrypted_content", rt.opaqueData());
                                inputArr.add(r);
                            }
                        }
                        case CanonicalContentPart.TextPart t -> {
                            ObjectNode tp = mapper.createObjectNode();
                            tp.put("type", "output_text");
                            tp.put("text", t.text());
                            assistantParts.add(tp);
                        }
                        case CanonicalContentPart.RefusalPart ref -> {
                            ObjectNode rp = mapper.createObjectNode();
                            rp.put("type", "refusal");
                            rp.put("refusal", ref.refusal());
                            assistantParts.add(rp);
                        }
                        case CanonicalContentPart.ToolCallPart tc -> {
                            if (!assistantParts.isEmpty()) {
                                inputArr.add(buildMessageInputItem("assistant", assistantParts));
                                assistantParts = mapper.createArrayNode();
                            }
                            inputArr.add(buildFunctionCallItem(tc));
                        }
                        case CanonicalContentPart.ImagePart ignored -> {}
                        case CanonicalContentPart.ToolResultPart ignored -> {}
                    }
                }
                if (!assistantParts.isEmpty()) {
                    inputArr.add(buildMessageInputItem("assistant", assistantParts));
                }
            }
        }
    }

    private ObjectNode buildMessageInputItem(String role, ArrayNode content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("type", "message");
        msg.put("role", role);
        msg.set("content", content);
        return msg;
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

    private ObjectNode buildFunctionCallOutputItem(CanonicalContentPart.ToolResultPart tr) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", tr.callId() != null ? tr.callId() : "");
        boolean hasImage = tr.content().stream().anyMatch(c -> c instanceof CanonicalContentPart.ImagePart);
        if (!hasImage) {
            StringBuilder text = new StringBuilder();
            for (CanonicalContentPart c : tr.content()) {
                if (c instanceof CanonicalContentPart.TextPart t) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(t.text());
                }
            }
            item.put("output", tr.isError() && !text.isEmpty() ? "Error: " + text : text.toString());
        } else {
            ArrayNode arr = mapper.createArrayNode();
            for (CanonicalContentPart c : tr.content()) {
                if (c instanceof CanonicalContentPart.TextPart t) {
                    ObjectNode tp = mapper.createObjectNode();
                    tp.put("type", "input_text");
                    tp.put("text", tr.isError() ? "Error: " + t.text() : t.text());
                    arr.add(tp);
                } else if (c instanceof CanonicalContentPart.ImagePart img) {
                    arr.add(buildInputImagePart(img));
                }
            }
            item.set("output", arr);
        }
        return item;
    }

    private ObjectNode buildInputImagePart(CanonicalContentPart.ImagePart img) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "input_image");
        node.put("detail", (img.detail() != null ? img.detail() : ImageDetail.AUTO).toOpenAiValue());
        switch (img.source()) {
            case ImageSource.Base64Image b64 -> node.put("image_url", b64.toDataUri());
            case ImageSource.UrlImage u -> node.put("image_url", u.url());
            case ImageSource.FileRefImage ref -> node.put("file_id", ref.fileRef());
        }
        return node;
    }

    private void encodeTools(ObjectNode root, CanonicalRequest req) {
        if (req.tools().isEmpty()) {
            return;
        }
        ArrayNode arr = mapper.createArrayNode();
        for (CanonicalToolDefinition tool : req.tools()) {
            ObjectNode t = mapper.createObjectNode();
            t.put("type", "function");
            t.put("name", tool.name());
            if (tool.description() != null) {
                t.put("description", tool.description());
            }
            if (tool.parametersSchema() != null && tool.parametersSchema().isObject()) {
                t.set("parameters", tool.parametersSchema().deepCopy());
            } else {
                ObjectNode emptySchema = mapper.createObjectNode();
                emptySchema.put("type", "object");
                emptySchema.set("properties", mapper.createObjectNode());
                t.set("parameters", emptySchema);
            }
            if (tool.strict() != null) {
                t.put("strict", tool.strict());
            }
            arr.add(t);
        }
        root.set("tools", arr);

        CanonicalToolChoice tc = req.toolChoice();
        switch (tc.mode()) {
            case NONE -> root.put("tool_choice", "none");
            case AUTO -> root.put("tool_choice", "auto");
            case REQUIRED -> root.put("tool_choice", "required");
            case SPECIFIC -> {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("type", "function");
                obj.put("name", tc.specificToolName());
                root.set("tool_choice", obj);
            }
            case OMITTED -> {}
        }
        if (tc.parallelToolCalls() != null) {
            root.put("parallel_tool_calls", tc.parallelToolCalls());
        }
    }

    private void encodeTextFormat(ObjectNode root, CanonicalResponseFormat format) {
        if (format == null) {
            return;
        }
        ObjectNode fmt = mapper.createObjectNode();
        switch (format) {
            case CanonicalResponseFormat.TextFormat ignored -> fmt.put("type", "text");
            case CanonicalResponseFormat.JsonObjectFormat ignored -> fmt.put("type", "json_object");
            case CanonicalResponseFormat.JsonSchemaFormat js -> {
                fmt.put("type", "json_schema");
                fmt.put("name", js.name());
                if (js.description() != null) {
                    fmt.put("description", js.description());
                }
                if (js.schema() != null) {
                    fmt.set("schema", js.schema().deepCopy());
                }
                if (js.strict() != null) {
                    fmt.put("strict", js.strict());
                }
            }
        }
        ObjectNode textNode = mapper.createObjectNode();
        textNode.set("format", fmt);
        root.set("text", textNode);
    }

    private void encodeReasoning(ObjectNode root, CanonicalThinkingConfig thinking) {
        String effort = CodecSupport.toOpenAiReasoningEffort(thinking);
        if (effort == null && thinking.summaryMode() == null && !Boolean.TRUE.equals(thinking.includeThoughts())) {
            return;
        }
        ObjectNode r = mapper.createObjectNode();
        if (effort != null) {
            r.put("effort", effort);
        }
        if (thinking.summaryMode() != null && !thinking.summaryMode().isBlank()) {
            r.put("summary", thinking.summaryMode());
        } else if (Boolean.TRUE.equals(thinking.includeThoughts())) {
            r.put("summary", "auto");
        }
        root.set("reasoning", r);
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

        private OpenAiResponsesStreamEncoder(CanonicalRequest inboundRequest) {
            this.model = inboundRequest.model();
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

    // ---- Passthrough Streaming Bridge ----

    private final class OpenAiResponsesPassthroughBridge implements StreamBridge {

        private final CanonicalRequest request;
        private final TokenEstimator tokenEstimator;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private final StringBuilder streamedText = new StringBuilder();
        private TokenUsage observedUsage;

        private OpenAiResponsesPassthroughBridge(CanonicalRequest request, TokenEstimator tokenEstimator) {
            this.request = request;
            this.tokenEstimator = tokenEstimator;
        }

        @Override
        public List<byte[]> feed(byte[] upstreamChunk) {
            return inspectEvents(splitter.push(upstreamChunk));
        }

        @Override
        public List<byte[]> complete() {
            return inspectEvents(splitter.flushRemainder());
        }

        @Override
        public TokenUsage finalizeUsage(boolean cleanCompletion) {
            if (cleanCompletion && observedUsage != null) {
                return observedUsage;
            }
            if (observedUsage != null && observedUsage.totalOutputTokens() > 0) {
                return observedUsage;
            }
            int estPrompt = (observedUsage != null && observedUsage.totalInputTokens() > 0)
                    ? observedUsage.promptTokens()
                    : tokenEstimator.estimatePromptTokens(request);
            int estCompletion = tokenEstimator.estimateCompletionTokens(request.model(), streamedText);
            return new TokenUsage(
                    estPrompt,
                    observedUsage == null ? 0 : observedUsage.cachedReadTokens(),
                    observedUsage == null ? 0 : observedUsage.cachedWriteTokens(),
                    estCompletion,
                    observedUsage == null ? 0 : observedUsage.reasoningTokens(),
                    true
            );
        }

        private List<byte[]> inspectEvents(List<SseEventSplitter.SseEvent> events) {
            if (events.isEmpty()) {
                return List.of();
            }
            List<byte[]> out = new ArrayList<>(events.size());
            for (SseEventSplitter.SseEvent ev : events) {
                inspectPayload(ev);
                out.add(ev.rawBytes());
            }
            return out;
        }

        private void inspectPayload(SseEventSplitter.SseEvent ev) {
            String data = ev.dataPayload();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            try {
                JsonNode root = mapper.readTree(data);
                if (!root.isObject()) {
                    return;
                }
                String type = root.path("type").asText(ev.eventName());
                if ("response.output_text.delta".equals(type)
                        || "response.reasoning_summary_text.delta".equals(type)
                        || "response.reasoning_text.delta".equals(type)
                        || "response.function_call_arguments.delta".equals(type)) {
                    CodecSupport.appendBounded(streamedText, root.path("delta").asText(""));
                } else if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
                    parseUsageNode(root.path("response").get("usage")).ifPresent(u -> observedUsage = u);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
