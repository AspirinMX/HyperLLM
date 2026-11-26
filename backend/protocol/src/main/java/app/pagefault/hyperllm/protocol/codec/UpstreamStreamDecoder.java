package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful per-request decoder converting upstream streaming byte chunks into Canonical IR stream
 * events pushed into a {@link StreamEventSink}.
 */
public interface UpstreamStreamDecoder {

    /** Push-based zero-allocation streaming decode from a byte slice into {@code sink}. */
    void feed(byte[] upstreamChunk, int offset, int length, StreamEventSink sink);

    /** Flushes any trailing buffered frame and closes open blocks into {@code sink}. */
    void complete(StreamEventSink sink);

    /** Backwards-compatible list-returning overload used by tests and standalone callers. */
    default List<CanonicalStreamEvent> feed(byte[] upstreamChunk) {
        if (upstreamChunk == null || upstreamChunk.length == 0) {
            return List.of();
        }
        List<CanonicalStreamEvent> out = new ArrayList<>();
        feed(upstreamChunk, 0, upstreamChunk.length, out::add);
        return out;
    }

    /** Backwards-compatible list-returning completion overload. */
    default List<CanonicalStreamEvent> complete() {
        List<CanonicalStreamEvent> out = new ArrayList<>();
        complete(out::add);
        return out;
    }
}
