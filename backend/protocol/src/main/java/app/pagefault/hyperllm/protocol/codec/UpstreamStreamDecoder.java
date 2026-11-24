package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;

import java.util.List;

/**
 * Stateful per-request decoder converting upstream streaming byte chunks into {@link CanonicalStreamEvent}s.
 */
public interface UpstreamStreamDecoder {

    List<CanonicalStreamEvent> feed(byte[] upstreamChunk);

    List<CanonicalStreamEvent> complete();
}
