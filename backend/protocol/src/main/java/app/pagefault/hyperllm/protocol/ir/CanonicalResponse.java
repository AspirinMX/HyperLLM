package app.pagefault.hyperllm.protocol.ir;

import java.util.List;

/**
 * Protocol-agnostic non-streaming response IR.
 */
public record CanonicalResponse(
        String id,
        String virtualModel,
        long createdEpochSeconds,
        List<CanonicalContentPart> content,
        CanonicalStopReason stopReason,
        String stopSequence,
        TokenUsage usage,
        ProtocolKind upstreamProtocol
) {
    public CanonicalResponse {
        content = content == null ? List.of() : List.copyOf(content);
        stopReason = stopReason == null ? CanonicalStopReason.END_TURN : stopReason;
        usage = usage == null ? TokenUsage.empty() : usage;
    }

    public boolean hasToolCalls() {
        for (CanonicalContentPart part : content) {
            if (part instanceof CanonicalContentPart.ToolCallPart) {
                return true;
            }
        }
        return false;
    }
}
