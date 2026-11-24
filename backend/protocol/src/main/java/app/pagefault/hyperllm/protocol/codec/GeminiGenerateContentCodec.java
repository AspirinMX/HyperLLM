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
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.UnsupportedFeatureFlag;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.StreamBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bidirectional codec for Google Gemini GenerateContent API
 * ({@code POST /v1beta/models/{model}:generateContent} and {@code :streamGenerateContent}).
 */
public final class GeminiGenerateContentCodec implements ProtocolCodec {

    private final ObjectMapper mapper;

    public GeminiGenerateContentCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProtocolKind kind() {
        return ProtocolKind.GEMINI_GENERATE_CONTENT;
    }

    @Override
    public CanonicalRequest decodeRequest(byte[] rawBody, InboundRequestContext context) {
        ObjectNode root = CodecSupport.parseObjectRoot(mapper, rawBody);
        String model = (context != null && context.pathModel() != null && !context.pathModel().isBlank())
                ? context.pathModel().trim()
                : CodecSupport.asNullableText(root, "model");
        if (model == null || model.isBlank()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                    "Model must be specified in the Gemini URL path '/v1beta/models/{model}:generateContent'");
        }
        boolean stream = context != null && Boolean.TRUE.equals(context.pathStream());
        boolean altSse = context == null || context.geminiAltSse();

        JsonNode contentsNode = root.get("contents");
        if (contentsNode == null || !contentsNode.isArray() || contentsNode.isEmpty()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'contents' must be a non-empty array");
        }

        List<UnsupportedFeatureFlag> unsupported = new ArrayList<>();
        List<CanonicalSystemPart> systemParts = new ArrayList<>(decodeSystemInstruction(root.get("systemInstruction")));
        List<CanonicalTurn> turns = new ArrayList<>(contentsNode.size());
        boolean sawNonSystem = false;

        for (JsonNode content : contentsNode) {
            if (!content.isObject()) {
                continue;
            }
            String roleStr = content.path("role").asText("user").toLowerCase(Locale.ROOT);
            if ("system".equals(roleStr) || "developer".equals(roleStr)) {
                boolean devPriority = "developer".equals(roleStr);
                List<CanonicalContentPart> parts = decodeParts(content.get("parts"), unsupported);
                if (!sawNonSystem) {
                    for (CanonicalContentPart p : parts) {
                        if (p instanceof CanonicalContentPart.TextPart t && !t.text().isEmpty()) {
                            systemParts.add(new CanonicalSystemPart(t.text(), devPriority, null));
                        }
                    }
                } else {
                    turns.add(new CanonicalTurn(devPriority ? CanonicalRole.DEVELOPER : CanonicalRole.SYSTEM, parts));
                }
                continue;
            }
            sawNonSystem = true;
            CanonicalRole role = switch (roleStr) {
                case "model", "assistant" -> CanonicalRole.ASSISTANT;
                case "function", "tool" -> CanonicalRole.TOOL;
                default -> CanonicalRole.USER;
            };
            List<CanonicalContentPart> parts = decodeParts(content.get("parts"), unsupported);
            turns.add(new CanonicalTurn(role, parts));
        }

        List<CanonicalToolDefinition> tools = decodeTools(root.get("tools"), unsupported);
        CanonicalToolChoice toolChoice = decodeToolConfig(root.get("toolConfig"));

        JsonNode genCfg = root.path("generationConfig");
        CanonicalGenerationConfig generation = decodeGenerationConfig(genCfg);
        CanonicalResponseFormat responseFormat = decodeResponseFormat(genCfg);
        CanonicalThinkingConfig thinking = decodeThinkingConfig(genCfg.get("thinkingConfig"));

        CanonicalCacheConfig cache = new CanonicalCacheConfig(
                null, null, null, CodecSupport.asNullableText(root, "cachedContent"));
        CanonicalProtocolOptions options = new CanonicalProtocolOptions(
                true, altSse, null, null, null, null, null);

