package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.ir.TokenUsage;

import java.util.List;

/**
 * Unified reactive streaming bridge used by the gateway for both zero-overhead homomorphic
 * passthrough and cross-protocol Canonical IR streaming translation.
 */
public interface StreamBridge {

    default String contentType() {
        return "text/event-stream";
    }

    /**
     * Feeds a raw upstream chunk and emits zero or more complete downstream frames.
     */
    List<byte[]> feed(byte[] upstreamChunk);

    /**
     * Flushes any trailing state-machine frames when the upstream completes cleanly.
     */
    List<byte[]> complete();

    /**
     * Finalizes strictly disjoint five-dimensional {@link TokenUsage}, falling back to
     * length-based token estimation on missing upstream telemetry or premature disconnection.
     */
    TokenUsage finalizeUsage(boolean cleanCompletion);
}
