package app.pagefault.hyperllm.protocol.adapter;

import app.pagefault.hyperllm.protocol.ir.TokenUsage;

import java.util.List;

/**
 * Per-request stateful SSE tracker that inspects upstream SSE byte chunks, accumulates streamed text
 * for fallback estimation, optionally strips gateway-injected frames, and emits finalized usage.
 */
public interface StreamUsageTracker {

    /**
     * Feeds a raw SSE chunk received from upstream and returns complete downstream SSE frames to forward.
     */
    List<byte[]> feed(byte[] chunk);

    /**
     * Flushes any buffered trailing bytes when upstream signals normal stream completion.
     */
    List<byte[]> complete();

    /**
     * Computes final five-dimensional token usage using upstream telemetry or jtokkit fallback.
     *
     * @param cleanCompletion true if the upstream response finished without abrupt disconnect/error
     */
    TokenUsage finalizeUsage(boolean cleanCompletion);
}
