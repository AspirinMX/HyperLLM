package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.CanonicalContentPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalTurn;
import app.pagefault.hyperllm.protocol.ir.ImageSource;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UnsupportedFeatureFlag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes a {@link CanonicalRequest} before cross-protocol encoding:
 * <ul>
 *   <li>Fails fast with HTTP 400 on vendor-locked features or cross-vendor file references.</li>
 *   <li>Synthesizes deterministic tool call IDs when omitted (e.g. legacy Gemini histories).</li>
 *   <li>Backfills missing {@code toolName} on {@link CanonicalContentPart.ToolResultPart} from
 *       preceding {@link CanonicalContentPart.ToolCallPart}s so Gemini {@code functionResponse.name}
 *       is always populated.</li>
 * </ul>
 */
public final class CanonicalRequestNormalizer {

    private CanonicalRequestNormalizer() {
    }

    public static CanonicalRequest normalizeForTranslation(
            CanonicalRequest request,
            ProtocolKind outboundProtocol,
            TranslationPolicy policy
    ) {
        if (request.inboundProtocol() == outboundProtocol) {
            return request;
        }
        TranslationPolicy effectivePolicy = policy == null ? TranslationPolicy.BEST_EFFORT : policy;

        if (!request.vendorOnlyFeatures().isEmpty()) {
            UnsupportedFeatureFlag first = request.vendorOnlyFeatures().getFirst();
            throw new HyperLlmException(
                    ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                    "Feature '" + first.featureName() + "' cannot be translated from "
                            + request.inboundProtocol() + " to " + outboundProtocol
                            + (first.detail() != null && !first.detail().isBlank() ? ": " + first.detail() : "")
            );
        }

        if (request.cache().geminiCachedContent() != null && !request.cache().geminiCachedContent().isBlank()) {
            throw new HyperLlmException(
                    ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                    "Gemini cachedContent reference cannot be translated to " + outboundProtocol
            );
        }

        if (request.generation().candidateCount() != null && request.generation().candidateCount() > 1
                && (outboundProtocol == ProtocolKind.ANTHROPIC_MESSAGES || outboundProtocol == ProtocolKind.OPENAI_RESPONSES)) {
            throw new HyperLlmException(
                    ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                    "candidateCount/n > 1 is not supported by " + outboundProtocol
            );
        }

        if (effectivePolicy == TranslationPolicy.STRICT) {
            validateStrictParameterCompatibility(request, outboundProtocol);
        }

        Map<String, String> callIdToName = new HashMap<>();
        Map<String, Deque<String>> pendingIdsByName = new HashMap<>();

        List<CanonicalTurn> normalizedTurns = new ArrayList<>(request.turns().size());
        for (int turnIdx = 0; turnIdx < request.turns().size(); turnIdx++) {
            CanonicalTurn turn = request.turns().get(turnIdx);
            List<CanonicalContentPart> normalizedParts = new ArrayList<>(turn.parts().size());
            for (int partIdx = 0; partIdx < turn.parts().size(); partIdx++) {
                CanonicalContentPart part = turn.parts().get(partIdx);
                normalizedParts.add(normalizePart(
                        part, turnIdx, partIdx, outboundProtocol, callIdToName, pendingIdsByName));
            }
            normalizedTurns.add(new CanonicalTurn(turn.role(), normalizedParts));
        }

        return new CanonicalRequest(
                request.inboundProtocol(),
                request.model(),
                request.stream(),
                request.systemInstructions(),
                normalizedTurns,
                request.tools(),
                request.toolChoice(),
                request.generation(),
                request.responseFormat(),
                request.thinking(),
                request.cache(),
                request.options(),
                request.vendorOnlyFeatures()
        );
    }

