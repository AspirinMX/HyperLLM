package app.pagefault.hyperllm.protocol.ir;

/**
 * Exhaustive streaming event algebra used by all four upstream decoders and downstream encoders.
 */
public sealed interface CanonicalStreamEvent permits
        CanonicalStreamEvent.MessageStart,
        CanonicalStreamEvent.BlockStart,
        CanonicalStreamEvent.TextDelta,
        CanonicalStreamEvent.ThinkingDelta,
        CanonicalStreamEvent.ThinkingSignatureDelta,
        CanonicalStreamEvent.RedactedThinking,
        CanonicalStreamEvent.RefusalDelta,
        CanonicalStreamEvent.ToolCallStart,
        CanonicalStreamEvent.ToolCallArgsDelta,
        CanonicalStreamEvent.BlockStop,
        CanonicalStreamEvent.UsageUpdate,
        CanonicalStreamEvent.MessageFinish,
        CanonicalStreamEvent.StreamError {

    record MessageStart(
            String responseId,
            String virtualModel,
            long createdEpochSeconds
    ) implements CanonicalStreamEvent {
    }

    record BlockStart(
            int blockIndex,
            BlockKind kind
    ) implements CanonicalStreamEvent {
    }

    record TextDelta(
            int blockIndex,
            String textDelta
    ) implements CanonicalStreamEvent {
    }

    record ThinkingDelta(
            int blockIndex,
            String thinkingDelta
    ) implements CanonicalStreamEvent {
    }

    record ThinkingSignatureDelta(
            int blockIndex,
            String signatureDelta,
            ProtocolKind originProtocol
    ) implements CanonicalStreamEvent {
    }

    record RedactedThinking(
            int blockIndex,
            String opaqueData,
            ProtocolKind originProtocol
    ) implements CanonicalStreamEvent {
    }

    record RefusalDelta(
            int blockIndex,
            String refusalDelta
    ) implements CanonicalStreamEvent {
    }

    record ToolCallStart(
            int blockIndex,
            String callId,
            String itemId,
            String name,
            String thoughtSignature,
            ProtocolKind originProtocol
    ) implements CanonicalStreamEvent {
    }

    record ToolCallArgsDelta(
            int blockIndex,
            String callId,
            String partialJsonDelta
    ) implements CanonicalStreamEvent {
    }

    record BlockStop(
            int blockIndex
    ) implements CanonicalStreamEvent {
    }

    record UsageUpdate(
            TokenUsage usage,
            boolean cumulative
    ) implements CanonicalStreamEvent {
    }

    record MessageFinish(
            CanonicalStopReason stopReason,
            String stopSequence
    ) implements CanonicalStreamEvent {
    }

    record StreamError(
            String errorType,
            String message
    ) implements CanonicalStreamEvent {
    }
}