        return new CanonicalRequest(
                ProtocolKind.GEMINI_GENERATE_CONTENT, model, stream, systemParts, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, unsupported
        );
    }

    @Override
    public byte[] preparePassthroughRequest(byte[] rawBody, CanonicalRequest decoded, UpstreamEncodingContext context) {
        // Model lives in the Gemini URL path; body passes through unmodified.
        CodecSupport.parseObjectRoot(mapper, rawBody);
        return rawBody;
    }

    @Override
    public byte[] encodeRequest(CanonicalRequest req, UpstreamEncodingContext context) {
        ObjectNode root = mapper.createObjectNode();

        if (!req.systemInstructions().isEmpty()) {
            ArrayNode sysParts = mapper.createArrayNode();
            for (CanonicalSystemPart sp : req.systemInstructions()) {
                if (!sp.text().isEmpty()) {
                    ObjectNode p = mapper.createObjectNode();
                    p.put("text", sp.text());
                    sysParts.add(p);
                }
            }
            if (!sysParts.isEmpty()) {
                ObjectNode sysInst = mapper.createObjectNode();
                sysInst.set("parts", sysParts);
                root.set("systemInstruction", sysInst);
            }
        }

        boolean injectSkipSig = CodecSupport.isGeminiLevelStyle(context.thinkingStyle(), context.upstreamModelName());
        ArrayNode contents = encodeContents(req.turns(), injectSkipSig);
        root.set("contents", contents);

        encodeToolsAndConfig(root, req);
        ObjectNode genCfg = encodeGenerationConfig(req, context);
        if (!genCfg.isEmpty()) {
            root.set("generationConfig", genCfg);
        }
        if (req.cache().geminiCachedContent() != null && !req.cache().geminiCachedContent().isBlank()) {
            root.put("cachedContent", req.cache().geminiCachedContent());
        }

        return CodecSupport.writeJsonBytes(mapper, root);
    }

    @Override
    public CanonicalResponse decodeResponse(byte[] responseBody, String virtualModel) {
        ObjectNode root = CodecSupport.parseResponseObjectRoot(mapper, responseBody);
        String id = CodecSupport.asNullableText(root, "responseId");
        if (id == null || id.isBlank()) {
            id = CodecSupport.generateId("gem_");
        }
        String model = (virtualModel != null && !virtualModel.isBlank())
                ? virtualModel : root.path("modelVersion").asText("");

        List<CanonicalContentPart> parts = new ArrayList<>();
        List<UnsupportedFeatureFlag> ignored = new ArrayList<>();
        CanonicalStopReason stopReason = CanonicalStopReason.END_TURN;

        JsonNode candidates = root.get("candidates");
        if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
            JsonNode cand0 = candidates.get(0);
            parts.addAll(decodeParts(cand0.path("content").get("parts"), ignored));
            stopReason = mapFinishReason(CodecSupport.asNullableText(cand0, "finishReason"), parts);
        } else if (root.path("promptFeedback").hasNonNull("blockReason")) {
            stopReason = CanonicalStopReason.CONTENT_FILTER;
        }

        TokenUsage usage = parseUsageMetadata(root.get("usageMetadata")).orElse(TokenUsage.empty());
        return new CanonicalResponse(
                id, model, CodecSupport.nowEpochSeconds(), parts, stopReason, null, usage, ProtocolKind.GEMINI_GENERATE_CONTENT);
    }

    @Override
    public byte[] encodeResponse(CanonicalResponse response) {
        ObjectNode root = mapper.createObjectNode();
        if (response.id() != null && !response.id().isBlank()) {
            root.put("responseId", response.id());
        }
        if (response.virtualModel() != null && !response.virtualModel().isBlank()) {
            root.put("modelVersion", response.virtualModel());
        }

        ArrayNode partsArr = mapper.createArrayNode();
        for (CanonicalContentPart part : response.content()) {
            switch (part) {
                case CanonicalContentPart.ThinkingPart th -> {
                    ObjectNode p = mapper.createObjectNode();
                    p.put("thought", true);
                    p.put("text", th.text());
                    if (CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.GEMINI_GENERATE_CONTENT)
                            && th.signature() != null) {
                        p.put("thoughtSignature", th.signature());
                    }
                    partsArr.add(p);
                }
                case CanonicalContentPart.TextPart t -> {
                    ObjectNode p = mapper.createObjectNode();
                    p.put("text", t.text());
                    partsArr.add(p);
                }
                case CanonicalContentPart.RefusalPart r -> {
                    ObjectNode p = mapper.createObjectNode();
                    p.put("text", r.refusal());
                    partsArr.add(p);
                }
                case CanonicalContentPart.ToolCallPart tc -> {
                    ObjectNode p = mapper.createObjectNode();
                    ObjectNode fc = mapper.createObjectNode();
                    if (tc.callId() != null && !tc.callId().isBlank()) {
                        fc.put("id", tc.callId());
                    }
                    fc.put("name", tc.name());
                    fc.set("args", CodecSupport.argumentsAsObject(mapper, tc));
                    p.set("functionCall", fc);
                    if (CodecSupport.isSameOrigin(tc.originProtocol(), ProtocolKind.GEMINI_GENERATE_CONTENT)
                            && tc.thoughtSignature() != null) {
                        p.put("thoughtSignature", tc.thoughtSignature());
                    }
                    partsArr.add(p);
                }
                case CanonicalContentPart.RedactedThinkingPart ignored -> {}
                case CanonicalContentPart.ImagePart ignored -> {}
                case CanonicalContentPart.ToolResultPart ignored -> {}
            }
        }

        ObjectNode content = mapper.createObjectNode();
        content.put("role", "model");
        content.set("parts", partsArr);

        ObjectNode candidate = mapper.createObjectNode();
        candidate.put("index", 0);
        candidate.set("content", content);
        candidate.put("finishReason", encodeFinishReason(response.stopReason()));

        ArrayNode candidates = mapper.createArrayNode();
        candidates.add(candidate);
        root.set("candidates", candidates);
        root.set("usageMetadata", encodeUsageMetadata(response.usage()));

        return CodecSupport.writeJsonBytes(mapper, root);
    }

    @Override
    public Optional<TokenUsage> extractNonStreamingUsage(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            return parseUsageMetadata(root.get("usageMetadata"));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public UpstreamStreamDecoder newStreamDecoder(String virtualModel) {
        return new GeminiStreamDecoder(virtualModel);
    }

    @Override
    public DownstreamStreamEncoder newStreamEncoder(CanonicalRequest inboundRequest) {
        return new GeminiStreamEncoder(inboundRequest);
    }

    @Override
    public StreamBridge newPassthroughStreamBridge(CanonicalRequest request, TokenEstimator tokenEstimator) {
        return new GeminiPassthroughBridge(request, tokenEstimator);
    }

    // ---- Decoding helpers ----

    private List<CanonicalSystemPart> decodeSystemInstruction(JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        JsonNode parts = node.get("parts");
        if (parts == null || !parts.isArray()) {
            return List.of();
        }
        List<CanonicalSystemPart> out = new ArrayList<>();
        for (JsonNode p : parts) {
            if (p.isObject() && p.hasNonNull("text")) {
                out.add(new CanonicalSystemPart(p.get("text").asText(), false, null));
            }
        }
        return out;
    }

    private List<CanonicalContentPart> decodeParts(JsonNode partsNode, List<UnsupportedFeatureFlag> unsupported) {
        if (partsNode == null || !partsNode.isArray()) {
            return List.of();
        }
        List<CanonicalContentPart> out = new ArrayList<>(partsNode.size());
        for (JsonNode part : partsNode) {
            if (!part.isObject()) {
                continue;
            }
            String thoughtSig = CodecSupport.asNullableText(part, "thoughtSignature");
            if (part.path("thought").asBoolean(false)) {
                out.add(new CanonicalContentPart.ThinkingPart(
                        part.path("text").asText(""), thoughtSig, null, ProtocolKind.GEMINI_GENERATE_CONTENT));
            } else if (part.hasNonNull("text")) {
                out.add(new CanonicalContentPart.TextPart(part.get("text").asText()));
            } else if (part.hasNonNull("inlineData")) {
                JsonNode inline = part.get("inlineData");
                String mime = inline.path("mimeType").asText("image/png");
                String data = inline.path("data").asText("");
                if (!mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
                    unsupported.add(new UnsupportedFeatureFlag("inline_media:" + mime,
                            "Non-image inlineData MIME type '" + mime + "' is outside text/image input scope"));
                } else {
                    out.add(new CanonicalContentPart.ImagePart(
                            new ImageSource.Base64Image(mime, data), ImageDetail.AUTO, null));
                }
            } else if (part.hasNonNull("fileData")) {
                JsonNode fd = part.get("fileData");
                String uri = fd.path("fileUri").asText("");
                String mime = CodecSupport.asNullableText(fd, "mimeType");
                if (uri.startsWith("gs://") || uri.contains("generativelanguage.googleapis.com/")) {
                    out.add(new CanonicalContentPart.ImagePart(
                            new ImageSource.FileRefImage(ProtocolKind.GEMINI_GENERATE_CONTENT, uri, mime),
                            ImageDetail.AUTO, null));
                } else if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    out.add(new CanonicalContentPart.ImagePart(
                            new ImageSource.UrlImage(uri, mime != null ? mime : CodecSupport.guessMimeFromUrl(uri)),
                            ImageDetail.AUTO, null));
                } else {
                    unsupported.add(new UnsupportedFeatureFlag("file_uri", "Unsupported Gemini fileUri: " + uri));
                }
            } else if (part.hasNonNull("functionCall")) {
                JsonNode fc = part.get("functionCall");
                String callId = CodecSupport.asNullableText(fc, "id");
                String name = fc.path("name").asText("");
                JsonNode args = fc.get("args");
                ObjectNode argsObj = (args instanceof ObjectNode o) ? o : mapper.createObjectNode();
                out.add(new CanonicalContentPart.ToolCallPart(
                        callId, null, name, argsObj, CodecSupport.writeJsonString(mapper, argsObj),
                        thoughtSig, ProtocolKind.GEMINI_GENERATE_CONTENT
                ));
            } else if (part.hasNonNull("functionResponse")) {
                JsonNode fr = part.get("functionResponse");
                String callId = CodecSupport.asNullableText(fr, "id");
                String name = fr.path("name").asText("");
                List<CanonicalContentPart> resParts = new ArrayList<>();
                JsonNode resp = fr.get("response");
                if (resp != null && !resp.isNull()) {
                    if (resp.isObject() && resp.size() == 1 && resp.hasNonNull("output") && resp.get("output").isTextual()) {
                        resParts.add(new CanonicalContentPart.TextPart(resp.get("output").asText()));
                    } else if (resp.isObject() && resp.size() == 1 && resp.hasNonNull("error") && resp.get("error").isTextual()) {
                        resParts.add(new CanonicalContentPart.TextPart(resp.get("error").asText()));
                    } else {
                        resParts.add(new CanonicalContentPart.TextPart(CodecSupport.writeJsonString(mapper, resp)));
                    }
                }
                JsonNode nestedParts = fr.get("parts");
                if (nestedParts != null && nestedParts.isArray()) {
                    resParts.addAll(decodeParts(nestedParts, unsupported));
                }
                boolean isErr = resp != null && resp.isObject() && resp.hasNonNull("error") && resp.size() == 1;
                out.add(new CanonicalContentPart.ToolResultPart(callId, name, resParts, isErr, null));
            }
        }
        return out;
    }

    private List<CanonicalToolDefinition> decodeTools(JsonNode toolsNode, List<UnsupportedFeatureFlag> unsupported) {
        if (toolsNode == null || !toolsNode.isArray()) {
            return List.of();
        }
        List<CanonicalToolDefinition> out = new ArrayList<>();
        for (JsonNode toolObj : toolsNode) {
            if (!toolObj.isObject()) {
                continue;
            }
            Iterator<String> fieldNames = toolObj.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                if ("functionDeclarations".equals(field)) {
                    JsonNode decls = toolObj.get(field);
                    if (decls != null && decls.isArray()) {
                        for (JsonNode decl : decls) {
                            JsonNode schema = decl.hasNonNull("parametersJsonSchema")
                                    ? decl.get("parametersJsonSchema")
                                    : decl.get("parameters");
                            out.add(new CanonicalToolDefinition(
                                    decl.path("name").asText(""),
                                    CodecSupport.asNullableText(decl, "description"),
                                    schema,
                                    null,
                                    null
                            ));
                        }
                    }
                } else {
                    unsupported.add(UnsupportedFeatureFlag.degradable("builtin_tool:" + field,
                            "Gemini built-in tool '" + field + "' cannot be translated across vendors"));
                }
            }
        }
        return out;
    }

    private CanonicalToolChoice decodeToolConfig(JsonNode toolConfig) {
        if (toolConfig == null || !toolConfig.isObject()) {
            return CanonicalToolChoice.omitted();
        }
        JsonNode fcc = toolConfig.get("functionCallingConfig");
        if (fcc == null || !fcc.isObject()) {
            return CanonicalToolChoice.omitted();
        }
        String mode = fcc.path("mode").asText("AUTO").toUpperCase(Locale.ROOT);
        JsonNode allowed = fcc.get("allowedFunctionNames");
        return switch (mode) {
            case "NONE" -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.NONE, null, null);
            case "ANY" -> {
                if (allowed != null && allowed.isArray() && allowed.size() == 1) {
                    yield new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.SPECIFIC, allowed.get(0).asText(), null);
                }
                yield new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.REQUIRED, null, null);
            }
            default -> new CanonicalToolChoice(CanonicalToolChoice.ToolChoiceMode.AUTO, null, null);
        };
    }

    private CanonicalGenerationConfig decodeGenerationConfig(JsonNode genCfg) {
        if (genCfg == null || !genCfg.isObject()) {
            return CanonicalGenerationConfig.empty();
        }
        List<String> stops = new ArrayList<>();
        JsonNode stopSeqs = genCfg.get("stopSequences");
        if (stopSeqs != null && stopSeqs.isArray()) {
            for (JsonNode s : stopSeqs) {
                if (s.isTextual()) {
                    stops.add(s.asText());
                }
            }
        }
        return new CanonicalGenerationConfig(
                CodecSupport.asNullableInt(genCfg, "maxOutputTokens"),
                CodecSupport.asNullableDouble(genCfg, "temperature"),
                CodecSupport.asNullableDouble(genCfg, "topP"),
                CodecSupport.asNullableInt(genCfg, "topK"),
                stops,
                CodecSupport.asNullableLong(genCfg, "seed"),
                CodecSupport.asNullableDouble(genCfg, "presencePenalty"),
                CodecSupport.asNullableDouble(genCfg, "frequencyPenalty"),
                CodecSupport.asNullableInt(genCfg, "candidateCount")
        );
    }

    private CanonicalResponseFormat decodeResponseFormat(JsonNode genCfg) {
        if (genCfg == null || !genCfg.isObject()) {
            return null;
        }
        JsonNode schema = genCfg.hasNonNull("responseJsonSchema")
                ? genCfg.get("responseJsonSchema")
                : genCfg.get("responseSchema");
        if (schema != null && schema.isObject()) {
            return new CanonicalResponseFormat.JsonSchemaFormat("response", null, schema, Boolean.TRUE);
        }
        String mime = CodecSupport.asNullableText(genCfg, "responseMimeType");
        if ("application/json".equalsIgnoreCase(mime)) {
            return new CanonicalResponseFormat.JsonObjectFormat();
        }
        return null;
    }

    private CanonicalThinkingConfig decodeThinkingConfig(JsonNode tc) {
        if (tc == null || !tc.isObject()) {
            return CanonicalThinkingConfig.omitted();
        }
        String level = CodecSupport.asNullableText(tc, "thinkingLevel");
        Integer budget = CodecSupport.asNullableInt(tc, "thinkingBudget");
        Boolean includeThoughts = CodecSupport.asNullableBoolean(tc, "includeThoughts");
        var effort = CanonicalThinkingConfig.ThinkingEffort.parseOrOmitted(level);

        CanonicalThinkingConfig.ThinkingMode mode;
        if (budget != null && budget == 0) {
            mode = CanonicalThinkingConfig.ThinkingMode.DISABLED;
        } else if (budget != null && budget < 0) {
            mode = CanonicalThinkingConfig.ThinkingMode.ADAPTIVE;
        } else if (budget != null || effort != CanonicalThinkingConfig.ThinkingEffort.OMITTED) {
            mode = CanonicalThinkingConfig.ThinkingMode.ENABLED;
        } else {
            mode = CanonicalThinkingConfig.ThinkingMode.OMITTED;
        }
        return new CanonicalThinkingConfig(mode, effort, budget != null && budget > 0 ? budget : null, includeThoughts, null);
    }

    // ---- Encoding helpers ----

    private ArrayNode encodeContents(List<CanonicalTurn> turns, boolean injectSkipSig) {
        record PendingContent(String role, List<ObjectNode> parts) {
        }
        List<PendingContent> merged = new ArrayList<>();

        for (CanonicalTurn turn : turns) {
            String role = (turn.role() == CanonicalRole.ASSISTANT) ? "model" : "user";
            List<ObjectNode> parts = encodeTurnParts(turn, role, injectSkipSig);
            if (parts.isEmpty()) {
                continue;
            }
            if (!merged.isEmpty() && merged.getLast().role().equals(role)) {
                merged.getLast().parts().addAll(parts);
            } else {
                merged.add(new PendingContent(role, new ArrayList<>(parts)));
            }
        }

        ArrayNode arr = mapper.createArrayNode();
        for (PendingContent pc : merged) {
            ObjectNode c = mapper.createObjectNode();
            c.put("role", pc.role());
            ArrayNode partsArr = mapper.createArrayNode();
            for (ObjectNode p : pc.parts()) {
                partsArr.add(p);
            }
            c.set("parts", partsArr);
            arr.add(c);
        }
        return arr;
    }

    private List<ObjectNode> encodeTurnParts(CanonicalTurn turn, String targetRole, boolean injectSkipSig) {
        List<ObjectNode> parts = new ArrayList<>();
        boolean attachedSigOnFirstCall = false;

        for (CanonicalContentPart part : turn.parts()) {
            switch (part) {
                case CanonicalContentPart.TextPart t -> {
                    if (!t.text().isEmpty()) {
                        ObjectNode p = mapper.createObjectNode();
                        p.put("text", t.text());
                        parts.add(p);
                    }
                }
                case CanonicalContentPart.RefusalPart r -> {
                    if (!r.refusal().isEmpty()) {
                        ObjectNode p = mapper.createObjectNode();
                        p.put("text", r.refusal());
                        parts.add(p);
                    }
                }
                case CanonicalContentPart.ImagePart img -> parts.add(encodeImagePart(img));
                case CanonicalContentPart.ThinkingPart th -> {
                    if ("model".equals(targetRole)
                            && CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.GEMINI_GENERATE_CONTENT)) {
                        ObjectNode p = mapper.createObjectNode();
                        p.put("thought", true);
                        p.put("text", th.text());
                        if (th.signature() != null && !th.signature().isBlank()) {
                            p.put("thoughtSignature", th.signature());
                        }
                        parts.add(p);
                    }
                }
                case CanonicalContentPart.ToolCallPart tc -> {
                    ObjectNode p = mapper.createObjectNode();
                    ObjectNode fc = mapper.createObjectNode();
                    if (tc.callId() != null && !tc.callId().isBlank()) {
                        fc.put("id", tc.callId());
                    }
                    fc.put("name", tc.name());
                    fc.set("args", CodecSupport.argumentsAsObject(mapper, tc));
                    p.set("functionCall", fc);
                    if (CodecSupport.isSameOrigin(tc.originProtocol(), ProtocolKind.GEMINI_GENERATE_CONTENT)
                            && tc.thoughtSignature() != null && !tc.thoughtSignature().isBlank()) {
                        p.put("thoughtSignature", tc.thoughtSignature());
                        attachedSigOnFirstCall = true;
                    } else if (injectSkipSig && !attachedSigOnFirstCall) {
                        p.put("thoughtSignature", CodecSupport.GEMINI_SKIP_THOUGHT_SIGNATURE);
                        attachedSigOnFirstCall = true;
                    }
                    parts.add(p);
                }
                case CanonicalContentPart.ToolResultPart tr -> {
                    if (tr.toolName() == null || tr.toolName().isBlank()) {
                        throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                                "Cannot translate tool result '" + tr.callId()
                                        + "' to Gemini: matching tool call name not found in conversation history");
                    }
                    parts.add(encodeFunctionResponsePart(tr));
                }
                case CanonicalContentPart.RedactedThinkingPart ignored -> {}
            }
        }
        return parts;
    }

    private ObjectNode encodeImagePart(CanonicalContentPart.ImagePart img) {
        ObjectNode p = mapper.createObjectNode();
        switch (img.source()) {
            case ImageSource.Base64Image b64 -> {
                ObjectNode inline = mapper.createObjectNode();
                inline.put("mimeType", b64.mediaType());
                inline.put("data", b64.base64Data());
                p.set("inlineData", inline);
            }
            case ImageSource.UrlImage u -> {
                ObjectNode fd = mapper.createObjectNode();
                if (u.mediaTypeHint() != null && !u.mediaTypeHint().isBlank()) {
                    fd.put("mimeType", u.mediaTypeHint());
                }
                fd.put("fileUri", u.url());
                p.set("fileData", fd);
            }
            case ImageSource.FileRefImage ref -> {
                ObjectNode fd = mapper.createObjectNode();
                if (ref.mediaTypeHint() != null && !ref.mediaTypeHint().isBlank()) {
                    fd.put("mimeType", ref.mediaTypeHint());
                }
                fd.put("fileUri", ref.fileRef());
                p.set("fileData", fd);
            }
        }
        return p;
    }

    private ObjectNode encodeFunctionResponsePart(CanonicalContentPart.ToolResultPart tr) {
        ObjectNode fr = mapper.createObjectNode();
        if (tr.callId() != null && !tr.callId().isBlank()) {
            fr.put("id", tr.callId());
        }
        fr.put("name", tr.toolName());

        StringBuilder textOut = new StringBuilder();
        ArrayNode mediaParts = mapper.createArrayNode();
        for (CanonicalContentPart child : tr.content()) {
            if (child instanceof CanonicalContentPart.TextPart t) {
                if (!textOut.isEmpty()) {
                    textOut.append('\n');
                }
                textOut.append(t.text());
            } else if (child instanceof CanonicalContentPart.ImagePart img) {
                mediaParts.add(encodeImagePart(img));
            }
        }
        fr.set("response", buildFunctionResponseObject(textOut.toString(), tr.isError()));
        if (!mediaParts.isEmpty()) {
            fr.set("parts", mediaParts);
        }

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("functionResponse", fr);
        return wrapper;
    }

    private ObjectNode buildFunctionResponseObject(String rawText, boolean isError) {
        if (!isError && rawText != null && !rawText.isBlank()) {
            try {
                JsonNode parsed = mapper.readTree(rawText);
                if (parsed instanceof ObjectNode obj) {
                    return obj;
                }
            } catch (Exception ignored) {
            }
        }
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put(isError ? "error" : "output", rawText != null ? rawText : "");
        return wrapped;
    }

    private void encodeToolsAndConfig(ObjectNode root, CanonicalRequest req) {
        if (req.tools().isEmpty()) {
            return;
        }
        ArrayNode decls = mapper.createArrayNode();
        for (CanonicalToolDefinition tool : req.tools()) {
            ObjectNode d = mapper.createObjectNode();
            d.put("name", tool.name());
            if (tool.description() != null) {
                d.put("description", tool.description());
            }
            if (tool.parametersSchema() != null && !tool.parametersSchema().isNull()) {
                d.set("parametersJsonSchema", tool.parametersSchema().deepCopy());
            }
            decls.add(d);
        }
        ObjectNode toolWrapper = mapper.createObjectNode();
        toolWrapper.set("functionDeclarations", decls);
        ArrayNode toolsArr = mapper.createArrayNode();
        toolsArr.add(toolWrapper);
        root.set("tools", toolsArr);

        CanonicalToolChoice tc = req.toolChoice();
        if (tc.mode() != CanonicalToolChoice.ToolChoiceMode.OMITTED) {
            ObjectNode fcc = mapper.createObjectNode();
            switch (tc.mode()) {
                case NONE -> fcc.put("mode", "NONE");
                case AUTO -> fcc.put("mode", "AUTO");
                case REQUIRED -> fcc.put("mode", "ANY");
                case SPECIFIC -> {
                    fcc.put("mode", "ANY");
                    ArrayNode allowed = mapper.createArrayNode();
                    allowed.add(tc.specificToolName());
                    fcc.set("allowedFunctionNames", allowed);
                }
                case OMITTED -> {}
            }
            ObjectNode toolConfig = mapper.createObjectNode();
            toolConfig.set("functionCallingConfig", fcc);
            root.set("toolConfig", toolConfig);
        }
    }

    private ObjectNode encodeGenerationConfig(CanonicalRequest req, UpstreamEncodingContext context) {
        ObjectNode cfg = mapper.createObjectNode();
        CanonicalGenerationConfig g = req.generation();
        if (g.maxOutputTokens() != null && g.maxOutputTokens() > 0) {
            cfg.put("maxOutputTokens", g.maxOutputTokens());
        }
        if (g.temperature() != null) {
            cfg.put("temperature", g.temperature());
        }
        if (g.topP() != null) {
            cfg.put("topP", g.topP());
        }
        if (g.topK() != null) {
            cfg.put("topK", g.topK());
        }
        if (!g.stopSequences().isEmpty()) {
            ArrayNode stops = mapper.createArrayNode();
            int limit = Math.min(g.stopSequences().size(), 5);
            for (int i = 0; i < limit; i++) {
                stops.add(g.stopSequences().get(i));
            }
            cfg.set("stopSequences", stops);
        }
        if (g.seed() != null) {
            cfg.put("seed", g.seed());
        }
        if (g.presencePenalty() != null) {
            cfg.put("presencePenalty", g.presencePenalty());
        }
        if (g.frequencyPenalty() != null) {
            cfg.put("frequencyPenalty", g.frequencyPenalty());
        }
        if (g.candidateCount() != null && g.candidateCount() > 1) {
            cfg.put("candidateCount", g.candidateCount());
        }

        if (req.responseFormat() instanceof CanonicalResponseFormat.JsonObjectFormat) {
            cfg.put("responseMimeType", "application/json");
        } else if (req.responseFormat() instanceof CanonicalResponseFormat.JsonSchemaFormat js) {
            cfg.put("responseMimeType", "application/json");
            if (js.schema() != null) {
                cfg.set("responseJsonSchema", js.schema().deepCopy());
            }
        }

        CanonicalThinkingConfig thinking = req.thinking();
        if (thinking.mode() != CanonicalThinkingConfig.ThinkingMode.OMITTED
                || thinking.effort() != CanonicalThinkingConfig.ThinkingEffort.OMITTED) {
            ObjectNode tc = mapper.createObjectNode();
            boolean useLevel = CodecSupport.isGeminiLevelStyle(context.thinkingStyle(), context.upstreamModelName());
            if (useLevel) {
                tc.put("thinkingLevel", CodecSupport.toGeminiThinkingLevel(thinking));
            } else {
                tc.put("thinkingBudget", CodecSupport.toGeminiThinkingBudget(thinking));
            }
            if (Boolean.TRUE.equals(thinking.includeThoughts())) {
                tc.put("includeThoughts", true);
            }
            cfg.set("thinkingConfig", tc);
        }
        return cfg;
    }

    // ---- Usage & stop reason helpers ----

    static Optional<TokenUsage> parseUsageMetadata(JsonNode meta) {
        if (meta == null || !meta.isObject()) {
            return Optional.empty();
        }
        int prompt = Math.max(0, meta.path("promptTokenCount").asInt(0));
        int toolUsePrompt = Math.max(0, meta.path("toolUsePromptTokenCount").asInt(0));
        int cached = Math.max(0, meta.path("cachedContentTokenCount").asInt(0));
        int candidates = Math.max(0, meta.path("candidatesTokenCount").asInt(0));
        int thoughts = Math.max(0, meta.path("thoughtsTokenCount").asInt(0));
        return Optional.of(TokenUsage.fromGemini(prompt, toolUsePrompt, cached, candidates, thoughts));
    }

    private ObjectNode encodeUsageMetadata(TokenUsage usage) {
        TokenUsage u = usage != null ? usage : TokenUsage.empty();
        ObjectNode node = mapper.createObjectNode();
        node.put("promptTokenCount", u.totalInputTokens());
        if (u.cachedReadTokens() > 0) {
            node.put("cachedContentTokenCount", u.cachedReadTokens());
        }
        node.put("candidatesTokenCount", u.completionTokens());
        if (u.reasoningTokens() > 0) {
            node.put("thoughtsTokenCount", u.reasoningTokens());
        }
        node.put("totalTokenCount", u.totalTokens());
        return node;
    }

    private static CanonicalStopReason mapFinishReason(String finishReason, List<CanonicalContentPart> parts) {
        boolean hasTools = parts.stream().anyMatch(p -> p instanceof CanonicalContentPart.ToolCallPart);
        if (finishReason == null) {
            return hasTools ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN;
        }
        return switch (finishReason) {
            case "MAX_TOKENS" -> CanonicalStopReason.MAX_TOKENS;
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> CanonicalStopReason.CONTENT_FILTER;
            case "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" -> CanonicalStopReason.MALFORMED_TOOL_CALL;
            default -> hasTools ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN;
        };
    }

    private static String encodeFinishReason(CanonicalStopReason reason) {
        if (reason == null) {
            return "STOP";
        }
        return switch (reason) {
            case MAX_TOKENS -> "MAX_TOKENS";
            case CONTENT_FILTER, REFUSAL -> "SAFETY";
            case MALFORMED_TOOL_CALL -> "MALFORMED_FUNCTION_CALL";
            case ERROR -> "OTHER";
            case END_TURN, TOOL_CALLS, STOP_SEQUENCE -> "STOP";
        };
    }

    // ---- Streaming Decoder (upstream always uses ?alt=sse) ----

    private final class GeminiStreamDecoder implements UpstreamStreamDecoder {

        private final String virtualModel;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private boolean started;
        private boolean finished;
        private int nextBlockIndex;
        private int activeBlockIndex = -1;
        private BlockKind activeBlockKind;
        private boolean sawToolCall;
        private int toolSeq;

        private GeminiStreamDecoder(String virtualModel) {
            this.virtualModel = virtualModel;
        }

        @Override
        public List<CanonicalStreamEvent> feed(byte[] upstreamChunk) {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            for (SseEventSplitter.SseEvent ev : splitter.push(upstreamChunk)) {
                processChunk(ev.dataPayload(), out);
            }
            return out;
        }

        @Override
        public List<CanonicalStreamEvent> complete() {
            List<CanonicalStreamEvent> out = new ArrayList<>();
            for (SseEventSplitter.SseEvent ev : splitter.flushRemainder()) {
                processChunk(ev.dataPayload(), out);
            }
            if (!finished && started) {
                closeActiveBlock(out);
                finished = true;
                out.add(new CanonicalStreamEvent.MessageFinish(
                        sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN, null));
            }
            return out;
        }

        private void processChunk(String data, List<CanonicalStreamEvent> out) {
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            try {
                JsonNode root = mapper.readTree(data);
                if (!root.isObject()) {
                    return;
                }
                if (!started) {
                    String id = CodecSupport.asNullableText(root, "responseId");
                    if (id == null || id.isBlank()) {
                        id = CodecSupport.generateId("gem_");
                    }
                    String model = (virtualModel != null && !virtualModel.isBlank())
                            ? virtualModel : root.path("modelVersion").asText("");
                    out.add(new CanonicalStreamEvent.MessageStart(id, model, CodecSupport.nowEpochSeconds()));
                    started = true;
                }

                parseUsageMetadata(root.get("usageMetadata"))
                        .ifPresent(u -> out.add(new CanonicalStreamEvent.UsageUpdate(u, true)));

                JsonNode candidates = root.get("candidates");
                if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                    JsonNode cand0 = candidates.get(0);
                    JsonNode parts = cand0.path("content").get("parts");
                    if (parts != null && parts.isArray()) {
                        for (JsonNode part : parts) {
                            String thoughtSig = CodecSupport.asNullableText(part, "thoughtSignature");
                            if (part.path("thought").asBoolean(false)) {
                                int idx = ensureActiveBlock(BlockKind.THINKING, out);
                                String text = part.path("text").asText("");
                                if (!text.isEmpty()) {
                                    out.add(new CanonicalStreamEvent.ThinkingDelta(idx, text));
                                }
                                if (thoughtSig != null && !thoughtSig.isBlank()) {
                                    out.add(new CanonicalStreamEvent.ThinkingSignatureDelta(
                                            idx, thoughtSig, ProtocolKind.GEMINI_GENERATE_CONTENT));
                                }
                            } else if (part.hasNonNull("text")) {
                                int idx = ensureActiveBlock(BlockKind.TEXT, out);
                                String text = part.get("text").asText();
                                if (!text.isEmpty()) {
                                    out.add(new CanonicalStreamEvent.TextDelta(idx, text));
                                }
                            } else if (part.hasNonNull("functionCall")) {
                                closeActiveBlock(out);
                                sawToolCall = true;
                                JsonNode fc = part.get("functionCall");
                                String callId = CodecSupport.asNullableText(fc, "id");
                                if (callId == null || callId.isBlank()) {
                                    callId = "call_gem_" + (toolSeq++);
                                }
                                String name = fc.path("name").asText("");
                                JsonNode args = fc.get("args");
                                String serializedArgs = (args != null && !args.isNull())
                                        ? CodecSupport.writeJsonString(mapper, args) : "{}";
                                int idx = nextBlockIndex++;
                                out.add(new CanonicalStreamEvent.ToolCallStart(
                                        idx, callId, null, name, thoughtSig, ProtocolKind.GEMINI_GENERATE_CONTENT));
                                out.add(new CanonicalStreamEvent.ToolCallArgsDelta(idx, callId, serializedArgs));
                                out.add(new CanonicalStreamEvent.BlockStop(idx));
                            }
                        }
                    }

                    String finishReason = CodecSupport.asNullableText(cand0, "finishReason");
                    if (finishReason != null && !finishReason.isBlank() && !finished) {
                        closeActiveBlock(out);
                        CanonicalStopReason mapped = switch (finishReason) {
                            case "MAX_TOKENS" -> CanonicalStopReason.MAX_TOKENS;
                            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" ->
                                    CanonicalStopReason.CONTENT_FILTER;
                            case "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" ->
                                    CanonicalStopReason.MALFORMED_TOOL_CALL;
                            default -> sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN;
                        };
                        finished = true;
                        out.add(new CanonicalStreamEvent.MessageFinish(mapped, null));
                    }
                } else if (root.path("promptFeedback").hasNonNull("blockReason") && !finished) {
                    closeActiveBlock(out);
                    finished = true;
                    out.add(new CanonicalStreamEvent.MessageFinish(CanonicalStopReason.CONTENT_FILTER, null));
                }
            } catch (Exception ignored) {
            }
        }

        private int ensureActiveBlock(BlockKind kind, List<CanonicalStreamEvent> out) {
            if (activeBlockIndex >= 0 && activeBlockKind == kind) {
                return activeBlockIndex;
            }
            closeActiveBlock(out);
            activeBlockIndex = nextBlockIndex++;
            activeBlockKind = kind;
            out.add(new CanonicalStreamEvent.BlockStart(activeBlockIndex, kind));
            return activeBlockIndex;
        }

        private void closeActiveBlock(List<CanonicalStreamEvent> out) {
            if (activeBlockIndex >= 0) {
                out.add(new CanonicalStreamEvent.BlockStop(activeBlockIndex));
                activeBlockIndex = -1;
                activeBlockKind = null;
            }
        }
    }

    // ---- Streaming Encoder (buffers tool args into complete JSON Objects; supports alt=sse & JSON array) ----

    private final class GeminiStreamEncoder implements DownstreamStreamEncoder {

        private final String model;
        private final boolean altSse;
        private String responseId = CodecSupport.generateId("gem_");
        private boolean emittedAnyChunk;
        private boolean finished;
        private TokenUsage latestUsage = TokenUsage.empty();
        private final Map<Integer, PendingToolCall> pendingTools = new HashMap<>();

        private static final class PendingToolCall {
            final String callId;
            final String name;
            final String thoughtSignature;
            final ProtocolKind origin;
            final StringBuilder argsBuffer = new StringBuilder();

            PendingToolCall(String callId, String name, String thoughtSignature, ProtocolKind origin) {
                this.callId = callId;
                this.name = name;
                this.thoughtSignature = thoughtSignature;
                this.origin = origin;
            }
        }

        private GeminiStreamEncoder(CanonicalRequest inboundRequest) {
            this.model = inboundRequest.model();
            this.altSse = inboundRequest.options().geminiAltSse();
        }

        @Override
        public String contentType() {
            return altSse ? "text/event-stream" : "application/json";
        }

        @Override
        public List<byte[]> encode(CanonicalStreamEvent event) {
            List<byte[]> frames = new ArrayList<>();
            switch (event) {
                case CanonicalStreamEvent.MessageStart start -> {
                    if (start.responseId() != null && !start.responseId().isBlank()) {
                        this.responseId = start.responseId();
                    }
                }
                case CanonicalStreamEvent.TextDelta td -> {
                    ObjectNode p = mapper.createObjectNode();
                    p.put("text", td.textDelta());
                    frames.add(wrapChunk(buildChunk(List.of(p), null, null)));
                }
                case CanonicalStreamEvent.RefusalDelta ref -> {
                    ObjectNode p = mapper.createObjectNode();
                    p.put("text", ref.refusalDelta());
                    frames.add(wrapChunk(buildChunk(List.of(p), null, null)));
                }
                case CanonicalStreamEvent.ThinkingDelta th -> {
                    ObjectNode p = mapper.createObjectNode();
                    p.put("thought", true);
                    p.put("text", th.thinkingDelta());
                    frames.add(wrapChunk(buildChunk(List.of(p), null, null)));
                }
                case CanonicalStreamEvent.ToolCallStart tcs -> pendingTools.put(
                        tcs.blockIndex(),
                        new PendingToolCall(tcs.callId(), tcs.name(), tcs.thoughtSignature(), tcs.originProtocol())
                );
                case CanonicalStreamEvent.ToolCallArgsDelta tcad -> {
                    PendingToolCall ptc = pendingTools.get(tcad.blockIndex());
                    if (ptc != null) {
                        ptc.argsBuffer.append(tcad.partialJsonDelta());
                    }
                }
                case CanonicalStreamEvent.BlockStop stop -> {
                    PendingToolCall ptc = pendingTools.remove(stop.blockIndex());
                    if (ptc != null) {
                        frames.add(wrapChunk(buildChunk(List.of(buildToolCallPart(ptc)), null, null)));
                    }
                }
                case CanonicalStreamEvent.UsageUpdate uu -> {
                    latestUsage = uu.cumulative() ? uu.usage() : latestUsage.mergeMax(uu.usage());
                }
                case CanonicalStreamEvent.MessageFinish mf -> emitTerminal(mf.stopReason(), frames);
                case CanonicalStreamEvent.StreamError err -> {
                    ObjectNode errObj = mapper.createObjectNode();
                    ObjectNode inner = mapper.createObjectNode();
                    inner.put("code", 500);
                    inner.put("status", "INTERNAL");
                    inner.put("message", err.message() != null ? err.message() : "Upstream stream error");
                    errObj.set("error", inner);
                    frames.add(wrapChunk(errObj));
                    if (!altSse) {
                        frames.add("]".getBytes(StandardCharsets.UTF_8));
                    }
                    finished = true;
                }
                case CanonicalStreamEvent.BlockStart ignored -> {}
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

        private void emitTerminal(CanonicalStopReason reason, List<byte[]> frames) {
            if (finished) {
                return;
            }
            List<ObjectNode> finalParts = new ArrayList<>();
            for (PendingToolCall ptc : pendingTools.values()) {
                finalParts.add(buildToolCallPart(ptc));
            }
            pendingTools.clear();
            frames.add(wrapChunk(buildChunk(finalParts, encodeFinishReason(reason), latestUsage)));
            if (!altSse) {
                frames.add("\n]".getBytes(StandardCharsets.UTF_8));
            }
            finished = true;
        }

        private ObjectNode buildToolCallPart(PendingToolCall ptc) {
            ObjectNode p = mapper.createObjectNode();
            ObjectNode fc = mapper.createObjectNode();
            if (ptc.callId != null && !ptc.callId.isBlank()) {
                fc.put("id", ptc.callId);
            }
            fc.put("name", ptc.name);
            fc.set("args", CodecSupport.parseArgumentsToObject(mapper, ptc.argsBuffer.toString()));
            p.set("functionCall", fc);
            if (CodecSupport.isSameOrigin(ptc.origin, ProtocolKind.GEMINI_GENERATE_CONTENT)
                    && ptc.thoughtSignature != null) {
                p.put("thoughtSignature", ptc.thoughtSignature);
            }
            return p;
        }

        private ObjectNode buildChunk(List<ObjectNode> parts, String finishReason, TokenUsage usage) {
            ObjectNode chunk = mapper.createObjectNode();
            chunk.put("responseId", responseId);
            chunk.put("modelVersion", model);

            ObjectNode content = mapper.createObjectNode();
            content.put("role", "model");
            ArrayNode partsArr = mapper.createArrayNode();
            for (ObjectNode p : parts) {
                partsArr.add(p);
            }
            content.set("parts", partsArr);

            ObjectNode candidate = mapper.createObjectNode();
            candidate.put("index", 0);
            candidate.set("content", content);
            if (finishReason != null) {
                candidate.put("finishReason", finishReason);
            }
            ArrayNode candidates = mapper.createArrayNode();
            candidates.add(candidate);
            chunk.set("candidates", candidates);
            if (usage != null) {
                chunk.set("usageMetadata", encodeUsageMetadata(usage));
            }
            return chunk;
        }

        private byte[] wrapChunk(ObjectNode chunk) {
            String json = CodecSupport.writeJsonString(mapper, chunk);
            if (altSse) {
                emittedAnyChunk = true;
                return SseEventSplitter.buildDataFrame(json);
            }
            String prefix = emittedAnyChunk ? ",\n" : "[\n";
            emittedAnyChunk = true;
            return (prefix + json).getBytes(StandardCharsets.UTF_8);
        }
    }

    // ---- Passthrough Streaming Bridge ----

    private final class GeminiPassthroughBridge implements StreamBridge {

        private final CanonicalRequest request;
        private final TokenEstimator tokenEstimator;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private final StringBuilder streamedText = new StringBuilder();
        private TokenUsage observedUsage;
        private boolean emittedJsonArrayStart;

        private GeminiPassthroughBridge(CanonicalRequest request, TokenEstimator tokenEstimator) {
            this.request = request;
            this.tokenEstimator = tokenEstimator;
        }

        @Override
        public String contentType() {
            return request.options().geminiAltSse() ? "text/event-stream" : "application/json";
        }

        @Override
        public List<byte[]> feed(byte[] upstreamChunk) {
            return inspectEvents(splitter.push(upstreamChunk));
        }

        @Override
        public List<byte[]> complete() {
            List<byte[]> out = new ArrayList<>(inspectEvents(splitter.flushRemainder()));
            if (!request.options().geminiAltSse()) {
                out.add((emittedJsonArrayStart ? "\n]" : "[]").getBytes(StandardCharsets.UTF_8));
            }
            return out;
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
                    0,
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
                inspectPayload(ev.dataPayload());
                if (request.options().geminiAltSse()) {
                    out.add(ev.rawBytes());
                } else if (!ev.dataPayload().isEmpty()) {
                    String prefix = emittedJsonArrayStart ? ",\n" : "[\n";
                    emittedJsonArrayStart = true;
                    out.add((prefix + ev.dataPayload()).getBytes(StandardCharsets.UTF_8));
                }
            }
            return out;
        }

        private void inspectPayload(String data) {
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            try {
                JsonNode root = mapper.readTree(data);
                if (!root.isObject()) {
                    return;
                }
                parseUsageMetadata(root.get("usageMetadata")).ifPresent(u -> observedUsage = u);
                JsonNode candidates = root.get("candidates");
                if (candidates != null && candidates.isArray()) {
                    for (JsonNode c : candidates) {
                        JsonNode parts = c.path("content").get("parts");
                        if (parts != null && parts.isArray()) {
                            for (JsonNode p : parts) {
                                if (p.hasNonNull("text") && p.get("text").isTextual()) {
                                    CodecSupport.appendBounded(streamedText, p.get("text").asText());
                                }
                                if (p.hasNonNull("functionCall")) {
                                    CodecSupport.appendBounded(streamedText, p.get("functionCall").toString());
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}
