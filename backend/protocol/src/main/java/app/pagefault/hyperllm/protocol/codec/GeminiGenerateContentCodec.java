package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.io.JsonUtf8Writer;
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
import app.pagefault.hyperllm.protocol.ir.JsonUtf8Text;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.RequestMetrics;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.UnsupportedFeatureFlag;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.ir.ValidatedJsonObject;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
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

    private static final byte[] GEM_SSE_DATA_PREFIX = "data: ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GEM_SSE_TAIL = "\n\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GEM_ARRAY_FIRST = "[\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GEM_ARRAY_NEXT = ",\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GEM_ARRAY_END = "\n]".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GEM_TEXT_PART_OPEN = "{\"text\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GEM_THOUGHT_PART_OPEN = "{\"thought\":true,\"text\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GEM_CHUNK_SUFFIX = "}]}}]}".getBytes(StandardCharsets.US_ASCII);

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
        return decodeRequest(rawBody, 0, rawBody != null ? rawBody.length : 0, context);
    }

    @Override
    public CanonicalRequest decodeRequest(byte[] rawBody, int offset, int length, InboundRequestContext context) {
        try (JsonParser parser = CodecSupport.openObjectParser(mapper, rawBody, offset, length)) {
            return decodeStreamingRoot(parser, context, rawBody, offset, offset + length);
        } catch (HyperLlmException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body", ex);
        }
    }

    @Override
    public CanonicalRequest decodeRequest(InputStream bodyStream, InboundRequestContext context) {
        try (JsonParser parser = CodecSupport.openObjectParser(mapper, bodyStream)) {
            return decodeStreamingRoot(parser, context, null, 0, 0);
        } catch (HyperLlmException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body", ex);
        }
    }

    @Override
    public void encodeRequest(CanonicalRequest req, UpstreamEncodingContext context, ByteSink out) {
        try (JsonGenerator gen = CodecSupport.newUtf8Generator(mapper, out)) {
            gen.writeStartObject();

            boolean hasSystemText = false;
            for (CanonicalSystemPart sp : req.systemInstructions()) {
                if (!sp.isEmpty()) {
                    hasSystemText = true;
                    break;
                }
            }
            if (hasSystemText) {
                gen.writeObjectFieldStart("systemInstruction");
                gen.writeArrayFieldStart("parts");
                for (CanonicalSystemPart sp : req.systemInstructions()) {
                    if (!sp.isEmpty()) {
                        gen.writeStartObject();
                        gen.writeFieldName("text");
                        sp.writeJsonValue(gen);
                        gen.writeEndObject();
                    }
                }
                gen.writeEndArray();
                gen.writeEndObject();
            }

            boolean injectSkipSig = CodecSupport.isGeminiLevelStyle(context.thinkingStyle(), context.upstreamModelName());
            writeContents(gen, req.turns(), injectSkipSig);

            writeToolsAndConfig(gen, req);
            writeGenerationConfigIfNonEmpty(gen, req, context);
            if (req.cache().geminiCachedContent() != null && !req.cache().geminiCachedContent().isBlank()) {
                gen.writeStringField("cachedContent", req.cache().geminiCachedContent());
            }

            gen.writeEndObject();
        } catch (HyperLlmException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize Gemini request JSON", ex);
        }
    }

    // ---- Streaming request decoding (zero inbound DOM tree for systemInstruction, contents[], tools[]) ----

    private CanonicalRequest decodeStreamingRoot(
            JsonParser parser,
            InboundRequestContext context,
            byte[] rawBacking,
            int baseOffset,
            int endOffset
    ) throws IOException {
        String bodyModel = null;
        boolean hasContentsArray = false;
        List<UnsupportedFeatureFlag> unsupported = new ArrayList<>();
        List<CanonicalSystemPart> systemParts = new ArrayList<>();
        List<CanonicalSystemPart> leadingContentSystemParts = new ArrayList<>();
        List<CanonicalTurn> turns = new ArrayList<>();
        List<CanonicalToolDefinition> tools = new ArrayList<>();
        JsonNode toolConfigNode = null;
        JsonNode genCfgNode = null;
        String cachedContent = null;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken valTok = parser.nextToken();
            if (valTok == null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body");
            }
            switch (field) {
                case "model" -> bodyModel = CodecSupport.readNullableString(parser);
                case "systemInstruction" -> readStreamingSystemInstruction(parser, rawBacking, baseOffset, endOffset, systemParts);
                case "contents" -> {
                    if (valTok == JsonToken.START_ARRAY) {
                        hasContentsArray = true;
                        readStreamingContents(parser, rawBacking, baseOffset, endOffset, leadingContentSystemParts, turns, unsupported);
                    } else {
                        parser.skipChildren();
                    }
                }
                case "tools" -> {
                    if (valTok == JsonToken.START_ARRAY) {
                        readStreamingTools(parser, rawBacking, baseOffset, endOffset, tools, unsupported);
                    } else {
                        parser.skipChildren();
                    }
                }
                case "toolConfig" -> toolConfigNode = mapper.readTree(parser);
                case "generationConfig" -> genCfgNode = mapper.readTree(parser);
                case "cachedContent" -> cachedContent = CodecSupport.readNullableString(parser);
                default -> parser.skipChildren();
            }
        }

        String model = (context != null && context.pathModel() != null && !context.pathModel().isBlank())
                ? context.pathModel().trim()
                : (bodyModel != null ? bodyModel.trim() : null);
        if (model == null || model.isBlank()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                    "Model must be specified in the Gemini URL path '/v1beta/models/{model}:generateContent'");
        }
        boolean stream = context != null && Boolean.TRUE.equals(context.pathStream());
        boolean altSse = context == null || context.geminiAltSse();

        if (!hasContentsArray || (leadingContentSystemParts.isEmpty() && turns.isEmpty())) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'contents' must be a non-empty array");
        }
        if (!leadingContentSystemParts.isEmpty()) {
            systemParts.addAll(leadingContentSystemParts);
        }

        CanonicalToolChoice toolChoice = decodeToolConfig(toolConfigNode);
        CanonicalGenerationConfig generation = decodeGenerationConfig(genCfgNode);
        CanonicalResponseFormat responseFormat = decodeResponseFormat(genCfgNode);
        CanonicalThinkingConfig thinking = decodeThinkingConfig(genCfgNode != null ? genCfgNode.get("thinkingConfig") : null);
        CanonicalCacheConfig cache = new CanonicalCacheConfig(null, null, null, cachedContent);
        CanonicalProtocolOptions options = new CanonicalProtocolOptions(true, altSse, null, null, null, null, null);

        RequestMetrics metrics = TokenEstimator.computeRequestMetrics(systemParts, turns, tools);
        return CanonicalRequest.ofOwned(
                ProtocolKind.GEMINI_GENERATE_CONTENT, model, stream, systemParts, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, unsupported, metrics
        );
    }

    private void readStreamingSystemInstruction(
            JsonParser parser,
            byte[] rawBacking,
            int baseOffset,
            int endOffset,
            List<CanonicalSystemPart> systemParts
    ) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return;
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String f = parser.currentName();
            JsonToken t = parser.nextToken();
            if ("parts".equals(f) && t == JsonToken.START_ARRAY) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        parser.skipChildren();
                        continue;
                    }
                    JsonUtf8Text text = null;
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String pf = parser.currentName();
                        JsonToken pt = parser.nextToken();
                        if ("text".equals(pf) && pt == JsonToken.VALUE_STRING) {
                            text = JsonUtf8Text.readFromParser(parser, rawBacking, baseOffset, endOffset);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    if (text != null) {
                        systemParts.add(CanonicalSystemPart.ofUtf8(text, false, null));
                    }
                }
            } else {
                parser.skipChildren();
            }
        }
    }

    private void readStreamingTools(
            JsonParser parser,
            byte[] rawBacking,
            int baseOffset,
            int endOffset,
            List<CanonicalToolDefinition> out,
            List<UnsupportedFeatureFlag> unsupported
    ) throws IOException {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken t = parser.nextToken();
                if ("functionDeclarations".equals(field) && t == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) {
                            parser.skipChildren();
                            continue;
                        }
                        String name = "";
                        String description = null;
                        ValidatedJsonObject paramsJsonSchema = null;
                        ValidatedJsonObject paramsLegacy = null;
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String df = parser.currentName();
                            JsonToken dt = parser.nextToken();
                            switch (df) {
                                case "name" -> {
                                    if (dt == JsonToken.VALUE_STRING) {
                                        name = parser.getText();
                                    } else {
                                        parser.skipChildren();
                                    }
                                }
                                case "description" -> description = CodecSupport.readNullableString(parser);
                                case "parametersJsonSchema" -> {
                                    if (dt == JsonToken.START_OBJECT) {
                                        paramsJsonSchema = ValidatedJsonObject.readFromParser(
                                                parser, mapper, rawBacking, baseOffset, endOffset);
                                    } else {
                                        parser.skipChildren();
                                    }
                                }
                                case "parameters" -> {
                                    if (dt == JsonToken.START_OBJECT) {
                                        paramsLegacy = ValidatedJsonObject.readFromParser(
                                                parser, mapper, rawBacking, baseOffset, endOffset);
                                    } else {
                                        parser.skipChildren();
                                    }
                                }
                                default -> parser.skipChildren();
                            }
                        }
                        ValidatedJsonObject effectiveSchema = paramsJsonSchema != null ? paramsJsonSchema : paramsLegacy;
                        out.add(CanonicalToolDefinition.ofValidated(name, description, effectiveSchema, null, null));
                    }
                } else {
                    parser.skipChildren();
                    unsupported.add(UnsupportedFeatureFlag.degradable("builtin_tool:" + field,
                            "Gemini built-in tool '" + field + "' cannot be translated across vendors"));
                }
            }
        }
    }

    private void readStreamingContents(
            JsonParser parser,
            byte[] rawBacking,
            int baseOffset,
            int endOffset,
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
            List<CanonicalContentPart> parts = List.of();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken t = parser.nextToken();
                if ("role".equals(f) && t == JsonToken.VALUE_STRING) {
                    roleStr = parser.getText().toLowerCase(Locale.ROOT);
                } else if ("parts".equals(f) && t == JsonToken.START_ARRAY) {
                    parts = readStreamingParts(parser, rawBacking, baseOffset, endOffset, unsupported);
                } else {
                    parser.skipChildren();
                }
            }
            if ("system".equals(roleStr) || "developer".equals(roleStr)) {
                boolean devPriority = "developer".equals(roleStr);
                if (!sawNonSystem) {
                    for (CanonicalContentPart p : parts) {
                        if (p instanceof CanonicalContentPart.TextPart t && !t.isEmpty()) {
                            leadingSystemParts.add(CanonicalSystemPart.ofUtf8(t.utf8Text(), devPriority, null));
                        }
                    }
                } else {
                    turns.add(CanonicalTurn.ofOwned(devPriority ? CanonicalRole.DEVELOPER : CanonicalRole.SYSTEM, parts));
                }
                continue;
            }
            sawNonSystem = true;
            CanonicalRole role = switch (roleStr) {
                case "model", "assistant" -> CanonicalRole.ASSISTANT;
                case "function", "tool" -> CanonicalRole.TOOL;
                default -> CanonicalRole.USER;
            };
            turns.add(CanonicalTurn.ofOwned(role, parts));
        }
    }

    private List<CanonicalContentPart> readStreamingParts(
            JsonParser parser,
            byte[] rawBacking,
            int baseOffset,
            int endOffset,
            List<UnsupportedFeatureFlag> unsupported
    ) throws IOException {
        List<CanonicalContentPart> out = new ArrayList<>(4);
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            boolean isThought = false;
            String thoughtSig = null;
            JsonUtf8Text text = null;
            boolean hasInlineData = false;
            String inlineMime = "image/png";
            String inlineData = "";
            boolean hasFileData = false;
            String fileUri = "";
            String fileMime = null;
            boolean hasFunctionCall = false;
            String fcId = null;
            String fcName = "";
            ValidatedJsonObject fcArgs = null;
            boolean hasFunctionResponse = false;
            String frId = null;
            String frName = "";
            JsonNode frResp = null;
            List<CanonicalContentPart> frNestedParts = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String f = parser.currentName();
                JsonToken t = parser.nextToken();
                switch (f) {
                    case "thought" -> {
                        if (t == JsonToken.VALUE_TRUE) {
                            isThought = true;
                        } else if (t != JsonToken.VALUE_FALSE) {
                            parser.skipChildren();
                        }
                    }
                    case "thoughtSignature" -> thoughtSig = CodecSupport.readNullableString(parser);
                    case "text" -> text = JsonUtf8Text.readNullableFromParser(parser, rawBacking, baseOffset, endOffset);
                    case "inlineData" -> {
                        if (t == JsonToken.START_OBJECT) {
                            hasInlineData = true;
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String inf = parser.currentName();
                                JsonToken intok = parser.nextToken();
                                if ("mimeType".equals(inf) && intok == JsonToken.VALUE_STRING) {
                                    inlineMime = parser.getText();
                                } else if ("data".equals(inf) && intok == JsonToken.VALUE_STRING) {
                                    inlineData = parser.getText();
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "fileData" -> {
                        if (t == JsonToken.START_OBJECT) {
                            hasFileData = true;
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String ff = parser.currentName();
                                JsonToken ft = parser.nextToken();
                                if ("fileUri".equals(ff) && ft == JsonToken.VALUE_STRING) {
                                    fileUri = parser.getText();
                                } else if ("mimeType".equals(ff)) {
                                    fileMime = CodecSupport.readNullableString(parser);
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "functionCall" -> {
                        if (t == JsonToken.START_OBJECT) {
                            hasFunctionCall = true;
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String ff = parser.currentName();
                                JsonToken ft = parser.nextToken();
                                if ("id".equals(ff)) {
                                    fcId = CodecSupport.readNullableString(parser);
                                } else if ("name".equals(ff) && ft == JsonToken.VALUE_STRING) {
                                    fcName = parser.getText();
                                } else if ("args".equals(ff) && ft == JsonToken.START_OBJECT) {
                                    fcArgs = ValidatedJsonObject.readFromParser(
                                            parser, mapper, rawBacking, baseOffset, endOffset);
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "functionResponse" -> {
                        if (t == JsonToken.START_OBJECT) {
                            hasFunctionResponse = true;
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String ff = parser.currentName();
                                JsonToken ft = parser.nextToken();
                                if ("id".equals(ff)) {
                                    frId = CodecSupport.readNullableString(parser);
                                } else if ("name".equals(ff) && ft == JsonToken.VALUE_STRING) {
                                    frName = parser.getText();
                                } else if ("response".equals(ff)) {
                                    frResp = mapper.readTree(parser);
                                } else if ("parts".equals(ff) && ft == JsonToken.START_ARRAY) {
                                    frNestedParts = readStreamingParts(parser, rawBacking, baseOffset, endOffset, unsupported);
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

            if (isThought) {
                out.add(CanonicalContentPart.ThinkingPart.ofUtf8(
                        text != null ? text : JsonUtf8Text.EMPTY, thoughtSig, null, ProtocolKind.GEMINI_GENERATE_CONTENT));
            } else if (text != null) {
                out.add(CanonicalContentPart.TextPart.ofUtf8(text));
            } else if (hasInlineData) {
                if (!inlineMime.toLowerCase(Locale.ROOT).startsWith("image/")) {
                    unsupported.add(new UnsupportedFeatureFlag("inline_media:" + inlineMime,
                            "Non-image inlineData MIME type '" + inlineMime + "' is outside text/image input scope"));
                } else {
                    out.add(new CanonicalContentPart.ImagePart(
                            new ImageSource.Base64Image(inlineMime, inlineData), ImageDetail.AUTO, null));
                }
            } else if (hasFileData) {
                if (fileUri.startsWith("gs://") || fileUri.contains("generativelanguage.googleapis.com/")) {
                    out.add(new CanonicalContentPart.ImagePart(
                            new ImageSource.FileRefImage(ProtocolKind.GEMINI_GENERATE_CONTENT, fileUri, fileMime),
                            ImageDetail.AUTO, null));
                } else if (fileUri.startsWith("http://") || fileUri.startsWith("https://")) {
                    out.add(new CanonicalContentPart.ImagePart(
                            new ImageSource.UrlImage(fileUri, fileMime != null ? fileMime : CodecSupport.guessMimeFromUrl(fileUri)),
                            ImageDetail.AUTO, null));
                } else {
                    unsupported.add(new UnsupportedFeatureFlag("file_uri", "Unsupported Gemini fileUri: " + fileUri));
                }
            } else if (hasFunctionCall) {
                out.add(CanonicalContentPart.ToolCallPart.ofValidatedObjectArgs(
                        fcId, null, fcName,
                        fcArgs != null ? fcArgs : ValidatedJsonObject.EMPTY_OBJECT,
                        thoughtSig, ProtocolKind.GEMINI_GENERATE_CONTENT));
            } else if (hasFunctionResponse) {
                List<CanonicalContentPart> resParts = new ArrayList<>();
                if (frResp != null && !frResp.isNull()) {
                    if (frResp.isObject() && frResp.size() == 1 && frResp.hasNonNull("output") && frResp.get("output").isTextual()) {
                        resParts.add(new CanonicalContentPart.TextPart(frResp.get("output").asText()));
                    } else if (frResp.isObject() && frResp.size() == 1 && frResp.hasNonNull("error") && frResp.get("error").isTextual()) {
                        resParts.add(new CanonicalContentPart.TextPart(frResp.get("error").asText()));
                    } else {
                        resParts.add(new CanonicalContentPart.TextPart(CodecSupport.writeJsonString(mapper, frResp)));
                    }
                }
                if (frNestedParts != null) {
                    resParts.addAll(frNestedParts);
                }
                boolean isErr = frResp != null && frResp.isObject() && frResp.hasNonNull("error") && frResp.size() == 1;
                out.add(new CanonicalContentPart.ToolResultPart(frId, frName, resParts, isErr, null));
            }
        }
        return out;
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
    public DownstreamStreamEncoder newStreamEncoder(StreamRequestMetadata inboundMeta) {
        return new GeminiStreamEncoder(inboundMeta);
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

    // ---- Streaming encoding helpers (zero outbound DOM tree, turn-range grouping) ----

    private void writeContents(
            JsonGenerator gen,
            List<CanonicalTurn> turns,
            boolean injectSkipSig
    ) throws IOException {
        gen.writeArrayFieldStart("contents");
        int n = turns.size();
        int i = 0;
        while (i < n) {
            CanonicalTurn startTurn = turns.get(i);
            String groupRole = (startTurn.role() == CanonicalRole.ASSISTANT) ? "model" : "user";
            if (!hasEmittablePart(startTurn, groupRole)) {
                i++;
                continue;
            }
            int groupEnd = i + 1;
            while (groupEnd < n) {
                CanonicalTurn nextTurn = turns.get(groupEnd);
                String nextRole = (nextTurn.role() == CanonicalRole.ASSISTANT) ? "model" : "user";
                if (!hasEmittablePart(nextTurn, nextRole)) {
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
            gen.writeArrayFieldStart("parts");
            for (int k = i; k < groupEnd; k++) {
                writeTurnParts(gen, turns.get(k), groupRole, injectSkipSig);
            }
            gen.writeEndArray();
            gen.writeEndObject();

            i = groupEnd;
        }
        gen.writeEndArray();
    }

    private static boolean hasEmittablePart(CanonicalTurn turn, String targetRole) {
        for (CanonicalContentPart part : turn.parts()) {
            switch (part) {
                case CanonicalContentPart.TextPart t -> {
                    if (!t.isEmpty()) {
                        return true;
                    }
                }
                case CanonicalContentPart.RefusalPart r -> {
                    if (!r.isEmpty()) {
                        return true;
                    }
                }
                case CanonicalContentPart.ImagePart ignored -> {
                    return true;
                }
                case CanonicalContentPart.ThinkingPart th -> {
                    if ("model".equals(targetRole)
                            && CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.GEMINI_GENERATE_CONTENT)) {
                        return true;
                    }
                }
                case CanonicalContentPart.ToolCallPart ignored -> {
                    return true;
                }
                case CanonicalContentPart.ToolResultPart ignored -> {
                    return true;
                }
                case CanonicalContentPart.RedactedThinkingPart ignored -> {}
            }
        }
        return false;
    }

    private void writeTurnParts(
            JsonGenerator gen,
            CanonicalTurn turn,
            String targetRole,
            boolean injectSkipSig
    ) throws IOException {
        boolean attachedSigOnFirstCall = false;
        for (CanonicalContentPart part : turn.parts()) {
            switch (part) {
                case CanonicalContentPart.TextPart t -> {
                    if (!t.isEmpty()) {
                        gen.writeStartObject();
                        gen.writeFieldName("text");
                        t.writeJsonValue(gen);
                        gen.writeEndObject();
                    }
                }
                case CanonicalContentPart.RefusalPart r -> {
                    if (!r.isEmpty()) {
                        gen.writeStartObject();
                        gen.writeFieldName("text");
                        r.writeJsonValue(gen);
                        gen.writeEndObject();
                    }
                }
                case CanonicalContentPart.ImagePart img -> writeImagePart(gen, img);
                case CanonicalContentPart.ThinkingPart th -> {
                    if ("model".equals(targetRole)
                            && CodecSupport.isSameOrigin(th.originProtocol(), ProtocolKind.GEMINI_GENERATE_CONTENT)) {
                        gen.writeStartObject();
                        gen.writeBooleanField("thought", true);
                        gen.writeFieldName("text");
                        th.writeJsonValue(gen);
                        if (th.signature() != null && !th.signature().isBlank()) {
                            gen.writeStringField("thoughtSignature", th.signature());
                        }
                        gen.writeEndObject();
                    }
                }
                case CanonicalContentPart.ToolCallPart tc -> {
                    gen.writeStartObject();
                    gen.writeObjectFieldStart("functionCall");
                    if (tc.callId() != null && !tc.callId().isBlank()) {
                        gen.writeStringField("id", tc.callId());
                    }
                    gen.writeStringField("name", tc.name());
                    gen.writeFieldName("args");
                    CodecSupport.writeArgumentsAsObject(gen, mapper, tc);
                    gen.writeEndObject();
                    if (CodecSupport.isSameOrigin(tc.originProtocol(), ProtocolKind.GEMINI_GENERATE_CONTENT)
                            && tc.thoughtSignature() != null && !tc.thoughtSignature().isBlank()) {
                        gen.writeStringField("thoughtSignature", tc.thoughtSignature());
                        attachedSigOnFirstCall = true;
                    } else if (injectSkipSig && !attachedSigOnFirstCall) {
                        gen.writeStringField("thoughtSignature", CodecSupport.GEMINI_SKIP_THOUGHT_SIGNATURE);
                        attachedSigOnFirstCall = true;
                    }
                    gen.writeEndObject();
                }
                case CanonicalContentPart.ToolResultPart tr -> {
                    if (tr.toolName() == null || tr.toolName().isBlank()) {
                        throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                                "Cannot translate tool result '" + tr.callId()
                                        + "' to Gemini: matching tool call name not found in conversation history");
                    }
                    writeFunctionResponsePart(gen, tr);
                }
                case CanonicalContentPart.RedactedThinkingPart ignored -> {}
            }
        }
    }

    private void writeImagePart(JsonGenerator gen, CanonicalContentPart.ImagePart img) throws IOException {
        gen.writeStartObject();
        switch (img.source()) {
            case ImageSource.Base64Image b64 -> {
                gen.writeObjectFieldStart("inlineData");
                gen.writeStringField("mimeType", b64.mediaType());
                gen.writeStringField("data", b64.base64Data());
                gen.writeEndObject();
            }
            case ImageSource.UrlImage u -> {
                gen.writeObjectFieldStart("fileData");
                if (u.mediaTypeHint() != null && !u.mediaTypeHint().isBlank()) {
                    gen.writeStringField("mimeType", u.mediaTypeHint());
                }
                gen.writeStringField("fileUri", u.url());
                gen.writeEndObject();
            }
            case ImageSource.FileRefImage ref -> {
                gen.writeObjectFieldStart("fileData");
                if (ref.mediaTypeHint() != null && !ref.mediaTypeHint().isBlank()) {
                    gen.writeStringField("mimeType", ref.mediaTypeHint());
                }
                gen.writeStringField("fileUri", ref.fileRef());
                gen.writeEndObject();
            }
        }
        gen.writeEndObject();
    }

    private void writeFunctionResponsePart(JsonGenerator gen, CanonicalContentPart.ToolResultPart tr) throws IOException {
        List<CanonicalContentPart> children = tr.content();
        String singleText = null;
        StringBuilder textSb = null;
        boolean hasImages = false;
        for (CanonicalContentPart child : children) {
            if (child instanceof CanonicalContentPart.TextPart t) {
                if (singleText == null && textSb == null) {
                    singleText = t.text();
                } else {
                    if (textSb == null) {
                        textSb = new StringBuilder(singleText.length() + t.text().length() + 1);
                        textSb.append(singleText);
                        singleText = null;
                    }
                    if (!textSb.isEmpty()) {
                        textSb.append('\n');
                    }
                    textSb.append(t.text());
                }
            } else if (child instanceof CanonicalContentPart.ImagePart) {
                hasImages = true;
            }
        }
        String textOut = textSb != null ? textSb.toString() : (singleText != null ? singleText : "");

        gen.writeStartObject();
        gen.writeObjectFieldStart("functionResponse");
        if (tr.callId() != null && !tr.callId().isBlank()) {
            gen.writeStringField("id", tr.callId());
        }
        gen.writeStringField("name", tr.toolName());
        gen.writeFieldName("response");
        CodecSupport.writeFunctionResponseObject(gen, mapper, textOut, tr.isError());
        if (hasImages) {
            gen.writeArrayFieldStart("parts");
            for (CanonicalContentPart child : children) {
                if (child instanceof CanonicalContentPart.ImagePart img) {
                    writeImagePart(gen, img);
                }
            }
            gen.writeEndArray();
        }
        gen.writeEndObject();
        gen.writeEndObject();
    }

    private void writeToolsAndConfig(JsonGenerator gen, CanonicalRequest req) throws IOException {
        if (req.tools().isEmpty()) {
            return;
        }
        gen.writeArrayFieldStart("tools");
        gen.writeStartObject();
        gen.writeArrayFieldStart("functionDeclarations");
        for (CanonicalToolDefinition tool : req.tools()) {
            gen.writeStartObject();
            gen.writeStringField("name", tool.name());
            if (tool.description() != null) {
                gen.writeStringField("description", tool.description());
            }
            if (tool.hasAnySchema()) {
                gen.writeFieldName("parametersJsonSchema");
                tool.writeParametersSchemaOrRaw(gen, mapper);
            }
            gen.writeEndObject();
        }
        gen.writeEndArray();
        gen.writeEndObject();
        gen.writeEndArray();

        CanonicalToolChoice tc = req.toolChoice();
        if (tc.mode() != CanonicalToolChoice.ToolChoiceMode.OMITTED) {
            gen.writeObjectFieldStart("toolConfig");
            gen.writeObjectFieldStart("functionCallingConfig");
            switch (tc.mode()) {
                case NONE -> gen.writeStringField("mode", "NONE");
                case AUTO -> gen.writeStringField("mode", "AUTO");
                case REQUIRED -> gen.writeStringField("mode", "ANY");
                case SPECIFIC -> {
                    gen.writeStringField("mode", "ANY");
                    gen.writeArrayFieldStart("allowedFunctionNames");
                    gen.writeString(tc.specificToolName());
                    gen.writeEndArray();
                }
                case OMITTED -> {}
            }
            gen.writeEndObject();
            gen.writeEndObject();
        }
    }

    private void writeGenerationConfigIfNonEmpty(
            JsonGenerator gen,
            CanonicalRequest req,
            UpstreamEncodingContext context
    ) throws IOException {
        CanonicalGenerationConfig g = req.generation();
        CanonicalThinkingConfig thinking = req.thinking();
        boolean hasThinking = thinking.mode() != CanonicalThinkingConfig.ThinkingMode.OMITTED
                || thinking.effort() != CanonicalThinkingConfig.ThinkingEffort.OMITTED;
        boolean hasResponseFmt = req.responseFormat() instanceof CanonicalResponseFormat.JsonObjectFormat
                || req.responseFormat() instanceof CanonicalResponseFormat.JsonSchemaFormat;
        boolean hasAny = (g.maxOutputTokens() != null && g.maxOutputTokens() > 0)
                || g.temperature() != null
                || g.topP() != null
                || g.topK() != null
                || !g.stopSequences().isEmpty()
                || g.seed() != null
                || g.presencePenalty() != null
                || g.frequencyPenalty() != null
                || (g.candidateCount() != null && g.candidateCount() > 1)
                || hasResponseFmt
                || hasThinking;
        if (!hasAny) {
            return;
        }
        gen.writeObjectFieldStart("generationConfig");
        if (g.maxOutputTokens() != null && g.maxOutputTokens() > 0) {
            gen.writeNumberField("maxOutputTokens", g.maxOutputTokens());
        }
        if (g.temperature() != null) {
            gen.writeNumberField("temperature", g.temperature());
        }
        if (g.topP() != null) {
            gen.writeNumberField("topP", g.topP());
        }
        if (g.topK() != null) {
            gen.writeNumberField("topK", g.topK());
        }
        if (!g.stopSequences().isEmpty()) {
            gen.writeArrayFieldStart("stopSequences");
            int limit = Math.min(g.stopSequences().size(), 5);
            for (int i = 0; i < limit; i++) {
                gen.writeString(g.stopSequences().get(i));
            }
            gen.writeEndArray();
        }
        if (g.seed() != null) {
            gen.writeNumberField("seed", g.seed());
        }
        if (g.presencePenalty() != null) {
            gen.writeNumberField("presencePenalty", g.presencePenalty());
        }
        if (g.frequencyPenalty() != null) {
            gen.writeNumberField("frequencyPenalty", g.frequencyPenalty());
        }
        if (g.candidateCount() != null && g.candidateCount() > 1) {
            gen.writeNumberField("candidateCount", g.candidateCount());
        }
        if (req.responseFormat() instanceof CanonicalResponseFormat.JsonObjectFormat) {
            gen.writeStringField("responseMimeType", "application/json");
        } else if (req.responseFormat() instanceof CanonicalResponseFormat.JsonSchemaFormat js) {
            gen.writeStringField("responseMimeType", "application/json");
            if (js.schema() != null) {
                gen.writeFieldName("responseJsonSchema");
                CodecSupport.writeJsonNode(gen, mapper, js.schema());
            }
        }
        if (hasThinking) {
            gen.writeObjectFieldStart("thinkingConfig");
            boolean useLevel = CodecSupport.isGeminiLevelStyle(context.thinkingStyle(), context.upstreamModelName());
            if (useLevel) {
                gen.writeStringField("thinkingLevel", CodecSupport.toGeminiThinkingLevel(thinking));
            } else {
                gen.writeNumberField("thinkingBudget", CodecSupport.toGeminiThinkingBudget(thinking));
            }
            if (Boolean.TRUE.equals(thinking.includeThoughts())) {
                gen.writeBooleanField("includeThoughts", true);
            }
            gen.writeEndObject();
        }
        gen.writeEndObject();
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
        public void feed(byte[] upstreamChunk, int offset, int length, StreamEventSink sink) {
            splitter.push(upstreamChunk, offset, length, ev -> processChunk(ev, sink));
        }

        @Override
        public void complete(StreamEventSink sink) {
            splitter.flushRemainder(ev -> processChunk(ev, sink));
            if (!finished && started) {
                closeActiveBlock(sink);
                finished = true;
                sink.onEvent(new CanonicalStreamEvent.MessageFinish(
                        sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN, null));
            }
        }

        /**
         * Tree-free fast path for single-candidate, single-part text/thought Gemini chunks without
         * {@code usageMetadata}, {@code finishReason}, {@code functionCall}, or {@code promptFeedback}.
         */
        private boolean scanAndEmitTextChunk(byte[] source, int offset, int length, StreamEventSink sink) {
            String respId = null;
            String modelVer = null;
            boolean sawCandidates = false;
            boolean isThought = false;
            String text = null;
            String thoughtSig = null;

            try (JsonParser parser = mapper.getFactory().createParser(source, offset, length)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return false;
                }
                JsonToken tok;
                while ((tok = parser.nextToken()) == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken val = parser.nextToken();
                    switch (field) {
                        case "responseId" -> {
                            if (val == JsonToken.VALUE_STRING && !started) {
                                respId = parser.getText();
                            } else if (val != JsonToken.VALUE_STRING && val != JsonToken.VALUE_NULL) {
                                return false;
                            }
                        }
                        case "modelVersion" -> {
                            if (val == JsonToken.VALUE_STRING && !started) {
                                modelVer = parser.getText();
                            } else if (val != JsonToken.VALUE_STRING && val != JsonToken.VALUE_NULL) {
                                return false;
                            }
                        }
                        case "candidates" -> {
                            if (val != JsonToken.START_ARRAY || sawCandidates) {
                                return false;
                            }
                            sawCandidates = true;
                            if (parser.nextToken() != JsonToken.START_OBJECT) {
                                return false;
                            }
                            JsonToken cTok;
                            while ((cTok = parser.nextToken()) == JsonToken.FIELD_NAME) {
                                String cf = parser.currentName();
                                JsonToken cv = parser.nextToken();
                                switch (cf) {
                                    case "index" -> {
                                        if (!cv.isNumeric()) {
                                            return false;
                                        }
                                    }
                                    case "finishReason" -> {
                                        if (cv != JsonToken.VALUE_NULL) {
                                            return false;
                                        }
                                    }
                                    case "content" -> {
                                        if (cv != JsonToken.START_OBJECT) {
                                            return false;
                                        }
                                        JsonToken ctTok;
                                        while ((ctTok = parser.nextToken()) == JsonToken.FIELD_NAME) {
                                            String ctf = parser.currentName();
                                            JsonToken ctv = parser.nextToken();
                                            if ("role".equals(ctf) && ctv == JsonToken.VALUE_STRING) {
                                                // ignore role
                                            } else if ("parts".equals(ctf) && ctv == JsonToken.START_ARRAY) {
                                                if (parser.nextToken() != JsonToken.START_OBJECT) {
                                                    return false;
                                                }
                                                JsonToken pTok;
                                                while ((pTok = parser.nextToken()) == JsonToken.FIELD_NAME) {
                                                    String pf = parser.currentName();
                                                    JsonToken pv = parser.nextToken();
                                                    switch (pf) {
                                                        case "text" -> {
                                                            if (pv == JsonToken.VALUE_STRING) {
                                                                text = parser.getText();
                                                            } else if (pv != JsonToken.VALUE_NULL) {
                                                                return false;
                                                            }
                                                        }
                                                        case "thought" -> {
                                                            if (pv == JsonToken.VALUE_TRUE) {
                                                                isThought = true;
                                                            } else if (pv != JsonToken.VALUE_FALSE) {
                                                                return false;
                                                            }
                                                        }
                                                        case "thoughtSignature" -> {
                                                            if (pv == JsonToken.VALUE_STRING) {
                                                                thoughtSig = parser.getText();
                                                            } else if (pv != JsonToken.VALUE_NULL) {
                                                                return false;
                                                            }
                                                        }
                                                        default -> {
                                                            return false;
                                                        }
                                                    }
                                                }
                                                if (pTok != JsonToken.END_OBJECT || parser.nextToken() != JsonToken.END_ARRAY) {
                                                    return false;
                                                }
                                            } else {
                                                return false;
                                            }
                                        }
                                        if (ctTok != JsonToken.END_OBJECT) {
                                            return false;
                                        }
                                    }
                                    case "safetyRatings", "groundingMetadata", "avgLogprobs" -> parser.skipChildren();
                                    default -> {
                                        return false;
                                    }
                                }
                            }
                            if (cTok != JsonToken.END_OBJECT || parser.nextToken() != JsonToken.END_ARRAY) {
                                return false;
                            }
                        }
                        default -> {
                            return false;
                        }
                    }
                }
                if (tok != JsonToken.END_OBJECT || !sawCandidates || text == null) {
                    return false;
                }
            } catch (Exception ex) {
                return false;
            }

            if (!started) {
                String id = (respId == null || respId.isBlank()) ? CodecSupport.generateId("gem_") : respId;
                String model = (virtualModel != null && !virtualModel.isBlank())
                        ? virtualModel : (modelVer != null ? modelVer : "");
                sink.onEvent(new CanonicalStreamEvent.MessageStart(id, model, CodecSupport.nowEpochSeconds()));
                started = true;
            }
            if (isThought) {
                int idx = ensureActiveBlock(BlockKind.THINKING, sink);
                if (!text.isEmpty()) {
                    sink.onThinkingDelta(idx, text);
                }
                if (thoughtSig != null && !thoughtSig.isBlank()) {
                    sink.onEvent(new CanonicalStreamEvent.ThinkingSignatureDelta(
                            idx, thoughtSig, ProtocolKind.GEMINI_GENERATE_CONTENT));
                }
            } else {
                int idx = ensureActiveBlock(BlockKind.TEXT, sink);
                if (!text.isEmpty()) {
                    sink.onTextDelta(idx, text);
                }
            }
            return true;
        }

        private void processChunk(SseEventSplitter.SseEvent ev, StreamEventSink sink) {
            if (!ev.hasData() || SseEventSplitter.isDoneMarker(ev)) {
                return;
            }
            if (scanAndEmitTextChunk(ev.dataSource(), ev.dataOffset(), ev.dataLength(), sink)) {
                return;
            }
            try {
                JsonNode root = CodecSupport.readTreeBytes(
                        mapper, ev.dataSource(), ev.dataOffset(), ev.dataLength());
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
                    sink.onEvent(new CanonicalStreamEvent.MessageStart(id, model, CodecSupport.nowEpochSeconds()));
                    started = true;
                }

                parseUsageMetadata(root.get("usageMetadata"))
                        .ifPresent(u -> sink.onEvent(new CanonicalStreamEvent.UsageUpdate(u, true)));

                JsonNode candidates = root.get("candidates");
                if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                    JsonNode cand0 = candidates.get(0);
                    JsonNode parts = cand0.path("content").get("parts");
                    if (parts != null && parts.isArray()) {
                        for (JsonNode part : parts) {
                            String thoughtSig = CodecSupport.asNullableText(part, "thoughtSignature");
                            if (part.path("thought").asBoolean(false)) {
                                int idx = ensureActiveBlock(BlockKind.THINKING, sink);
                                String text = part.path("text").asText("");
                                if (!text.isEmpty()) {
                                    sink.onThinkingDelta(idx, text);
                                }
                                if (thoughtSig != null && !thoughtSig.isBlank()) {
                                    sink.onEvent(new CanonicalStreamEvent.ThinkingSignatureDelta(
                                            idx, thoughtSig, ProtocolKind.GEMINI_GENERATE_CONTENT));
                                }
                            } else if (part.hasNonNull("text")) {
                                int idx = ensureActiveBlock(BlockKind.TEXT, sink);
                                String text = part.get("text").asText();
                                if (!text.isEmpty()) {
                                    sink.onTextDelta(idx, text);
                                }
                            } else if (part.hasNonNull("functionCall")) {
                                closeActiveBlock(sink);
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
                                sink.onEvent(new CanonicalStreamEvent.ToolCallStart(
                                        idx, callId, null, name, thoughtSig, ProtocolKind.GEMINI_GENERATE_CONTENT));
                                sink.onToolCallArgsDelta(idx, callId, serializedArgs);
                                sink.onEvent(new CanonicalStreamEvent.BlockStop(idx));
                            }
                        }
                    }

                    String finishReason = CodecSupport.asNullableText(cand0, "finishReason");
                    if (finishReason != null && !finishReason.isBlank() && !finished) {
                        closeActiveBlock(sink);
                        CanonicalStopReason mapped = switch (finishReason) {
                            case "MAX_TOKENS" -> CanonicalStopReason.MAX_TOKENS;
                            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" ->
                                    CanonicalStopReason.CONTENT_FILTER;
                            case "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" ->
                                    CanonicalStopReason.MALFORMED_TOOL_CALL;
                            default -> sawToolCall ? CanonicalStopReason.TOOL_CALLS : CanonicalStopReason.END_TURN;
                        };
                        finished = true;
                        sink.onEvent(new CanonicalStreamEvent.MessageFinish(mapped, null));
                    }
                } else if (root.path("promptFeedback").hasNonNull("blockReason") && !finished) {
                    closeActiveBlock(sink);
                    finished = true;
                    sink.onEvent(new CanonicalStreamEvent.MessageFinish(CanonicalStopReason.CONTENT_FILTER, null));
                }
            } catch (Exception ignored) {
            }
        }

        private int ensureActiveBlock(BlockKind kind, StreamEventSink sink) {
            if (activeBlockIndex >= 0 && activeBlockKind == kind) {
                return activeBlockIndex;
            }
            closeActiveBlock(sink);
            activeBlockIndex = nextBlockIndex++;
            activeBlockKind = kind;
            sink.onEvent(new CanonicalStreamEvent.BlockStart(activeBlockIndex, kind));
            return activeBlockIndex;
        }

        private void closeActiveBlock(StreamEventSink sink) {
            if (activeBlockIndex >= 0) {
                sink.onEvent(new CanonicalStreamEvent.BlockStop(activeBlockIndex));
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
        private byte[] chunkEnvelopePrefix;
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

        private GeminiStreamEncoder(StreamRequestMetadata inboundMeta) {
            this.model = inboundMeta.model();
            this.altSse = inboundMeta.geminiAltSse();
        }

        @Override
        public String contentType() {
            return altSse ? "text/event-stream" : "application/json";
        }

        @Override
        public void encodeTextDelta(int blockIndex, CharSequence textDelta, ByteSink out) {
            writeFastPartChunk(GEM_TEXT_PART_OPEN, textDelta, out);
        }

        @Override
        public void encodeRefusalDelta(int blockIndex, CharSequence refusalDelta, ByteSink out) {
            writeFastPartChunk(GEM_TEXT_PART_OPEN, refusalDelta, out);
        }

        @Override
        public void encodeThinkingDelta(int blockIndex, CharSequence thinkingDelta, ByteSink out) {
            writeFastPartChunk(GEM_THOUGHT_PART_OPEN, thinkingDelta, out);
        }

        @Override
        public void encodeToolCallArgsDelta(int blockIndex, String callId, CharSequence partialJsonDelta, ByteSink out) {
            PendingToolCall ptc = pendingTools.get(blockIndex);
            if (ptc != null) {
                ptc.argsBuffer.append(partialJsonDelta);
            }
        }

        @Override
        public void encode(CanonicalStreamEvent event, ByteSink out) {
            switch (event) {
                case CanonicalStreamEvent.MessageStart start -> {
                    if (start.responseId() != null && !start.responseId().isBlank()) {
                        this.responseId = start.responseId();
                        this.chunkEnvelopePrefix = null;
                    }
                }
                case CanonicalStreamEvent.TextDelta td -> encodeTextDelta(td.blockIndex(), td.textDelta(), out);
                case CanonicalStreamEvent.RefusalDelta ref -> encodeRefusalDelta(ref.blockIndex(), ref.refusalDelta(), out);
                case CanonicalStreamEvent.ThinkingDelta th -> encodeThinkingDelta(th.blockIndex(), th.thinkingDelta(), out);
                case CanonicalStreamEvent.ToolCallStart tcs -> pendingTools.put(
                        tcs.blockIndex(),
                        new PendingToolCall(tcs.callId(), tcs.name(), tcs.thoughtSignature(), tcs.originProtocol())
                );
                case CanonicalStreamEvent.ToolCallArgsDelta tcad ->
                        encodeToolCallArgsDelta(tcad.blockIndex(), tcad.callId(), tcad.partialJsonDelta(), out);
                case CanonicalStreamEvent.BlockStop stop -> {
                    PendingToolCall ptc = pendingTools.remove(stop.blockIndex());
                    if (ptc != null) {
                        writeWrappedChunk(buildChunk(List.of(buildToolCallPart(ptc)), null, null), out);
                    }
                }
                case CanonicalStreamEvent.UsageUpdate uu -> {
                    latestUsage = uu.cumulative() ? uu.usage() : latestUsage.mergeMax(uu.usage());
                }
                case CanonicalStreamEvent.MessageFinish mf -> emitTerminal(mf.stopReason(), out);
                case CanonicalStreamEvent.StreamError err -> {
                    ObjectNode errObj = mapper.createObjectNode();
                    ObjectNode inner = mapper.createObjectNode();
                    inner.put("code", 500);
                    inner.put("status", "INTERNAL");
                    inner.put("message", err.message() != null ? err.message() : "Upstream stream error");
                    errObj.set("error", inner);
                    writeWrappedChunk(errObj, out);
                    if (!altSse) {
                        out.writeByte(']');
                    }
                    finished = true;
                }
                case CanonicalStreamEvent.BlockStart ignored -> {}
                case CanonicalStreamEvent.ThinkingSignatureDelta ignored -> {}
                case CanonicalStreamEvent.RedactedThinking ignored -> {}
            }
        }

        @Override
        public void finish(ByteSink out) {
            if (finished) {
                return;
            }
            emitTerminal(CanonicalStopReason.END_TURN, out);
        }

        private byte[] envelopePrefix() {
            byte[] cached = chunkEnvelopePrefix;
            if (cached != null) {
                return cached;
            }
            ByteSink.HeapByteSink tmp = new ByteSink.HeapByteSink(160);
            tmp.writeBytes("{\"responseId\":".getBytes(StandardCharsets.US_ASCII));
            JsonUtf8Writer.writeJsonString(responseId, tmp);
            tmp.writeBytes(",\"modelVersion\":".getBytes(StandardCharsets.US_ASCII));
            if (model == null) {
                tmp.writeBytes("null".getBytes(StandardCharsets.US_ASCII));
            } else {
                JsonUtf8Writer.writeJsonString(model, tmp);
            }
            tmp.writeBytes(",\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[".getBytes(StandardCharsets.US_ASCII));
            cached = tmp.toExactByteArray();
            chunkEnvelopePrefix = cached;
            return cached;
        }

        private void writeFastPartChunk(byte[] partOpen, CharSequence text, ByteSink out) {
            if (altSse) {
                emittedAnyChunk = true;
                out.writeBytes(GEM_SSE_DATA_PREFIX, 0, GEM_SSE_DATA_PREFIX.length);
            } else {
                byte[] sep = emittedAnyChunk ? GEM_ARRAY_NEXT : GEM_ARRAY_FIRST;
                emittedAnyChunk = true;
                out.writeBytes(sep, 0, sep.length);
            }
            byte[] prefix = envelopePrefix();
            out.writeBytes(prefix, 0, prefix.length);
            out.writeBytes(partOpen, 0, partOpen.length);
            JsonUtf8Writer.writeJsonString(text, out);
            out.writeBytes(GEM_CHUNK_SUFFIX, 0, GEM_CHUNK_SUFFIX.length);
            if (altSse) {
                out.writeBytes(GEM_SSE_TAIL, 0, GEM_SSE_TAIL.length);
            }
        }

        private void emitTerminal(CanonicalStopReason reason, ByteSink out) {
            if (finished) {
                return;
            }
            List<ObjectNode> finalParts = new ArrayList<>();
            for (PendingToolCall ptc : pendingTools.values()) {
                finalParts.add(buildToolCallPart(ptc));
            }
            pendingTools.clear();
            writeWrappedChunk(buildChunk(finalParts, encodeFinishReason(reason), latestUsage), out);
            if (!altSse) {
                out.writeBytes(GEM_ARRAY_END, 0, GEM_ARRAY_END.length);
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

        private void writeWrappedChunk(ObjectNode chunk, ByteSink out) {
            byte[] json = CodecSupport.writeJsonBytes(mapper, chunk);
            if (altSse) {
                emittedAnyChunk = true;
                SseEventSplitter.writeDataFrame(json, out);
                return;
            }
            byte[] sep = emittedAnyChunk ? GEM_ARRAY_NEXT : GEM_ARRAY_FIRST;
            emittedAnyChunk = true;
            out.writeBytes(sep, 0, sep.length);
            out.writeBytes(json, 0, json.length);
        }
    }


}
