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
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.StreamBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
        ObjectNode root = CodecSupport.parseObjectRoot(mapper, rawBody);
        String model = CodecSupport.requireModel(root);
        Integer parsedMaxTokens = null;
        JsonNode maxTokensNode = root.get("max_tokens");
        if (maxTokensNode != null && !maxTokensNode.isNull()) {
            if (!maxTokensNode.isIntegralNumber() || maxTokensNode.asInt() <= 0) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'max_tokens' must be a positive integer");
            }
            parsedMaxTokens = maxTokensNode.asInt();
        }
        JsonNode messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray() || messagesNode.isEmpty()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'messages' must be a non-empty array");
        }

        boolean stream = root.path("stream").asBoolean(false);
        List<UnsupportedFeatureFlag> unsupported = new ArrayList<>();

        List<CanonicalSystemPart> systemParts = new ArrayList<>(decodeSystem(root.get("system"), false));
        List<CanonicalTurn> turns = new ArrayList<>(messagesNode.size());
        boolean sawNonSystem = false;
        for (JsonNode msg : messagesNode) {
            if (!msg.isObject()) {
                continue;
            }
            String roleStr = msg.path("role").asText("user").toLowerCase(Locale.ROOT);
            switch (roleStr) {
                case "system", "developer" -> {
                    boolean devPriority = "developer".equals(roleStr);
                    if (!sawNonSystem) {
                        systemParts.addAll(decodeSystem(msg.get("content"), devPriority));
                    } else {
                        List<CanonicalContentPart> parts = decodeContentBlocks(msg.get("content"), unsupported);
                        turns.add(new CanonicalTurn(devPriority ? CanonicalRole.DEVELOPER : CanonicalRole.SYSTEM, parts));
                    }
                }
                case "user" -> {
                    sawNonSystem = true;
                    List<CanonicalContentPart> parts = decodeContentBlocks(msg.get("content"), unsupported);
                    turns.add(new CanonicalTurn(CanonicalRole.USER, parts));
                }
                case "assistant" -> {
                    sawNonSystem = true;
                    List<CanonicalContentPart> parts = decodeContentBlocks(msg.get("content"), unsupported);
                    turns.add(new CanonicalTurn(CanonicalRole.ASSISTANT, parts));
                }
                default -> throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                        "Unsupported Anthropic role: " + roleStr);
            }
        }

        List<CanonicalToolDefinition> tools = decodeTools(root.get("tools"), unsupported);
        CanonicalToolChoice toolChoice = decodeToolChoice(root.get("tool_choice"));
        CanonicalResponseFormat responseFormat = decodeOutputFormat(root.path("output_config").get("format"));
        CanonicalThinkingConfig thinking = decodeThinking(root.get("thinking"), root.path("output_config"));

        List<String> stops = new ArrayList<>();
        JsonNode stopSeqs = root.get("stop_sequences");
        if (stopSeqs != null && stopSeqs.isArray()) {
            for (JsonNode s : stopSeqs) {
                if (s.isTextual()) {
                    stops.add(s.asText());
                }
            }
        }
        CanonicalGenerationConfig generation = new CanonicalGenerationConfig(
                parsedMaxTokens,
                CodecSupport.asNullableDouble(root, "temperature"),
                CodecSupport.asNullableDouble(root, "top_p"),
                CodecSupport.asNullableInt(root, "top_k"),
                stops,
                null,
                null,
                null,
                null
        );

        CanonicalCacheConfig cache = new CanonicalCacheConfig(
                CodecSupport.parseCacheControl(root.get("cache_control")),
                null,
                null,
                null
        );
        String userId = root.path("metadata").isObject() ? CodecSupport.asNullableText(root.path("metadata"), "user_id") : null;
        CanonicalProtocolOptions options = new CanonicalProtocolOptions(
                true,
                true,
                context != null ? context.anthropicVersion() : null,
                context != null ? context.anthropicBeta() : null,
                userId,
                CodecSupport.asNullableText(root, "service_tier"),
                null
        );

        return new CanonicalRequest(
                ProtocolKind.ANTHROPIC_MESSAGES, model, stream, systemParts, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, unsupported
        );
    }

    @Override
    public byte[] preparePassthroughRequest(byte[] rawBody, CanonicalRequest decoded, UpstreamEncodingContext context) {
        ObjectNode root = CodecSupport.parseObjectRoot(mapper, rawBody);
        CodecSupport.rewriteModelIfPresent(root, context.upstreamModelName());
        if (!root.hasNonNull("max_tokens")) {
            root.put("max_tokens", context.resolveDefaultMaxOutputTokens(DEFAULT_MAX_TOKENS));
        }
        normalizePassthroughMessages(root);
        return CodecSupport.writeJsonBytes(mapper, root);
    }

    @Override
    public byte[] encodeRequest(CanonicalRequest req, UpstreamEncodingContext context) {
        ObjectNode root = mapper.createObjectNode();
        String upstreamModel = context.upstreamModelName() != null && !context.upstreamModelName().isBlank()
                ? context.upstreamModelName() : req.model();
        root.put("model", upstreamModel);
        root.put("stream", req.stream());

        int maxTokens = req.generation().maxOutputTokens() != null && req.generation().maxOutputTokens() > 0
                ? req.generation().maxOutputTokens()
                : context.resolveDefaultMaxOutputTokens(DEFAULT_MAX_TOKENS);

        boolean thinkingEnabled = req.thinking().isEnabled();
        ObjectNode outputConfig = mapper.createObjectNode();

        if (req.thinking().mode() == CanonicalThinkingConfig.ThinkingMode.DISABLED
                || req.thinking().effort() == CanonicalThinkingConfig.ThinkingEffort.NONE) {
            ObjectNode thinkingNode = mapper.createObjectNode();
            thinkingNode.put("type", "disabled");
            root.set("thinking", thinkingNode);
            thinkingEnabled = false;
        } else if (thinkingEnabled) {
            boolean adaptive = req.thinking().mode() == CanonicalThinkingConfig.ThinkingMode.ADAPTIVE
                    || CodecSupport.isAnthropicAdaptiveStyle(context.thinkingStyle(), upstreamModel);
            ObjectNode thinkingNode = mapper.createObjectNode();
            if (adaptive) {
                thinkingNode.put("type", "adaptive");
                String effort = CodecSupport.toAnthropicAdaptiveEffort(req.thinking());
                if (effort != null) {
                    outputConfig.put("effort", effort);
                }
            } else {
                int budget = Math.max(1024, CodecSupport.toAnthropicBudgetTokens(req.thinking()));
                if (maxTokens <= budget) {
                    maxTokens = budget + 4096;
                }
                thinkingNode.put("type", "enabled");
                thinkingNode.put("budget_tokens", budget);
            }
            root.set("thinking", thinkingNode);
        }
        root.put("max_tokens", maxTokens);

        // System instructions (+ optional BEST_EFFORT json_object hint)
        ArrayNode systemArr = mapper.createArrayNode();
        for (CanonicalSystemPart sp : req.systemInstructions()) {
            if (!sp.text().isEmpty()) {
                ObjectNode block = mapper.createObjectNode();
                block.put("type", "text");
                block.put("text", sp.text());
                if (sp.cacheControl() != null) {
                    block.set("cache_control", CodecSupport.encodeCacheControl(mapper, sp.cacheControl()));
                }
                systemArr.add(block);
            }
        }
        if (req.responseFormat() instanceof CanonicalResponseFormat.JsonObjectFormat) {
            if (context.policy() == TranslationPolicy.STRICT) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "Schemaless json_object format is not supported natively by Anthropic in STRICT mode");
            }
            ObjectNode hint = mapper.createObjectNode();
            hint.put("type", "text");
            hint.put("text", "Respond with a valid JSON object only. Do not wrap in markdown code fences.");
            systemArr.add(hint);
        }
        if (!systemArr.isEmpty()) {
            root.set("system", systemArr);
        }

        // Messages with strict alternation and first-user guarantee
        ArrayNode messagesArr = encodeAlternatingMessages(req.turns(), context.policy());
        root.set("messages", messagesArr);

        // Sampling parameters (respecting Anthropic's thinking constraints)
        CanonicalGenerationConfig g = req.generation();
        if (!thinkingEnabled) {
            if (g.temperature() != null) {
                double temp = Math.max(0.0, Math.min(1.0, g.temperature()));
                root.put("temperature", temp);
            }
            if (g.topP() != null) {
                root.put("top_p", g.topP());
            }
            if (g.topK() != null) {
                root.put("top_k", g.topK());
            }
        }
        if (!g.stopSequences().isEmpty()) {
            ArrayNode stops = mapper.createArrayNode();
            for (String s : g.stopSequences()) {
                stops.add(s);
            }
            root.set("stop_sequences", stops);
        }

        encodeTools(root, req, thinkingEnabled, context.policy());

        if (req.responseFormat() instanceof CanonicalResponseFormat.JsonSchemaFormat js && js.schema() != null) {
            ObjectNode fmt = mapper.createObjectNode();
            fmt.put("type", "json_schema");
            fmt.set("schema", js.schema().deepCopy());
            outputConfig.set("format", fmt);
        }
        if (!outputConfig.isEmpty()) {
            root.set("output_config", outputConfig);
        }

        if (req.cache().topLevelCacheControl() != null) {
            root.set("cache_control", CodecSupport.encodeCacheControl(mapper, req.cache().topLevelCacheControl()));
        }
        if (req.options().user() != null && !req.options().user().isBlank()) {
            ObjectNode meta = mapper.createObjectNode();
            meta.put("user_id", req.options().user());
            root.set("metadata", meta);
        }
        if (req.options().serviceTier() != null && !req.options().serviceTier().isBlank()) {
            root.put("service_tier", req.options().serviceTier());
        }

        return CodecSupport.writeJsonBytes(mapper, root);
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

    @Override
    public StreamBridge newPassthroughStreamBridge(StreamRequestMetadata inboundMeta, TokenEstimator tokenEstimator) {
        return new AnthropicPassthroughBridge(inboundMeta, tokenEstimator);
    }

    // ---- Decoding helpers ----

    /**
     * For Anthropic passthrough: hoists leading {@code system}/{@code developer} messages out of
     * {@code messages[]} into top-level {@code system[]} (since Anthropic requires {@code messages[0]}
     * to be {@code user}), while preserving mid-conversation {@code role: "system"} (native Anthropic
     * cache-preserving developer override) and rewriting mid-conversation {@code role: "developer"} to {@code "system"}.
     */
    private void normalizePassthroughMessages(ObjectNode root) {
        JsonNode messagesNode = root.get("messages");
        if (!(messagesNode instanceof ArrayNode messagesArr) || messagesArr.isEmpty()) {
            return;
        }
        boolean needsRewrite = false;
        boolean sawNonSystem = false;
        for (JsonNode msg : messagesArr) {
            if (!msg.isObject()) {
                continue;
            }
            String role = msg.path("role").asText("").toLowerCase(Locale.ROOT);
            if (!sawNonSystem && ("system".equals(role) || "developer".equals(role))) {
                needsRewrite = true;
                break;
            }
            if ("developer".equals(role)) {
                needsRewrite = true;
                break;
            }
            if (!"system".equals(role)) {
                sawNonSystem = true;
            }
        }
        if (!needsRewrite) {
            return;
        }

        ArrayNode systemArr = mapper.createArrayNode();
        JsonNode existingSystem = root.get("system");
        if (existingSystem != null && existingSystem.isTextual() && !existingSystem.asText().isEmpty()) {
            ObjectNode b = mapper.createObjectNode();
            b.put("type", "text");
            b.put("text", existingSystem.asText());
            systemArr.add(b);
        } else if (existingSystem instanceof ArrayNode existingArr) {
            for (JsonNode item : existingArr) {
                systemArr.add(item.deepCopy());
            }
        }

        ArrayNode cleanedMessages = mapper.createArrayNode();
        sawNonSystem = false;
        for (JsonNode msg : messagesArr) {
            if (!msg.isObject()) {
                continue;
            }
            String role = msg.path("role").asText("").toLowerCase(Locale.ROOT);
            if (!sawNonSystem && ("system".equals(role) || "developer".equals(role))) {
                for (CanonicalSystemPart sp : decodeSystem(msg.get("content"), false)) {
                    if (!sp.text().isEmpty()) {
                        ObjectNode b = mapper.createObjectNode();
                        b.put("type", "text");
                        b.put("text", sp.text());
                        if (sp.cacheControl() != null) {
                            b.set("cache_control", CodecSupport.encodeCacheControl(mapper, sp.cacheControl()));
                        }
                        systemArr.add(b);
                    }
                }
            } else {
                sawNonSystem = true;
                ObjectNode copy = (ObjectNode) msg.deepCopy();
                if ("developer".equals(role)) {
                    copy.put("role", "system");
                }
                cleanedMessages.add(copy);
            }
        }

        if (!systemArr.isEmpty()) {
            root.set("system", systemArr);
        }
        root.set("messages", cleanedMessages);
    }

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

    // ---- Encoding helpers ----

    private ArrayNode encodeAlternatingMessages(List<CanonicalTurn> turns, TranslationPolicy policy) {
        record PendingMsg(String role, List<ObjectNode> blocks) {
        }
        List<PendingMsg> merged = new ArrayList<>();

        for (CanonicalTurn turn : turns) {
            String targetRole = (turn.role() == CanonicalRole.ASSISTANT) ? "assistant" : "user";
            List<ObjectNode> encodedBlocks = encodeTurnBlocks(turn, targetRole);
            if (encodedBlocks.isEmpty()) {
                continue;
            }
            if (!merged.isEmpty() && merged.getLast().role().equals(targetRole)) {
                merged.getLast().blocks().addAll(encodedBlocks);
            } else {
                merged.add(new PendingMsg(targetRole, new ArrayList<>(encodedBlocks)));
            }
        }

        // Anthropic requires tool_result blocks to appear before text/image blocks in a user message
        for (PendingMsg pm : merged) {
            if ("user".equals(pm.role())) {
                pm.blocks().sort((a, b) -> {
                    boolean aRes = "tool_result".equals(a.path("type").asText());
                    boolean bRes = "tool_result".equals(b.path("type").asText());
                    return Boolean.compare(bRes, aRes);
                });
            }
        }

        if (merged.isEmpty()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Translated Anthropic messages cannot be empty");
        }
        if (!"user".equals(merged.getFirst().role())) {
            if (policy == TranslationPolicy.STRICT) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "Anthropic Messages requires the first message to have role 'user'");
            }
            ObjectNode placeholder = mapper.createObjectNode();
            placeholder.put("type", "text");
            placeholder.put("text", "Continue.");
            merged.add(0, new PendingMsg("user", List.of(placeholder)));
        }

        ArrayNode arr = mapper.createArrayNode();
        for (PendingMsg pm : merged) {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", pm.role());
            ArrayNode content = mapper.createArrayNode();
            for (ObjectNode b : pm.blocks()) {
                content.add(b);
            }
            msg.set("content", content);
            arr.add(msg);
        }
        return arr;
    }

    private List<ObjectNode> encodeTurnBlocks(CanonicalTurn turn, String targetRole) {
        List<ObjectNode> blocks = new ArrayList<>();
        if ("assistant".equals(targetRole)) {
            for (CanonicalContentPart p : turn.parts()) {
                switch (p) {
                    case CanonicalContentPart.ThinkingPart th -> {
                        if (CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                                && th.signature() != null && !th.signature().isBlank()) {
                            ObjectNode b = mapper.createObjectNode();
                            b.put("type", "thinking");
                            b.put("thinking", th.text());
                            b.put("signature", th.signature());
                            blocks.add(b);
                        }
                    }
                    case CanonicalContentPart.RedactedThinkingPart rt -> {
                        if (CodecSupport.isSameOrigin(rt.originProtocol(), ProtocolKind.ANTHROPIC_MESSAGES)
                                && rt.opaqueData() != null) {
                            ObjectNode b = mapper.createObjectNode();
                            b.put("type", "redacted_thinking");
                            b.put("data", rt.opaqueData());
                            blocks.add(b);
                        }
                    }
                    case CanonicalContentPart.TextPart t -> {
                        if (!t.text().isEmpty()) {
                            blocks.add(encodeTextBlock(t.text(), t.cacheControl()));
                        }
                    }
                    case CanonicalContentPart.RefusalPart r -> {
                        if (!r.refusal().isEmpty()) {
                            blocks.add(encodeTextBlock(r.refusal(), null));
                        }
                    }
                    case CanonicalContentPart.ToolCallPart tc -> {
                        ObjectNode b = mapper.createObjectNode();
                        b.put("type", "tool_use");
                        b.put("id", tc.callId() != null ? tc.callId() : CodecSupport.generateId("toolu_"));
                        b.put("name", tc.name());
                        b.set("input", CodecSupport.argumentsAsObject(mapper, tc));
                        blocks.add(b);
                    }
                    case CanonicalContentPart.ImagePart ignored -> {}
                    case CanonicalContentPart.ToolResultPart ignored -> {}
                }
            }
        } else {
            for (CanonicalContentPart p : turn.parts()) {
                switch (p) {
                    case CanonicalContentPart.TextPart t -> {
                        if (!t.text().isEmpty()) {
                            blocks.add(encodeTextBlock(t.text(), t.cacheControl()));
                        }
                    }
                    case CanonicalContentPart.ImagePart img -> blocks.add(encodeImageBlock(img));
                    case CanonicalContentPart.ToolResultPart tr -> blocks.add(encodeToolResultBlock(tr));
                    default -> {}
                }
            }
        }
        return blocks;
    }

    private ObjectNode encodeTextBlock(String text, CacheControl cc) {
        ObjectNode b = mapper.createObjectNode();
        b.put("type", "text");
        b.put("text", text);
        if (cc != null) {
            b.set("cache_control", CodecSupport.encodeCacheControl(mapper, cc));
        }
        return b;
    }

    private ObjectNode encodeImageBlock(CanonicalContentPart.ImagePart img) {
        ObjectNode b = mapper.createObjectNode();
        b.put("type", "image");
        ObjectNode src = mapper.createObjectNode();
        switch (img.source()) {
            case ImageSource.Base64Image b64 -> {
                src.put("type", "base64");
                src.put("media_type", b64.mediaType());
                src.put("data", b64.base64Data());
            }
            case ImageSource.UrlImage u -> {
                src.put("type", "url");
                src.put("url", u.url());
            }
            case ImageSource.FileRefImage ref -> {
                src.put("type", "file");
                src.put("file_id", ref.fileRef());
            }
        }
        b.set("source", src);
        if (img.cacheControl() != null) {
            b.set("cache_control", CodecSupport.encodeCacheControl(mapper, img.cacheControl()));
        }
        return b;
    }

    private ObjectNode encodeToolResultBlock(CanonicalContentPart.ToolResultPart tr) {
        ObjectNode b = mapper.createObjectNode();
        b.put("type", "tool_result");
        b.put("tool_use_id", tr.callId() != null ? tr.callId() : "");
        if (tr.isError()) {
            b.put("is_error", true);
        }
        if (tr.content().isEmpty()) {
            b.put("content", "");
        } else if (tr.content().size() == 1 && tr.content().getFirst() instanceof CanonicalContentPart.TextPart t) {
            b.put("content", t.text());
        } else {
            ArrayNode arr = mapper.createArrayNode();
            for (CanonicalContentPart child : tr.content()) {
                if (child instanceof CanonicalContentPart.TextPart t) {
                    if (!t.text().isEmpty()) {
                        arr.add(encodeTextBlock(t.text(), null));
                    }
                } else if (child instanceof CanonicalContentPart.ImagePart img) {
                    arr.add(encodeImageBlock(img));
                }
            }
            if (arr.isEmpty()) {
                b.put("content", "");
            } else {
                b.set("content", arr);
            }
        }
        if (tr.cacheControl() != null) {
            b.set("cache_control", CodecSupport.encodeCacheControl(mapper, tr.cacheControl()));
        }
        return b;
    }

    private void encodeTools(ObjectNode root, CanonicalRequest req, boolean thinkingEnabled, TranslationPolicy policy) {
        if (req.tools().isEmpty()) {
            return;
        }
        ArrayNode arr = mapper.createArrayNode();
        for (CanonicalToolDefinition tool : req.tools()) {
            ObjectNode t = mapper.createObjectNode();
            t.put("name", tool.name());
            if (tool.description() != null) {
                t.put("description", tool.description());
            }
            if (tool.parametersSchema() != null && tool.parametersSchema().isObject()) {
                t.set("input_schema", tool.parametersSchema().deepCopy());
            } else {
                ObjectNode emptySchema = mapper.createObjectNode();
                emptySchema.put("type", "object");
                emptySchema.set("properties", mapper.createObjectNode());
                t.set("input_schema", emptySchema);
            }
            if (tool.strict() != null) {
                t.put("strict", tool.strict());
            }
            if (tool.cacheControl() != null) {
                t.set("cache_control", CodecSupport.encodeCacheControl(mapper, tool.cacheControl()));
            }
            arr.add(t);
        }
        root.set("tools", arr);

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
        ObjectNode tcObj = mapper.createObjectNode();
        switch (mode) {
            case NONE -> tcObj.put("type", "none");
            case AUTO -> tcObj.put("type", "auto");
            case REQUIRED -> tcObj.put("type", "any");
            case SPECIFIC -> {
                tcObj.put("type", "tool");
                tcObj.put("name", tc.specificToolName());
            }
            case OMITTED -> tcObj.put("type", "auto");
        }
        if (Boolean.FALSE.equals(tc.parallelToolCalls()) && mode != CanonicalToolChoice.ToolChoiceMode.NONE) {
            tcObj.put("disable_parallel_tool_use", true);
        }
        root.set("tool_choice", tcObj);
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
            for (SseEventSplitter.SseEvent ev : splitter.push(upstreamChunk)) {
                processEvent(ev, out);
            }
            return out;
        }

        @Override
        public List<CanonicalStreamEvent> complete() {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            for (SseEventSplitter.SseEvent ev : splitter.flushRemainder()) {
                processEvent(ev, out);
            }
            if (!finished) {
                finished = true;
                out.add(new CanonicalStreamEvent.MessageFinish(
                        sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN, null));
            }
            return out;
        }

        private void processEvent(SseEventSplitter.SseEvent ev, List<CanonicalStreamEvent> out) {
            String data = ev.dataPayload();
            if (data.isEmpty()) {
                return;
            }
            try {
                JsonNode root = mapper.readTree(data);
                if (!root.isObject()) {
                    return;
                }
                String type = root.path("type").asText(ev.eventName());
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
            ObjectNode delta = mapper.createObjectNode();
            switch (kind) {
                case THINKING -> {
                    delta.put("type", "thinking_delta");
                    delta.put("thinking", fragment);
                }
                case TOOL_CALL -> {
                    delta.put("type", "input_json_delta");
                    delta.put("partial_json", fragment);
                }
                default -> {
                    delta.put("type", "text_delta");
                    delta.put("text", fragment);
                }
            }
            emitBlockDelta(idx, delta, frames);
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

    // ---- Passthrough Streaming Bridge ----

    private final class AnthropicPassthroughBridge implements StreamBridge {

        private final StreamRequestMetadata inboundMeta;
        private final TokenEstimator tokenEstimator;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private final StringBuilder streamedText = new StringBuilder();
        private TokenUsage observed = TokenUsage.empty();
        private boolean sawInputUsage;
        private boolean sawOutputUsage;

        private AnthropicPassthroughBridge(StreamRequestMetadata inboundMeta, TokenEstimator tokenEstimator) {
            this.inboundMeta = inboundMeta;
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
            if (cleanCompletion && sawInputUsage && sawOutputUsage) {
                return observed;
            }
            if (sawInputUsage && sawOutputUsage && observed.completionTokens() > 0) {
                return observed;
            }
            int prompt = sawInputUsage ? observed.promptTokens() : inboundMeta.estimatedPromptTokens();
            int completion = (cleanCompletion && sawOutputUsage)
                    ? observed.completionTokens()
                    : tokenEstimator.estimateCompletionTokens(inboundMeta.model(), streamedText);
            return new TokenUsage(
                    prompt,
                    observed.cachedReadTokens(),
                    observed.cachedWriteTokens(),
                    completion,
                    0,
                    !(sawInputUsage && sawOutputUsage && cleanCompletion)
            );
        }

        private List<byte[]> inspectEvents(List<SseEventSplitter.SseEvent> events) {
            if (events.isEmpty()) {
                return List.of();
            }
            List<byte[]> out = new ArrayList<>(events.size());
            for (SseEventSplitter.SseEvent event : events) {
                inspectPayload(event);
                out.add(event.rawBytes());
            }
            return out;
        }

        private void inspectPayload(SseEventSplitter.SseEvent event) {
            String data = event.dataPayload();
            if (data.isEmpty()) {
                return;
            }
            try {
                JsonNode root = mapper.readTree(data);
                if (!root.isObject()) {
                    return;
                }
                String type = root.path("type").asText(event.eventName());
                switch (type) {
                    case "message_start" -> parseUsageObject(root.path("message").get("usage")).ifPresent(u -> {
                        observed = observed.mergeMax(u);
                        sawInputUsage = true;
                    });
                    case "content_block_delta" -> {
                        JsonNode delta = root.get("delta");
                        if (delta != null && delta.isObject()) {
                            if (delta.hasNonNull("text") && delta.get("text").isTextual()) {
                                CodecSupport.appendBounded(streamedText, delta.get("text").asText());
                            }
                            if (delta.hasNonNull("thinking") && delta.get("thinking").isTextual()) {
                                CodecSupport.appendBounded(streamedText, delta.get("thinking").asText());
                            }
                            if (delta.hasNonNull("partial_json") && delta.get("partial_json").isTextual()) {
                                CodecSupport.appendBounded(streamedText, delta.get("partial_json").asText());
                            }
                        }
                    }
                    case "message_delta" -> {
                        JsonNode usage = root.get("usage");
                        parseUsageObject(usage).ifPresent(u -> {
                            observed = observed.mergeMax(u);
                            if (usage.hasNonNull("output_tokens")) {
                                sawOutputUsage = true;
                            }
                        });
                    }
                    default -> {}
                }
            } catch (Exception ignored) {
            }
        }
    }
}
