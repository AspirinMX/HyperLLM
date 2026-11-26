package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;

/**
 * Push-based event consumer for streaming protocol decoders.
 *
 * <p>Delta fast-path methods accept {@link CharSequence} directly so high-frequency token frames
 * bypass {@link CanonicalStreamEvent} record allocation and intermediate {@code List} wrapping
 * while preserving exact Canonical IR event semantics.
 */
public interface StreamEventSink {

    void onEvent(CanonicalStreamEvent event);

    default void onTextDelta(int blockIndex, CharSequence textDelta) {
        if (textDelta != null && !textDelta.isEmpty()) {
            onEvent(new CanonicalStreamEvent.TextDelta(blockIndex, textDelta.toString()));
        }
    }

    default void onThinkingDelta(int blockIndex, CharSequence thinkingDelta) {
        if (thinkingDelta != null && !thinkingDelta.isEmpty()) {
            onEvent(new CanonicalStreamEvent.ThinkingDelta(blockIndex, thinkingDelta.toString()));
        }
    }

    default void onRefusalDelta(int blockIndex, CharSequence refusalDelta) {
        if (refusalDelta != null && !refusalDelta.isEmpty()) {
            onEvent(new CanonicalStreamEvent.RefusalDelta(blockIndex, refusalDelta.toString()));
        }
    }

    default void onToolCallArgsDelta(int blockIndex, String callId, CharSequence partialJsonDelta) {
        if (partialJsonDelta != null && !partialJsonDelta.isEmpty()) {
            onEvent(new CanonicalStreamEvent.ToolCallArgsDelta(blockIndex, callId, partialJsonDelta.toString()));
        }
    }
}
