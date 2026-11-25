package app.pagefault.hyperllm.gateway.proxy;

import java.util.Objects;

/**
 * Per-request batcher that turns a stream of downstream SSE frames into fewer, larger socket writes.
 *
 * <p>An upstream provider emits one SSE event per token, so a proxied stream pays one {@code writev}
 * per frame - the dominant cost measured at 2,000 concurrent streams. Holding frames for a short window
 * lets several events travel in one write. The batch is deliberately dumb about scheduling: callers
 * feed {@link #offer} and call {@link #poll} from a shared tick, which keeps the whole thing free of
 * timers per connection.
 *
 * <p>The first frame is never delayed - time-to-first-token is a user-visible metric and the window is
 * only worth paying for once a stream is actually flowing. Frames are concatenated verbatim, so the
 * wire format is unchanged: the client still sees a sequence of complete {@code data:} events.
 */
public final class SseFrameBatcher {

    private final long windowNanos;
    private final int maxBytes;
    private final int maxFrames;

    private byte[] pending;
    private int pendingLength;
    private int pendingFrames;
    private long deadlineNanos;
    private boolean firstFrameSent;

    public SseFrameBatcher(long windowNanos, int maxBytes, int maxFrames) {
        if (windowNanos < 0) {
            throw new IllegalArgumentException("windowNanos must not be negative");
        }
        if (maxBytes <= 0 || maxFrames <= 0) {
            throw new IllegalArgumentException("maxBytes and maxFrames must be positive");
        }
        this.windowNanos = windowNanos;
        this.maxBytes = maxBytes;
        this.maxFrames = maxFrames;
    }

    /**
     * Accepts one downstream frame and returns the bytes that must be written immediately, or
     * {@code null} when the frame has been buffered until the window elapses (or the batch is full).
     */
    public byte[] offer(byte[] frame, long nowNanos) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length == 0) {
            return null;
        }
        if (!firstFrameSent && pendingFrames == 0) {
            firstFrameSent = true;
            return frame;
        }
        if (pendingFrames == 0) {
            deadlineNanos = nowNanos + windowNanos;
            append(frame);
            return null;
        }
        if (pendingLength + frame.length > maxBytes || pendingFrames + 1 > maxFrames) {
            byte[] flushed = drain();
            if (frame.length > maxBytes) {
                // a single frame larger than a whole batch: never buffer it, write it on its own
                return frame;
            }
            deadlineNanos = nowNanos + windowNanos;
            append(frame);
            return flushed;
        }
        append(frame);
        return null;
    }

    /** Returns the buffered batch once its window has elapsed, or {@code null} when nothing is due. */
    public byte[] poll(long nowNanos) {
        if (pendingFrames == 0 || nowNanos < deadlineNanos) {
            return null;
        }
        return drain();
    }

    /** True while frames are held, so a scheduler only touches batchers that have work. */
    public boolean hasPending() {
        return pendingFrames > 0;
    }

    /** Returns and clears the buffered batch, regardless of the window. */
    public byte[] drain() {
        if (pendingFrames == 0) {
            return null;
        }
        byte[] out = pendingLength == pending.length
                ? pending
                : java.util.Arrays.copyOf(pending, pendingLength);
        pending = null;
        pendingLength = 0;
        pendingFrames = 0;
        return out;
    }

    private void append(byte[] frame) {
        if (pending == null) {
            pending = new byte[Math.min(maxBytes, Math.max(256, frame.length * 4))];
        }
        if (pendingLength + frame.length > pending.length) {
            int capacity = Math.min(maxBytes, Math.max(pendingLength + frame.length, pending.length * 2));
            pending = java.util.Arrays.copyOf(pending, capacity);
        }
        System.arraycopy(frame, 0, pending, pendingLength, frame.length);
        pendingLength += frame.length;
        pendingFrames++;
    }
}
