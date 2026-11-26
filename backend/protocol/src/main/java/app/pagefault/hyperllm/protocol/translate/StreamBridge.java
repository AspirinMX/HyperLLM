package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;

import java.util.List;

/**
 * Unified reactive streaming bridge: every upstream SSE chunk is decoded into Canonical IR and
 * re-encoded for the downstream protocol, including the same-protocol case.
 */
public interface StreamBridge {

    default String contentType() {
        return "text/event-stream";
    }

    /**
     * Push-based zero-copy streaming feed: decodes upstream bytes in {@code [offset, offset+length)}
     * and writes all translated downstream SSE bytes directly into {@code out} (e.g. a pooled Netty
     * direct {@code ByteBuf} sink).
     */
    void feed(byte[] upstreamChunk, int offset, int length, ByteSink out);

    /**
     * Flushes any trailing state-machine frames directly into {@code out} when the upstream completes.
     */
    void complete(ByteSink out);

    /**
     * Backwards-compatible byte-array feed returning downstream bytes for one upstream chunk.
     */
    byte[] feed(byte[] upstreamChunk);

    /**
     * Backwards-compatible completion returning trailing downstream frames.
     */
    List<byte[]> complete();

    /**
     * Finalizes strictly disjoint five-dimensional {@link TokenUsage}, falling back to
     * length-based token estimation on missing upstream telemetry or premature disconnection.
     */
    TokenUsage finalizeUsage(boolean cleanCompletion);
}
