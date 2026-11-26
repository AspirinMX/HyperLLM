package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;

import java.util.List;

/**
 * Stateful per-request encoder converting Canonical IR stream events directly into downstream wire
 * frames in a {@link ByteSink}.
 */
public interface DownstreamStreamEncoder {

    String contentType();

    /** Encodes a general {@link CanonicalStreamEvent} directly into {@code out}. */
    void encode(CanonicalStreamEvent event, ByteSink out);

    /** Fast-path unboxed text delta encoder (avoids {@code TextDelta} record + intermediate arrays). */
    default void encodeTextDelta(int blockIndex, CharSequence textDelta, ByteSink out) {
        if (textDelta != null && !textDelta.isEmpty()) {
            encode(new CanonicalStreamEvent.TextDelta(blockIndex, textDelta.toString()), out);
        }
    }

    /** Fast-path unboxed thinking delta encoder. */
    default void encodeThinkingDelta(int blockIndex, CharSequence thinkingDelta, ByteSink out) {
        if (thinkingDelta != null && !thinkingDelta.isEmpty()) {
            encode(new CanonicalStreamEvent.ThinkingDelta(blockIndex, thinkingDelta.toString()), out);
        }
    }

    /** Fast-path unboxed refusal delta encoder. */
    default void encodeRefusalDelta(int blockIndex, CharSequence refusalDelta, ByteSink out) {
        if (refusalDelta != null && !refusalDelta.isEmpty()) {
            encode(new CanonicalStreamEvent.RefusalDelta(blockIndex, refusalDelta.toString()), out);
        }
    }

    /** Fast-path unboxed tool call argument JSON delta encoder. */
    default void encodeToolCallArgsDelta(int blockIndex, String callId, CharSequence partialJsonDelta, ByteSink out) {
        if (partialJsonDelta != null && !partialJsonDelta.isEmpty()) {
            encode(new CanonicalStreamEvent.ToolCallArgsDelta(blockIndex, callId, partialJsonDelta.toString()), out);
        }
    }

    /** Emits any required terminal frames directly into {@code out}. */
    void finish(ByteSink out);

    /** Backwards-compatible list-returning overload used by tests. */
    default List<byte[]> encode(CanonicalStreamEvent event) {
        ByteSink.HeapByteSink sink = new ByteSink.HeapByteSink(256);
        encode(event, sink);
        return sink.writtenBytes() == 0 ? List.of() : List.of(sink.toExactByteArray());
    }

    /** Backwards-compatible list-returning finish overload used by tests. */
    default List<byte[]> finish() {
        ByteSink.HeapByteSink sink = new ByteSink.HeapByteSink(256);
        finish(sink);
        return sink.writtenBytes() == 0 ? List.of() : List.of(sink.toExactByteArray());
    }
}
