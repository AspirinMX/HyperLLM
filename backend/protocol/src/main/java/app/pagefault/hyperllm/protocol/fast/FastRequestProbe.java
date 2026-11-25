package app.pagefault.hyperllm.protocol.fast;

import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;

/**
 * Result of a single-pass, strict RFC 8259 UTF-8 byte scan over an inbound LLM request body.
 * Captures all routing, admission, token estimation, and exact byte-offset patching metadata
 * without building a JSON DOM tree or allocating strings for prompt/tool contents.
 */
public record FastRequestProbe(
        ProtocolKind inboundProtocol,
        String model,
        boolean stream,
        Integer maxOutputTokens,
        boolean clientRequestedUsage,
        boolean geminiAltSse,
        String anthropicVersion,
        String anthropicBeta,
        int estimatedPromptTokens,
        int modelValStart,
        int modelValEnd,
        int streamOptionsStart,
        int streamOptionsEnd,
        boolean streamOptionsIsObject,
        boolean streamOptionsHasFields,
        int includeUsageValStart,
        int includeUsageValEnd,
        boolean hasNonNullMaxTokens,
        int maxTokensValStart,
        int maxTokensValEnd,
        int rootCloseBrace,
        boolean rootHasFields,
        boolean requiresSlowPath
) {
    public StreamRequestMetadata toStreamMetadata() {
        return new StreamRequestMetadata(
                inboundProtocol,
                model,
                clientRequestedUsage,
                geminiAltSse,
                estimatedPromptTokens
        );
    }
}
