package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.CanonicalContentPart;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalResponseFormat;
import app.pagefault.hyperllm.protocol.ir.CanonicalRole;
import app.pagefault.hyperllm.protocol.ir.CanonicalToolChoice;
import app.pagefault.hyperllm.protocol.ir.CanonicalToolDefinition;
import app.pagefault.hyperllm.protocol.ir.CanonicalTurn;
import app.pagefault.hyperllm.protocol.ir.ImageSource;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.ProviderCapabilities;
import app.pagefault.hyperllm.protocol.ir.RequestMetrics;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UnsupportedFeatureFlag;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.ir.ValidatedJsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capability-driven cross-protocol normalization pipeline optimized with copy-on-write fast paths
 * for large multi-turn agent {@code messages} arrays:
 * <ol>
 *   <li>Enforces feature compatibility ({@link UnsupportedFeatureFlag.Severity#HARD_INCOMPATIBLE}
 *       always fails fast; {@link UnsupportedFeatureFlag.Severity#DEGRADABLE} drops cleanly in
 *       {@code BEST_EFFORT} and rejects in {@code STRICT}).</li>
 *   <li>Strips cross-vendor opaque thinking signatures/redacted blocks and target-disallowed empty text parts.</li>
 *   <li>Bijectively sanitizes tool call IDs to target {@link ProviderCapabilities} charset/length limits
 *       while backfilling missing {@code callId <-> toolName} bindings (lazy map initialization).</li>
 *   <li>Repairs interrupted agent tool-call turns in {@code BEST_EFFORT} using zero-allocation fast-skip
 *       on clean histories.</li>
 *   <li>Normalizes tool parameter schemas (copy-on-write) and removes dangling {@code tool_choice} directives.</li>
 * </ol>
 */
public final class CanonicalRequestNormalizer {

    private CanonicalRequestNormalizer() {
    }

    public static CanonicalRequest normalizeForTranslation(
            CanonicalRequest request,
            ProtocolKind outboundProtocol,
            TranslationPolicy policy
    ) {
        return normalizeForTranslation(
                request,
                outboundProtocol,
                new UpstreamEncodingContext(null, null, policy, null, null)
        );
    }

    public static CanonicalRequest normalizeForTranslation(
            CanonicalRequest request,
            ProtocolKind outboundProtocol,
            UpstreamEncodingContext context
    ) {
        if (request.inboundProtocol() == outboundProtocol) {
            return request;
        }
        TranslationPolicy effectivePolicy = (context != null && context.policy() != null)
                ? context.policy()
                : TranslationPolicy.BEST_EFFORT;
        ProviderCapabilities caps = context != null
                ? context.capabilitiesFor(outboundProtocol)
                : ProviderCapabilities.resolve(outboundProtocol, null, false, null, null);

        // 1. Feature compatibility gate
        for (UnsupportedFeatureFlag flag : request.vendorOnlyFeatures()) {
            if (flag.isHardIncompatible() || effectivePolicy == TranslationPolicy.STRICT) {
                throw new HyperLlmException(
                        ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                        "Feature '" + flag.featureName() + "' cannot be translated from "
                                + request.inboundProtocol() + " to " + outboundProtocol
                                + (flag.detail() != null && !flag.detail().isBlank() ? ": " + flag.detail() : "")
                );
            }
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

        // 2. Copy-on-write turn normalization with lazy tool-call state allocation
        ToolTrackingState toolState = null;
        List<CanonicalTurn> originalTurns = request.turns();
        List<CanonicalTurn> normalizedTurns = null;
        boolean sawAnyToolCall = false;

        for (int turnIdx = 0; turnIdx < originalTurns.size(); turnIdx++) {
            CanonicalTurn turn = originalTurns.get(turnIdx);
            List<CanonicalContentPart> origParts = turn.parts();
            List<CanonicalContentPart> modifiedParts = null;

            for (int partIdx = 0; partIdx < origParts.size(); partIdx++) {
                CanonicalContentPart part = origParts.get(partIdx);
                if (part instanceof CanonicalContentPart.ToolCallPart) {
                    sawAnyToolCall = true;
                    if (toolState == null) {
                        toolState = new ToolTrackingState();
                    }
                } else if (part instanceof CanonicalContentPart.ToolResultPart && toolState == null) {
                    toolState = new ToolTrackingState();
                }

                CanonicalContentPart normalized = normalizePart(
                        part, turnIdx, partIdx, outboundProtocol, caps, toolState);
                if (normalized != part) {
                    if (modifiedParts == null) {
                        modifiedParts = new ArrayList<>(origParts.size());
                        for (int k = 0; k < partIdx; k++) {
                            modifiedParts.add(origParts.get(k));
                        }
                    }
                    if (normalized != null) {
                        modifiedParts.add(normalized);
                    }
                } else if (modifiedParts != null) {
                    modifiedParts.add(normalized);
                }
            }

            if (modifiedParts != null) {
                if (normalizedTurns == null) {
                    normalizedTurns = new ArrayList<>(originalTurns.size());
                    for (int k = 0; k < turnIdx; k++) {
                        normalizedTurns.add(originalTurns.get(k));
                    }
                }
                if (!modifiedParts.isEmpty()) {
                    normalizedTurns.add(CanonicalTurn.ofOwned(turn.role(), modifiedParts));
                }
            } else if (origParts.isEmpty()) {
                if (normalizedTurns == null) {
                    normalizedTurns = new ArrayList<>(originalTurns.size());
                    for (int k = 0; k < turnIdx; k++) {
                        normalizedTurns.add(originalTurns.get(k));
                    }
                }
            } else if (normalizedTurns != null) {
                normalizedTurns.add(turn);
            }
        }

        List<CanonicalTurn> effectiveTurns = normalizedTurns != null ? normalizedTurns : originalTurns;

        // 3. Interrupted agent tool-call repair (BEST_EFFORT, skipped completely if no tool calls exist)
        if (effectivePolicy == TranslationPolicy.BEST_EFFORT && sawAnyToolCall) {
            effectiveTurns = repairInterruptedToolCalls(effectiveTurns);
        }

        // 4. Copy-on-write tool schema defaults & tool_choice coupling guard
        List<CanonicalToolDefinition> normalizedTools = normalizeTools(request.tools());
        CanonicalToolChoice normalizedToolChoice = normalizeToolChoice(
                request.toolChoice(), normalizedTools, effectivePolicy);

        if (effectiveTurns == originalTurns
                && normalizedTools == request.tools()
                && normalizedToolChoice == request.toolChoice()) {
            return stripVendorRaw(request);
        }

        return CanonicalRequest.ofOwned(
                request.inboundProtocol(),
                request.model(),
                request.stream(),
                request.systemInstructions(),
                effectiveTurns,
                normalizedTools,
                normalizedToolChoice,
                request.generation(),
                request.responseFormat(),
                request.thinking(),
                request.cache(),
                request.options().withoutVendorRaw(),
                request.vendorOnlyFeatures(),
                RequestMetrics.UNCOMPUTED
        );
    }

    /**
     * Drops vendor-only members retained for a same-protocol re-encode. Cross-protocol translation must not
     * forward them: hard-incompatible members already fail the feature gate, and degradable ones (built-in
     * tools) are dropped by policy.
     */
    private static CanonicalRequest stripVendorRaw(CanonicalRequest request) {
        if (!request.options().hasVendorRaw()) {
            return request;
        }
        return CanonicalRequest.ofOwned(
                request.inboundProtocol(),
                request.model(),
                request.stream(),
                request.systemInstructions(),
                request.turns(),
                request.tools(),
                request.toolChoice(),
                request.generation(),
                request.responseFormat(),
                request.thinking(),
                request.cache(),
                request.options().withoutVendorRaw(),
                request.vendorOnlyFeatures(),
                request.metrics()
        );
    }

    private static final class ToolTrackingState {
        final Map<String, String> originalToSanitizedId = new HashMap<>();
        final Set<String> usedSanitizedIds = new HashSet<>();
        final Map<String, String> callIdToName = new HashMap<>();
        final Map<String, Deque<String>> pendingIdsByName = new HashMap<>();
    }

    private static CanonicalContentPart normalizePart(
            CanonicalContentPart part,
            int turnIdx,
            int partIdx,
            ProtocolKind outboundProtocol,
            ProviderCapabilities caps,
            ToolTrackingState toolState
    ) {
        if (part instanceof CanonicalContentPart.TextPart t) {
            if (!caps.allowsEmptyTextBlocks() && t.isEmpty()) {
                return null;
            }
            return t;
        }
        if (part instanceof CanonicalContentPart.ImagePart img) {
            validateImageSource(img.source(), outboundProtocol);
            return img;
        }
        if (part instanceof CanonicalContentPart.ThinkingPart th) {
            if (!CodecSupport.isSameOrigin(th.originProtocol(), outboundProtocol)) {
                if (th.isEmpty()) {
                    return null;
                }
                if (th.signature() == null && th.encryptedContent() == null) {
                    return th;
                }
                return CanonicalContentPart.ThinkingPart.ofUtf8(
                        th.utf8Text(), null, null, th.originProtocol(), th.wireField());
            }
            return th;
        }
        if (part instanceof CanonicalContentPart.RedactedThinkingPart rt) {
            return CodecSupport.isSameOrigin(rt.originProtocol(), outboundProtocol) ? rt : null;
        }
        if (part instanceof CanonicalContentPart.ToolCallPart tc) {
            String rawCallId = (tc.callId() != null && !tc.callId().isBlank())
                    ? tc.callId()
                    : "call_ir_" + turnIdx + "_" + partIdx;
            String sanitizedId = sanitizeToolCallId(
                    rawCallId, caps, toolState.originalToSanitizedId, toolState.usedSanitizedIds);
            if (tc.name() != null && !tc.name().isBlank()) {
                toolState.callIdToName.put(sanitizedId, tc.name());
                toolState.pendingIdsByName.computeIfAbsent(tc.name(), k -> new ArrayDeque<>()).addLast(sanitizedId);
            }
            boolean keepThoughtSig = tc.thoughtSignature() != null
                    && (outboundProtocol == ProtocolKind.GEMINI_GENERATE_CONTENT
                    || outboundProtocol == ProtocolKind.OPENAI_CHAT);
            String sig = keepThoughtSig ? tc.thoughtSignature() : null;
            if (sanitizedId.equals(tc.callId()) && java.util.Objects.equals(sig, tc.thoughtSignature())) {
                return tc;
            }
            return tc.withSanitizedCallIdAndSignature(sanitizedId, sig);
        }
        if (part instanceof CanonicalContentPart.ToolResultPart tr) {
            List<CanonicalContentPart> origContent = tr.content();
            List<CanonicalContentPart> cleanedContent = null;
            for (int i = 0; i < origContent.size(); i++) {
                CanonicalContentPart child = origContent.get(i);
                boolean keep = true;
                if (child instanceof CanonicalContentPart.ImagePart childImg) {
                    validateImageSource(childImg.source(), outboundProtocol);
                } else if (child instanceof CanonicalContentPart.TextPart t) {
                    keep = caps.allowsEmptyTextBlocks() || !t.isEmpty();
                }
                if (!keep) {
                    if (cleanedContent == null) {
                        cleanedContent = new ArrayList<>(origContent.size());
                        for (int k = 0; k < i; k++) {
                            cleanedContent.add(origContent.get(k));
                        }
                    }
                } else if (cleanedContent != null) {
                    cleanedContent.add(child);
                }
            }
            String rawCallId = tr.callId();
            String sanitizedCallId = (rawCallId != null && !rawCallId.isBlank())
                    ? sanitizeToolCallId(rawCallId, caps, toolState.originalToSanitizedId, toolState.usedSanitizedIds)
                    : null;
            String toolName = tr.toolName();
            if ((toolName == null || toolName.isBlank()) && sanitizedCallId != null) {
                toolName = toolState.callIdToName.get(sanitizedCallId);
            }
            if ((sanitizedCallId == null || sanitizedCallId.isBlank()) && toolName != null) {
                Deque<String> queue = toolState.pendingIdsByName.get(toolName);
                if (queue != null && !queue.isEmpty()) {
                    sanitizedCallId = queue.pollFirst();
                } else {
                    sanitizedCallId = sanitizeToolCallId(
                            "call_ir_" + turnIdx + "_" + partIdx, caps,
                            toolState.originalToSanitizedId, toolState.usedSanitizedIds);
                }
            } else if (sanitizedCallId != null && toolName != null) {
                Deque<String> queue = toolState.pendingIdsByName.get(toolName);
                if (queue != null && !queue.isEmpty()) {
                    if (sanitizedCallId.equals(queue.peekFirst())) {
                        queue.pollFirst();
                    } else {
                        queue.remove(sanitizedCallId);
                    }
                }
            }
            if (cleanedContent == null
                    && java.util.Objects.equals(sanitizedCallId, tr.callId())
                    && java.util.Objects.equals(toolName, tr.toolName())) {
                return tr;
            }
            return new CanonicalContentPart.ToolResultPart(
                    sanitizedCallId, toolName, cleanedContent != null ? cleanedContent : origContent,
                    tr.isError(), tr.cacheControl()
            );
        }
        return part;
    }

    /**
     * Deterministically maps a tool call ID into a target-compliant identifier while preserving
     * a strict 1-to-1 bijection between {@code ToolCallPart} and {@code ToolResultPart}.
     */
    static String sanitizeToolCallId(
            String rawId,
            ProviderCapabilities caps,
            Map<String, String> map,
            Set<String> used
    ) {
        String existing = map.get(rawId);
        if (existing != null) {
            return existing;
        }
        int maxLen = Math.max(16, caps.toolCallIdMaxLength());
        boolean alnumOnly = caps.toolCallIdAlnumDashUnderscoreOnly();

        if (isValidToolCallId(rawId, maxLen, alnumOnly) && !used.contains(rawId)) {
            map.put(rawId, rawId);
            used.add(rawId);
            return rawId;
        }

        StringBuilder cleaned = new StringBuilder(rawId.length());
        for (int i = 0; i < rawId.length(); i++) {
            char c = rawId.charAt(i);
            if (!alnumOnly || isAsciiAlnumDashUnderscore(c)) {
                cleaned.append(c);
            } else {
                cleaned.append('_');
            }
        }
        String base = cleaned.toString();
        if (base.isEmpty()) {
            base = "call";
        }
        String hash = shortHash(rawId);
        int prefixLimit = Math.max(1, maxLen - hash.length() - 1);
        String prefix = base.length() > prefixLimit ? base.substring(0, prefixLimit) : base;
        String candidate = prefix + "_" + hash;

        int seq = 0;
        while (used.contains(candidate)) {
            String suffix = "_" + hash + "_" + (++seq);
            int pLen = Math.max(1, maxLen - suffix.length());
            candidate = (base.length() > pLen ? base.substring(0, pLen) : base) + suffix;
        }

        map.put(rawId, candidate);
        used.add(candidate);
        return candidate;
    }

    private static boolean isValidToolCallId(String id, int maxLen, boolean alnumOnly) {
        if (id == null || id.isEmpty() || id.length() > maxLen) {
            return false;
        }
        if (!alnumOnly) {
            return true;
        }
        for (int i = 0; i < id.length(); i++) {
            if (!isAsciiAlnumDashUnderscore(id.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlnumDashUnderscore(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
    }

    private static String shortHash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Copy-on-write interrupted tool-call repair: returns the original list with zero allocations
     * on clean conversations and only allocates synthetic results when an assistant tool call lacks
     * a matching tool result before subsequent turns.
     */
    private static List<CanonicalTurn> repairInterruptedToolCalls(List<CanonicalTurn> turns) {
        List<CanonicalTurn> out = null;
        int n = turns.size();
        for (int i = 0; i < n; i++) {
            CanonicalTurn turn = turns.get(i);
            if (out != null) {
                out.add(turn);
            }
            if (turn.role() != CanonicalRole.ASSISTANT || i == n - 1) {
                continue;
            }
            boolean hasCall = false;
            for (CanonicalContentPart part : turn.parts()) {
                if (part instanceof CanonicalContentPart.ToolCallPart tc
                        && tc.callId() != null && !tc.callId().isBlank()) {
                    hasCall = true;
                    break;
                }
            }
            if (!hasCall) {
                continue;
            }

            Map<String, String> expectedCalls = new LinkedHashMap<>();
            for (CanonicalContentPart part : turn.parts()) {
                if (part instanceof CanonicalContentPart.ToolCallPart tc
                        && tc.callId() != null && !tc.callId().isBlank()) {
                    expectedCalls.put(tc.callId(), tc.name());
                }
            }
            int unanswered = expectedCalls.size();
            Set<String> answeredIds = null;
            for (int j = i + 1; j < n && unanswered > 0; j++) {
                CanonicalTurn next = turns.get(j);
                if (next.role() == CanonicalRole.ASSISTANT) {
                    break;
                }
                if (next.role() == CanonicalRole.TOOL || next.role() == CanonicalRole.USER) {
                    for (CanonicalContentPart part : next.parts()) {
                        if (part instanceof CanonicalContentPart.ToolResultPart tr
                                && tr.callId() != null && expectedCalls.containsKey(tr.callId())) {
                            if (answeredIds == null) {
                                answeredIds = new HashSet<>(expectedCalls.size() * 2);
                            }
                            if (answeredIds.add(tr.callId())) {
                                unanswered--;
                            }
                        }
                    }
                }
            }
            if (unanswered == 0) {
                continue;
            }
            List<CanonicalContentPart> synthesized = new ArrayList<>(unanswered);
            for (Map.Entry<String, String> entry : expectedCalls.entrySet()) {
                if (answeredIds == null || !answeredIds.contains(entry.getKey())) {
                    synthesized.add(new CanonicalContentPart.ToolResultPart(
                            entry.getKey(),
                            entry.getValue(),
                            List.of(new CanonicalContentPart.TextPart("[Tool execution was interrupted]")),
                            true,
                            null
                    ));
                }
            }
            if (out == null) {
                out = new ArrayList<>(n + 2);
                for (int k = 0; k <= i; k++) {
                    out.add(turns.get(k));
                }
            }
            out.add(new CanonicalTurn(CanonicalRole.TOOL, synthesized));
        }
        return out != null ? out : turns;
    }

    private static List<CanonicalToolDefinition> normalizeTools(List<CanonicalToolDefinition> tools) {
        if (tools.isEmpty()) {
            return tools;
        }
        List<CanonicalToolDefinition> out = null;
        for (int i = 0; i < tools.size(); i++) {
            CanonicalToolDefinition tool = tools.get(i);
            if (!tool.hasValidObjectSchema()) {
                if (out == null) {
                    out = new ArrayList<>(tools.size());
                    for (int k = 0; k < i; k++) {
                        out.add(tools.get(k));
                    }
                }
                out.add(CanonicalToolDefinition.ofValidated(
                        tool.name(), tool.description(), ValidatedJsonObject.EMPTY_OBJECT_SCHEMA,
                        tool.strict(), tool.cacheControl()));
            } else if (out != null) {
                out.add(tool);
            }
        }
        return out != null ? out : tools;
    }

    private static CanonicalToolChoice normalizeToolChoice(
            CanonicalToolChoice toolChoice,
            List<CanonicalToolDefinition> normalizedTools,
            TranslationPolicy policy
    ) {
        if (normalizedTools.isEmpty()) {
            return toolChoice.mode() == CanonicalToolChoice.ToolChoiceMode.OMITTED
                    && toolChoice.parallelToolCalls() == null
                    ? toolChoice
                    : CanonicalToolChoice.omitted();
        }
        if (toolChoice.mode() == CanonicalToolChoice.ToolChoiceMode.SPECIFIC) {
            String targetName = toolChoice.specificToolName();
            boolean found = targetName != null && normalizedTools.stream()
                    .anyMatch(t -> targetName.equals(t.name()));
            if (!found) {
                if (policy == TranslationPolicy.STRICT) {
                    throw new HyperLlmException(
                            ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE,
                            "Specific tool_choice '" + targetName + "' references a tool not available after translation"
                    );
                }
                return new CanonicalToolChoice(
                        CanonicalToolChoice.ToolChoiceMode.AUTO, null, toolChoice.parallelToolCalls());
            }
        }
        return toolChoice;
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
            if (request.responseFormat() instanceof CanonicalResponseFormat.JsonObjectFormat) {
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