    private static CanonicalContentPart normalizePart(
            CanonicalContentPart part,
            int turnIdx,
            int partIdx,
            ProtocolKind outboundProtocol,
            Map<String, String> callIdToName,
            Map<String, Deque<String>> pendingIdsByName
    ) {
        if (part instanceof CanonicalContentPart.ImagePart img) {
            validateImageSource(img.source(), outboundProtocol);
            return img;
        }
        if (part instanceof CanonicalContentPart.ToolCallPart tc) {
            String callId = (tc.callId() != null && !tc.callId().isBlank())
                    ? tc.callId()
                    : "call_ir_" + turnIdx + "_" + partIdx;
            if (tc.name() != null && !tc.name().isBlank()) {
                callIdToName.put(callId, tc.name());
                pendingIdsByName.computeIfAbsent(tc.name(), k -> new ArrayDeque<>()).addLast(callId);
            }
            if (callId.equals(tc.callId())) {
                return tc;
            }
            return new CanonicalContentPart.ToolCallPart(
                    callId, tc.itemId(), tc.name(), tc.argumentsJson(),
                    tc.rawArgumentsJson(), tc.thoughtSignature(), tc.originProtocol()
            );
        }
        if (part instanceof CanonicalContentPart.ToolResultPart tr) {
            for (CanonicalContentPart child : tr.content()) {
                if (child instanceof CanonicalContentPart.ImagePart childImg) {
                    validateImageSource(childImg.source(), outboundProtocol);
                }
            }
            String callId = tr.callId();
            String toolName = tr.toolName();
            if ((toolName == null || toolName.isBlank()) && callId != null) {
                toolName = callIdToName.get(callId);
            }
            if ((callId == null || callId.isBlank()) && toolName != null) {
                Deque<String> queue = pendingIdsByName.get(toolName);
                if (queue != null && !queue.isEmpty()) {
                    callId = queue.pollFirst();
                } else {
                    callId = "call_ir_" + turnIdx + "_" + partIdx;
                }
            } else if (callId != null && toolName != null) {
                Deque<String> queue = pendingIdsByName.get(toolName);
                if (queue != null) {
                    queue.remove(callId);
                }
            }
            return new CanonicalContentPart.ToolResultPart(
                    callId, toolName, tr.content(), tr.isError(), tr.cacheControl()
            );
        }
        return part;
    }

    private static void validateImageSource(ImageSource source, ProtocolKind outboundProtocol) {
        if (source instanceof ImageSource.FileRefImage ref && ref.ownerProtocol() != outboundProtocol) {
            throw new HyperLlmException(
                    ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                    "Vendor-scoped file reference from " + ref.ownerProtocol()
                            + " cannot be translated to " + outboundProtocol
            );
        }
    }

    private static void validateStrictParameterCompatibility(CanonicalRequest request, ProtocolKind outbound) {
        var gen = request.generation();
        if (outbound == ProtocolKind.ANTHROPIC_MESSAGES) {
            if (gen.temperature() != null && (gen.temperature() < 0.0 || gen.temperature() > 1.0)) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "Temperature " + gen.temperature() + " exceeds Anthropic [0.0, 1.0] range in STRICT mode");
            }
            if (gen.seed() != null || gen.frequencyPenalty() != null || gen.presencePenalty() != null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "seed/frequency_penalty/presence_penalty are not supported by Anthropic in STRICT mode");
            }
            if (request.responseFormat() instanceof app.pagefault.hyperllm.protocol.ir.CanonicalResponseFormat.JsonObjectFormat) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "Schemaless json_object format is not natively supported by Anthropic in STRICT mode");
            }
        } else if (outbound == ProtocolKind.OPENAI_RESPONSES) {
            if (!gen.stopSequences().isEmpty()) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "stop sequences are not supported by OpenAI Responses API in STRICT mode");
            }
            if (gen.frequencyPenalty() != null || gen.presencePenalty() != null || gen.topK() != null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "top_k/penalties are not supported by OpenAI Responses API in STRICT mode");
            }
        } else if (outbound == ProtocolKind.OPENAI_CHAT) {
            if (gen.topK() != null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "top_k is not supported by OpenAI Chat Completions in STRICT mode");
            }
        }
    }
}
