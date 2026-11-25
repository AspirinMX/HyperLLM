package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.codec.AnthropicMessagesCodec;
import app.pagefault.hyperllm.protocol.codec.CanonicalRequestNormalizer;
import app.pagefault.hyperllm.protocol.codec.GeminiGenerateContentCodec;
import app.pagefault.hyperllm.protocol.codec.OpenAiChatCodec;
import app.pagefault.hyperllm.protocol.codec.OpenAiResponsesCodec;
import app.pagefault.hyperllm.protocol.codec.ProtocolCodec;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalResponse;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamPayload;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamRequest;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * Single-path Canonical IR protocol engine for the four supported text-generation protocols.
 *
 * <p>Every request follows exactly one route — <b>downstream wire body &rarr; Canonical IR &rarr; upstream wire
 * body</b> — regardless of whether the inbound and outbound protocols match. There is no homomorphic
 * passthrough and no byte-range patching of the client payload: the bytes sent upstream are always
 * produced by {@link ProtocolCodec#encodeRequest} from a request graph that this process decoded and
 * validated, so routing, billing, and auditing all observe the same authoritative parse as the upstream.
 *
 * <p>Responses follow the mirror rule: upstream wire events &rarr; Canonical IR &rarr; downstream wire events,
 * including the same-protocol case.
 */
public final class ProtocolTranslator {

    public record TranslatedResponse(byte[] clientBody, TokenUsage usage) {
    }

    private final Map<ProtocolKind, ProtocolCodec> codecs = new EnumMap<>(ProtocolKind.class);
    private final TokenEstimator tokenEstimator;
    private final ProtocolErrorFormatter errorFormatter;

    public ProtocolTranslator(ObjectMapper objectMapper) {
        this(objectMapper, new TokenEstimator(), new ProtocolErrorFormatter(objectMapper));
    }

    public ProtocolTranslator(
            ObjectMapper objectMapper,
            TokenEstimator tokenEstimator,
            ProtocolErrorFormatter errorFormatter
    ) {
        this.tokenEstimator = tokenEstimator;
        this.errorFormatter = errorFormatter;
        register(new OpenAiChatCodec(objectMapper));
        register(new OpenAiResponsesCodec(objectMapper));
        register(new AnthropicMessagesCodec(objectMapper));
        register(new GeminiGenerateContentCodec(objectMapper));
    }

    private void register(ProtocolCodec codec) {
        codecs.put(codec.kind(), codec);
    }

    public ProtocolCodec codecFor(ProtocolKind kind) {
        ProtocolCodec codec = codecs.get(kind);
        if (codec == null) {
            throw new IllegalArgumentException("Unsupported protocol kind: " + kind);
        }
        return codec;
    }

    public TokenEstimator tokenEstimator() {
        return tokenEstimator;
    }

    /**
     * Decodes the downstream body into the Canonical IR. This is the single authoritative parse of the
     * request: it is strict (rejects malformed JSON, duplicate critical fields, and unsupported shapes)
     * and everything downstream — routing, admission, billing, and the outbound body — is derived from it.
     */
    public CanonicalRequest parseRequest(
            ProtocolKind inboundProtocol,
            byte[] rawBody,
            InboundRequestContext context
    ) {
        return codecFor(inboundProtocol).decodeRequest(
                rawBody, context != null ? context : InboundRequestContext.empty());
    }

    /**
     * Canonical IR &rarr; outbound upstream body. Always re-encoded: the client's bytes are never forwarded.
     */
    public PreparedUpstreamPayload prepareUpstreamPayload(
            CanonicalRequest decoded,
            ProtocolKind outboundProtocol,
            UpstreamEncodingContext context
    ) {
        ProtocolCodec outboundCodec = codecFor(outboundProtocol);
        CanonicalRequest normalized = CanonicalRequestNormalizer.normalizeForTranslation(
                decoded, outboundProtocol, context);
        return outboundCodec.encodeRequestPayload(normalized, context);
    }

    /**
     * Canonical IR &rarr; outbound upstream request, retaining the normalized request graph for callers that
     * need it (adapters and tests).
     */
    public PreparedUpstreamRequest prepareUpstreamRequest(
            byte[] rawBody,
            CanonicalRequest decoded,
            ProtocolKind outboundProtocol,
            UpstreamEncodingContext context
    ) {
        ProtocolCodec outboundCodec = codecFor(outboundProtocol);
        CanonicalRequest normalized = CanonicalRequestNormalizer.normalizeForTranslation(
                decoded, outboundProtocol, context);
        byte[] translatedBody = outboundCodec.encodeRequest(normalized, context);
        return new PreparedUpstreamRequest(normalized, outboundProtocol, translatedBody);
    }

    /**
     * Upstream non-streaming body &rarr; Canonical IR &rarr; downstream body, for every protocol combination.
     */
    public TranslatedResponse translateNonStreamingResponse(
            StreamRequestMetadata inboundMeta,
            ProtocolKind outboundProtocol,
            byte[] upstreamBody
    ) {
        ProtocolCodec outboundCodec = codecFor(outboundProtocol);
        CanonicalResponse canonicalResponse = outboundCodec.decodeResponse(upstreamBody, inboundMeta.model());
        TokenUsage usage = canonicalResponse.usage();
        if (usage == null || (usage.totalInputTokens() == 0 && usage.totalOutputTokens() == 0)) {
            usage = fallbackUsage(inboundMeta, upstreamBody);
            canonicalResponse = new CanonicalResponse(
                    canonicalResponse.id(),
                    canonicalResponse.virtualModel(),
                    canonicalResponse.createdEpochSeconds(),
                    canonicalResponse.content(),
                    canonicalResponse.stopReason(),
                    canonicalResponse.stopSequence(),
                    usage,
                    canonicalResponse.upstreamProtocol()
            );
        }
        byte[] clientBody = codecFor(inboundMeta.inboundProtocol()).encodeResponse(canonicalResponse);
        return new TranslatedResponse(clientBody, usage);
    }

    /**
     * Backwards-compatible overload accepting the full {@link CanonicalRequest}.
     */
    public TranslatedResponse translateNonStreamingResponse(
            CanonicalRequest decoded,
            ProtocolKind outboundProtocol,
            byte[] upstreamBody
    ) {
        int estPrompt = tokenEstimator.estimatePromptTokens(decoded);
        return translateNonStreamingResponse(
                StreamRequestMetadata.fromCanonical(decoded, estPrompt),
                outboundProtocol,
                upstreamBody
        );
    }

    /**
     * Creates a stateful streaming bridge: upstream wire frames &rarr; Canonical IR &rarr; downstream frames,
     * for every protocol combination (including same-protocol, which is a 1:1 IR round trip).
     */
    public StreamBridge newStreamBridge(StreamRequestMetadata inboundMeta, ProtocolKind outboundProtocol) {
        ProtocolCodec outboundCodec = codecFor(outboundProtocol);
        ProtocolCodec inboundCodec = codecFor(inboundMeta.inboundProtocol());
        return new TranslatingStreamBridge(
                inboundMeta,
                outboundCodec.newStreamDecoder(inboundMeta.model()),
                inboundCodec.newStreamEncoder(inboundMeta),
                tokenEstimator
        );
    }

    public StreamBridge newStreamBridge(CanonicalRequest decoded, ProtocolKind outboundProtocol) {
        int estPrompt = tokenEstimator.estimatePromptTokens(decoded);
        return newStreamBridge(StreamRequestMetadata.fromCanonical(decoded, estPrompt), outboundProtocol);
    }

    public byte[] translateUpstreamError(
            ProtocolKind inboundProtocol,
            ProtocolKind outboundProtocol,
            int httpStatus,
            byte[] upstreamBody
    ) {
        return errorFormatter.translateUpstreamError(inboundProtocol, outboundProtocol, httpStatus, upstreamBody);
    }

    private TokenUsage fallbackUsage(StreamRequestMetadata meta, byte[] responseBody) {
        int estPrompt = meta.estimatedPromptTokens();
        String bodyText = (responseBody == null || responseBody.length == 0)
                ? ""
                : new String(responseBody, StandardCharsets.UTF_8);
        int estCompletion = tokenEstimator.countText(meta.model(), bodyText);
        return new TokenUsage(estPrompt, 0, 0, estCompletion, 0, true);
    }
}
