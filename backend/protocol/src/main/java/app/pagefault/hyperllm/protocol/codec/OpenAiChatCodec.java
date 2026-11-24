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
 * Bidirectional codec for OpenAI Chat Completions ({@code POST /v1/chat/completions}).
 */
public final class OpenAiChatCodec implements ProtocolCodec {

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
        ObjectNode root = CodecSupport.parseObjectRoot(mapper, rawBody);
        return decodeRoot(root);
    }

    @Override
    public byte[] preparePassthroughRequest(byte[] rawBody, CanonicalRequest decoded, UpstreamEncodingContext context) {
        ObjectNode root = CodecSupport.parseObjectRoot(mapper, rawBody);
        CodecSupport.rewriteModelIfPresent(root, context.upstreamModelName());
        if (decoded.stream() && !decoded.clientRequestedUsage()) {
            JsonNode streamOptions = root.get("stream_options");
            ObjectNode optionsObj = (streamOptions instanceof ObjectNode existing)
                    ? existing
                    : mapper.createObjectNode();
            optionsObj.put("include_usage", true);
            root.set("stream_options", optionsObj);
        }
        return CodecSupport.writeJsonBytes(mapper, root);
    }

    @Override
    public byte[] encodeRequest(CanonicalRequest req, UpstreamEncodingContext context) {
        ProviderCapabilities caps = context.capabilitiesFor(ProtocolKind.OPENAI_CHAT);
        ObjectNode root = mapper.createObjectNode();
        root.put("model", context.upstreamModelName() != null && !context.upstreamModelName().isBlank()
                ? context.upstreamModelName() : req.model());
        root.put("stream", req.stream());
        if (req.stream()) {
            ObjectNode streamOpts = mapper.createObjectNode();
            streamOpts.put("include_usage", true);
            root.set("stream_options", streamOpts);
        }

        ArrayNode messagesArr = mapper.createArrayNode();
        for (CanonicalSystemPart sys : req.systemInstructions()) {
            if (!sys.text().isEmpty()) {
                ObjectNode msg = mapper.createObjectNode();
                msg.put("role", (sys.developerPriority() && caps.supportsDeveloperRole()) ? "developer" : "system");
                msg.put("content", sys.text());
                messagesArr.add(msg);
            }
        }

        for (CanonicalTurn turn : req.turns()) {
            encodeTurn(messagesArr, turn, context.policy(), caps);
        }
        if (messagesArr.isEmpty()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Translated messages array cannot be empty");
        }
        root.set("messages", messagesArr);

        encodeGenerationConfig(root, req, caps, context.policy());
        encodeTools(root, req, caps);
        encodeResponseFormat(root, req.responseFormat());

        if (caps.supportsReasoningEffort()) {
            String effort = CodecSupport.toOpenAiReasoningEffort(req.thinking());
            if (effort != null) {
                root.put("reasoning_effort", effort);
            }
        }
        if (req.cache().promptCacheKey() != null && !req.cache().promptCacheKey().isBlank()) {
            root.put("prompt_cache_key", req.cache().promptCacheKey());
        }
        if (req.cache().promptCacheRetention() != null && !req.cache().promptCacheRetention().isBlank()) {
            root.put("prompt_cache_retention", req.cache().promptCacheRetention());
        }
        if (req.options().user() != null && !req.options().user().isBlank()) {
            root.put("user", req.options().user());
        }
        if (req.options().serviceTier() != null && !req.options().serviceTier().isBlank()) {
            root.put("service_tier", req.options().serviceTier());
        }
        if (req.options().store() != null) {
            root.put("store", req.options().store());
        }

        return CodecSupport.writeJsonBytes(mapper, root);
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
    public DownstreamStreamEncoder newStreamEncoder(CanonicalRequest inboundRequest) {
        return new OpenAiChatStreamEncoder(inboundRequest);
    }

    @Override
    public StreamBridge newPassthroughStreamBridge(CanonicalRequest request, TokenEstimator tokenEstimator) {
        return new OpenAiChatPassthroughBridge(request, tokenEstimator);
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

    // ---- Request encoding helpers ----

    private void encodeTurn(
            ArrayNode messagesArr,
            CanonicalTurn turn,
            TranslationPolicy policy,
            ProviderCapabilities caps
    ) {
        switch (turn.role()) {
            case SYSTEM, DEVELOPER -> {
                StringBuilder sb = new StringBuilder();
                for (CanonicalContentPart p : turn.parts()) {
                    if (p instanceof CanonicalContentPart.TextPart t && !t.text().isEmpty()) {
                        if (!sb.isEmpty()) {
                            sb.append('\n');
                        }
                        sb.append(t.text());
                    }
                }
                ObjectNode msg = mapper.createObjectNode();
                boolean useDeveloper = turn.role() == CanonicalRole.DEVELOPER && caps.supportsDeveloperRole();
                msg.put("role", useDeveloper ? "developer" : "system");
                msg.put("content", sb.toString());
                messagesArr.add(msg);
            }
            case USER, TOOL -> {
                List<CanonicalContentPart.ToolResultPart> toolResults = new ArrayList<>();
                List<CanonicalContentPart> remainingUserParts = new ArrayList<>();
                for (CanonicalContentPart p : turn.parts()) {
                    if (p instanceof CanonicalContentPart.ToolResultPart tr) {
                        toolResults.add(tr);
                    } else {
                        remainingUserParts.add(p);
                    }
                }
                List<CanonicalContentPart> hoistedImages = new ArrayList<>();
                for (CanonicalContentPart.ToolResultPart tr : toolResults) {
                    StringBuilder textOut = new StringBuilder();
                    for (CanonicalContentPart child : tr.content()) {
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
                            hoistedImages.add(new CanonicalContentPart.TextPart(
                                    "[Image returned by tool " + (tr.toolName() != null ? tr.toolName() : "unknown")
                                            + " (" + tr.callId() + ")]"));
                            hoistedImages.add(img);
                        }
                    }
                    ObjectNode toolMsg = mapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", tr.callId() != null ? tr.callId() : "");
                    toolMsg.put("content", tr.isError() && !textOut.isEmpty()
                            ? "Error: " + textOut : textOut.toString());
                    messagesArr.add(toolMsg);
                }
                if (!hoistedImages.isEmpty()) {
                    remainingUserParts.addAll(0, hoistedImages);
                }
                if (!remainingUserParts.isEmpty()) {
                    ObjectNode userMsg = mapper.createObjectNode();
                    userMsg.put("role", "user");
                    userMsg.set("content", encodeUserParts(remainingUserParts));
                    messagesArr.add(userMsg);
                }
            }
            case ASSISTANT -> {
                StringBuilder text = new StringBuilder();
                StringBuilder thinking = new StringBuilder();
                String observedThinkingField = null;
                StringBuilder refusal = new StringBuilder();
                ArrayNode toolCalls = mapper.createArrayNode();
                for (CanonicalContentPart p : turn.parts()) {
                    switch (p) {
                        case CanonicalContentPart.TextPart t -> text.append(t.text());
                        case CanonicalContentPart.RefusalPart r -> refusal.append(r.refusal());
                        case CanonicalContentPart.ToolCallPart tc -> toolCalls.add(encodeToolCall(tc));
                        case CanonicalContentPart.ThinkingPart th -> {
                            if (!caps.openAiNativeProvider() && !th.text().isEmpty()) {
                                String targetField = resolveTargetReasoningField(caps, th);
                                if (targetField != null) {
                                    if (observedThinkingField == null) {
                                        observedThinkingField = targetField;
                                    }
                                    if (!thinking.isEmpty()) {
                                        thinking.append('\n');
                                    }
                                    thinking.append(th.text());
                                }
                            }
                        }
                        case CanonicalContentPart.RedactedThinkingPart ignored -> {}
                        case CanonicalContentPart.ImagePart ignored -> {}
                        case CanonicalContentPart.ToolResultPart ignored -> {}
                    }
                }
                ObjectNode msg = mapper.createObjectNode();
                msg.put("role", "assistant");
                if (!text.isEmpty()) {
                    msg.put("content", text.toString());
                } else if (!toolCalls.isEmpty() || !refusal.isEmpty()) {
                    msg.set("content", NullNode.getInstance());
                } else {
                    msg.put("content", "");
                }
                if (observedThinkingField != null && !thinking.isEmpty()) {
                    msg.put(observedThinkingField, thinking.toString());
                }
                if (!refusal.isEmpty()) {
                    msg.put("refusal", refusal.toString());
                }
                if (!toolCalls.isEmpty()) {
                    msg.set("tool_calls", toolCalls);
                }
                messagesArr.add(msg);
            }
        }
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

    private JsonNode encodeUserParts(List<CanonicalContentPart> parts) {
        if (parts.size() == 1 && parts.getFirst() instanceof CanonicalContentPart.TextPart t) {
            return mapper.getNodeFactory().textNode(t.text());
        }
        ArrayNode arr = mapper.createArrayNode();
        for (CanonicalContentPart p : parts) {
            if (p instanceof CanonicalContentPart.TextPart t) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("type", "text");
                obj.put("text", t.text());
                arr.add(obj);
            } else if (p instanceof CanonicalContentPart.ImagePart img) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put("type", "image_url");
                ObjectNode imgUrl = mapper.createObjectNode();
                switch (img.source()) {
                    case ImageSource.Base64Image b64 -> imgUrl.put("url", b64.toDataUri());
                    case ImageSource.UrlImage u -> imgUrl.put("url", u.url());
                    case ImageSource.FileRefImage ref -> throw new HyperLlmException(
                            ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                            "OpenAI Chat does not accept file references: " + ref.fileRef());
                }
                if (img.detail() != null && img.detail() != ImageDetail.AUTO) {
                    imgUrl.put("detail", img.detail() == ImageDetail.ORIGINAL ? "high" : img.detail().toOpenAiValue());
                }
                obj.set("image_url", imgUrl);
                arr.add(obj);
            }
        }
        return arr;
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

    private void encodeGenerationConfig(
            ObjectNode root,
            CanonicalRequest req,
            ProviderCapabilities caps,
            TranslationPolicy policy
    ) {
        CanonicalGenerationConfig g = req.generation();
        if (g.maxOutputTokens() != null && g.maxOutputTokens() > 0) {
            root.put(caps.useMaxCompletionTokens() ? "max_completion_tokens" : "max_tokens", g.maxOutputTokens());
        }
        boolean allowSampling = !(caps.forbidsSamplingWithReasoning() && policy != TranslationPolicy.STRICT);
        if (allowSampling) {
            if (g.temperature() != null) {
                root.put("temperature", g.temperature());
            }
            if (g.topP() != null) {
                root.put("top_p", g.topP());
            }
        }
        if (!g.stopSequences().isEmpty()) {
            ArrayNode stops = mapper.createArrayNode();
            int limit = Math.min(g.stopSequences().size(), 4);
            for (int i = 0; i < limit; i++) {
                stops.add(g.stopSequences().get(i));
            }
            root.set("stop", stops);
        }
        if (g.seed() != null) {
            root.put("seed", g.seed());
        }
        if (g.presencePenalty() != null) {
            root.put("presence_penalty", g.presencePenalty());
        }
        if (g.frequencyPenalty() != null) {
            root.put("frequency_penalty", g.frequencyPenalty());
        }
        if (g.candidateCount() != null && g.candidateCount() > 1) {
            root.put("n", g.candidateCount());
        }
    }

    private void encodeTools(ObjectNode root, CanonicalRequest req, ProviderCapabilities caps) {
        if (req.tools().isEmpty()) {
            return;
        }
        ArrayNode arr = mapper.createArrayNode();
        for (CanonicalToolDefinition tool : req.tools()) {
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", tool.name());
            if (tool.description() != null) {
                fn.put("description", tool.description());
            }
            if (tool.parametersSchema() != null && tool.parametersSchema().isObject()) {
                fn.set("parameters", tool.parametersSchema().deepCopy());
            } else {
                ObjectNode emptySchema = mapper.createObjectNode();
                emptySchema.put("type", "object");
                emptySchema.set("properties", mapper.createObjectNode());
                fn.set("parameters", emptySchema);
            }
            if (tool.strict() != null && caps.supportsStrictToolSchema()) {
                fn.put("strict", tool.strict());
            }
            wrapper.set("function", fn);
            arr.add(wrapper);
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
                ObjectNode fn = mapper.createObjectNode();
                fn.put("name", tc.specificToolName());
                obj.set("function", fn);
                root.set("tool_choice", obj);
            }
            case OMITTED -> {}
        }
        if (tc.parallelToolCalls() != null) {
            root.put("parallel_tool_calls", tc.parallelToolCalls());
        }
    }

    private void encodeResponseFormat(ObjectNode root, CanonicalResponseFormat format) {
        if (format == null) {
            return;
        }
        ObjectNode rf = mapper.createObjectNode();
        switch (format) {
            case CanonicalResponseFormat.TextFormat ignored -> rf.put("type", "text");
            case CanonicalResponseFormat.JsonObjectFormat ignored -> rf.put("type", "json_object");
            case CanonicalResponseFormat.JsonSchemaFormat js -> {
                rf.put("type", "json_schema");
                ObjectNode inner = mapper.createObjectNode();
                inner.put("name", js.name());
                if (js.description() != null) {
                    inner.put("description", js.description());
                }
                if (js.schema() != null) {
                    inner.set("schema", js.schema().deepCopy());
                }
                if (js.strict() != null) {
                    inner.put("strict", js.strict());
                }
                rf.set("json_schema", inner);
            }
        }
        root.set("response_format", rf);
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
            emitFinalClose(out);
            return out;
        }

        private void processEvent(SseEventSplitter.SseEvent event, List<CanonicalStreamEvent> out) {
            String data = event.dataPayload();
            if (data.isEmpty()) {
                return;
            }
            if ("[DONE]".equals(data)) {
                emitFinalClose(out);
                return;
            }
            try {
                JsonNode root = mapper.readTree(data);
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

        private final CanonicalRequest inboundRequest;
        private String responseId = CodecSupport.generateId("chatcmpl-");
        private String model;
        private long created = CodecSupport.nowEpochSeconds();
        private boolean roleSent;
        private boolean doneSent;
        private int nextToolIdx;
        private final Map<Integer, Integer> blockToToolIndex = new HashMap<>();
        private boolean sawToolCall;
        private TokenUsage latestUsage;

        private OpenAiChatStreamEncoder(CanonicalRequest inboundRequest) {
            this.inboundRequest = inboundRequest;
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
                    if (start.virtualModel() != null && !start.virtualModel().isBlank()) {
                        this.model = start.virtualModel();
                    }
                    if (start.createdEpochSeconds() > 0) {
                        this.created = start.createdEpochSeconds();
                    }
                    ensureRoleChunk(frames);
                }
                case CanonicalStreamEvent.TextDelta td -> {
                    ensureRoleChunk(frames);
                    ObjectNode delta = mapper.createObjectNode();
                    delta.put("content", td.textDelta());
                    frames.add(buildChunkFrame(delta, null));
                }
                case CanonicalStreamEvent.ThinkingDelta th -> {
                    ensureRoleChunk(frames);
                    ObjectNode delta = mapper.createObjectNode();
                    delta.put("reasoning_content", th.thinkingDelta());
                    frames.add(buildChunkFrame(delta, null));
                }
                case CanonicalStreamEvent.RefusalDelta ref -> {
                    ensureRoleChunk(frames);
                    ObjectNode delta = mapper.createObjectNode();
                    delta.put("refusal", ref.refusalDelta());
                    frames.add(buildChunkFrame(delta, null));
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
                        if (inboundRequest.clientRequestedUsage() && latestUsage != null) {
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
            if (inboundRequest.clientRequestedUsage() && latestUsage != null) {
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

    // ---- Passthrough Streaming Bridge ----

    private final class OpenAiChatPassthroughBridge implements StreamBridge {

        private final CanonicalRequest request;
        private final TokenEstimator tokenEstimator;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private final StringBuilder streamedText = new StringBuilder();
        private TokenUsage observedUsage;

        private OpenAiChatPassthroughBridge(CanonicalRequest request, TokenEstimator tokenEstimator) {
            this.request = request;
            this.tokenEstimator = tokenEstimator;
        }

        @Override
        public List<byte[]> feed(byte[] upstreamChunk) {
            return processEvents(splitter.push(upstreamChunk));
        }

        @Override
        public List<byte[]> complete() {
            return processEvents(splitter.flushRemainder());
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

        private List<byte[]> processEvents(List<SseEventSplitter.SseEvent> events) {
            if (events.isEmpty()) {
                return List.of();
            }
            List<byte[]> out = new ArrayList<>(events.size());
            for (SseEventSplitter.SseEvent event : events) {
                byte[] inspected = inspectAndFilter(event);
                if (inspected != null) {
                    out.add(inspected);
                }
            }
            return out;
        }

        private byte[] inspectAndFilter(SseEventSplitter.SseEvent event) {
            String data = event.dataPayload();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return event.rawBytes();
            }
            try {
                JsonNode node = mapper.readTree(data);
                if (!node.isObject()) {
                    return event.rawBytes();
                }
                accumulateChoices(node.get("choices"));
                Optional<TokenUsage> parsed = parseUsageNode(node.get("usage"));
                if (parsed.isPresent()) {
                    observedUsage = parsed.get();
                    if (!request.clientRequestedUsage()) {
                        if (isUsageOnlyFrame(node)) {
                            return null;
                        }
                        if (node instanceof ObjectNode obj) {
                            obj.remove("usage");
                            return SseEventSplitter.buildDataFrame(CodecSupport.writeJsonString(mapper, obj));
                        }
                    }
                }
                return event.rawBytes();
            } catch (Exception ignored) {
                return event.rawBytes();
            }
        }

        private void accumulateChoices(JsonNode choices) {
            if (choices == null || !choices.isArray()) {
                return;
            }
            for (JsonNode choice : choices) {
                JsonNode delta = choice.get("delta");
                if (delta == null || !delta.isObject()) {
                    continue;
                }
                if (delta.hasNonNull("content") && delta.get("content").isTextual()) {
                    CodecSupport.appendBounded(streamedText, delta.get("content").asText());
                }
                String reasoning = extractReasoningText(delta);
                if (reasoning != null && !reasoning.isEmpty()) {
                    CodecSupport.appendBounded(streamedText, reasoning);
                }
                JsonNode toolCalls = delta.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        JsonNode args = tc.path("function").get("arguments");
                        if (args != null && args.isTextual()) {
                            CodecSupport.appendBounded(streamedText, args.asText());
                        }
                    }
                }
            }
        }

        private boolean isUsageOnlyFrame(JsonNode root) {
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isNull() || (choices.isArray() && choices.isEmpty())) {
                return true;
            }
            if (!choices.isArray()) {
                return false;
            }
            for (JsonNode choice : choices) {
                if (choice.hasNonNull("finish_reason")) {
                    return false;
                }
                JsonNode delta = choice.get("delta");
                if (delta != null && delta.isObject() && !delta.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }
}
