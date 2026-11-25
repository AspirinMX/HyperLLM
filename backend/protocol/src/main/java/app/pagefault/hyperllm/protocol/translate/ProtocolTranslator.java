package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.codec.AnthropicMessagesCodec;
import app.pagefault.hyperllm.protocol.codec.CanonicalRequestNormalizer;
import app.pagefault.hyperllm.protocol.codec.GeminiGenerateContentCodec;
import app.pagefault.hyperllm.protocol.codec.OpenAiChatCodec;
import app.pagefault.hyperllm.protocol.codec.OpenAiResponsesCodec;
import app.pagefault.hyperllm.protocol.codec.ProtocolCodec;
import app.pagefault.hyperllm.protocol.fast.FastRequestProbe;
import app.pagefault.hyperllm.protocol.fast.PassthroughBytePatcher;
import app.pagefault.hyperllm.protocol.fast.StrictJsonRequestScanner;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dual-path protocol engine supporting all 16 combinations of the four supported text-generation protocols:
 * <ul>
 *   <li>4 homomorphic combinations use strict single-pass UTF-8 scanning + zero-copy byte-range patching
 *       (no full JSON DOM parsing or re-serialization on 100K+ token requests).</li>
 *   <li>12 heterogeneous combinations lazily decode into the Canonical IR and streaming state machines.</li>
 * </ul>
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
     * Single-pass strict RFC 8259 UTF-8 probe extracting routing scalars, Base64-aware prompt token
     * estimation, and exact byte spans for zero-copy passthrough patching.
     */
    public FastRequestProbe probeRequest(
            ProtocolKind inboundProtocol,
            byte[] rawBody,
            InboundRequestContext context
    ) {
        codecFor(inboundProtocol); // validate supported protocol
        return StrictJsonRequestScanner.scan(inboundProtocol, rawBody, context);
    }

    public CanonicalRequest parseRequest(
            ProtocolKind inboundProtocol,
            byte[] rawBody,
            InboundRequestContext context
    ) {
        return codecFor(inboundProtocol).decodeRequest(rawBody, context != null ? context : InboundRequestContext.empty());
    }

    /**
     * Zero-copy fast-path / lazy-IR slow-path upstream payload builder used by the gateway hot path.
     */
    public PreparedUpstreamPayload prepareUpstreamPayload(
            byte[] rawBody,
            FastRequestProbe probe,
            InboundRequestContext inboundContext,
            ProtocolKind outboundProtocol,
            UpstreamEncodingContext context,
            AtomicReference<CanonicalRequest> lazyCanonical
    ) {
        if (probe.inboundProtocol() == outboundProtocol && !probe.requiresSlowPath()) {
            return PassthroughBytePatcher.patch(rawBody, probe, context);
        }
        CanonicalRequest decoded = lazyCanonical != null ? lazyCanonical.get() : null;
        if (decoded == null) {
            decoded = parseRequest(probe.inboundProtocol(), rawBody, inboundContext);
            if (lazyCanonical != null) {
                lazyCanonical.set(decoded);
            }
        }
        ProtocolCodec outboundCodec = codecFor(outboundProtocol);
        if (decoded.inboundProtocol() == outboundProtocol) {
            byte[] rewritten = outboundCodec.preparePassthroughRequest(rawBody, decoded, context);
            return PreparedUpstreamPayload.ofSingleArray(outboundProtocol, rewritten, true);
        }
        CanonicalRequest normalized = CanonicalRequestNormalizer.normalizeForTranslation(
                decoded, outboundProtocol, context);
        byte[] translatedBody = outboundCodec.encodeRequest(normalized, context);
        return PreparedUpstreamPayload.ofSingleArray(outboundProtocol, translatedBody, false);
    }

    /**
     * Prepares an upstream request body using either zero-overhead passthrough (when
     * {@code decoded.inboundProtocol() == outboundProtocol}) or Canonical IR normalization and encoding.
     */
    public PreparedUpstreamRequest prepareUpstreamRequest(
            byte[] rawBody,
            CanonicalRequest decoded,
            ProtocolKind outboundProtocol,
            UpstreamEncodingContext context
    ) {
        ProtocolCodec outboundCodec = codecFor(outboundProtocol);
        if (decoded.inboundProtocol() == outboundProtocol) {
            byte[] passthroughBody = outboundCodec.preparePassthroughRequest(rawBody, decoded, context);
            return new PreparedUpstreamRequest(decoded, outboundProtocol, passthroughBody, true);
        }
        CanonicalRequest normalized = CanonicalRequestNormalizer.normalizeForTranslation(
                decoded, outboundProtocol, context);
        byte[] translatedBody = outboundCodec.encodeRequest(normalized, context);
        return new PreparedUpstreamRequest(normalized, outboundProtocol, translatedBody, false);
    }

    /**
     * Extracts disjoint usage and (when cross-protocol) translates a 2xx non-streaming upstream
     * response into the caller's native wire protocol using only lightweight {@link StreamRequestMetadata}.
     */
    public TranslatedResponse translateNonStreamingResponse(
            StreamRequestMetadata inboundMeta,
            ProtocolKind outboundProtocol,
            byte[] upstreamBody
    ) {
        ProtocolCodec outboundCodec = codecFor(outboundProtocol);
        if (inboundMeta.inboundProtocol() == outboundProtocol) {
            TokenUsage usage = outboundCodec.extractNonStreamingUsage(upstreamBody)
                    .orElseGet(() -> fallbackUsage(inboundMeta, upstreamBody));
            return new TranslatedResponse(upstreamBody, usage);
        }

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
     * Backwards-compatible overload accepting full {@link CanonicalRequest}.
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
     * Creates a stateful streaming bridge holding only lightweight {@link StreamRequestMetadata}.
     */
    public StreamBridge newStreamBridge(StreamRequestMetadata inboundMeta, ProtocolKind outboundProtocol) {
        ProtocolCodec outboundCodec = codecFor(outboundProtocol);
        if (inboundMeta.inboundProtocol() == outboundProtocol) {
            return outboundCodec.newPassthroughStreamBridge(inboundMeta, tokenEstimator);
        }
        ProtocolCodec inboundCodec = codecFor(inboundMeta.inboundProtocol());
        return new TranslatingStreamBridge(
                inboundMeta,
                outboundCodec.newStreamDecoder(inboundMeta.model()),
                inboundCodec.newStreamEncoder(inboundMeta),
                tokenEstimator
        );
    }

    /**
     * Creates a stateful streaming bridge for either homomorphic passthrough or cross-protocol SSE translation.
     */
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
