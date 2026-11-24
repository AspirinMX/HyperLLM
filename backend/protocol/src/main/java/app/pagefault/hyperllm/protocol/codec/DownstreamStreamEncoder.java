package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;

import java.util.List;

/**
 * Stateful per-request encoder converting {@link CanonicalStreamEvent}s into downstream wire frames.
 */
public interface DownstreamStreamEncoder {

    String contentType();

    List<byte[]> encode(CanonicalStreamEvent event);

    List<byte[]> finish();
}
