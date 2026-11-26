package app.pagefault.hyperllm.gateway.proxy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe per-request batcher that turns a stream of downstream SSE frames into fewer, larger
 * socket writes.
 *
 * <p>Synchronized across EventLoop {@link #offer}/{@link #drain} calls and the shared
 * {@link SseFrameCoalescer} ticker's {@link #poll} calls, with an {@link AtomicBoolean} queue
 * membership guard so each active batcher appears at most once in the coalescer's pending queue.
 *
 * <p>The first frame is never delayed — time-to-first-token is user-visible and the window is only
 * applied once a stream is flowing.
 */
public final class SseFrameBatcher {

    private final long windowNanos;
    private final int maxBytes;
    private final int maxFrames;
    private final AtomicBoolean enqueued = new AtomicBoolean(false);

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
    public synchronized byte[] offer(byte[] frame, long nowNanos) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length == 0) {
            return null;
        }
        if (!firstFrameSent && pendingFrames == 0) {
            firstFrameSent = true;
            return frame;
        }
        if (pendingFrames == 0) {
            if (frame.length > maxBytes) {
                return frame;
            }
            deadlineNanos = nowNanos + windowNanos;
            append(frame);
            return null;
        }
        if (pendingLength + frame.length > maxBytes || pendingFrames + 1 > maxFrames) {
            byte[] flushed = drainLocked();
            if (frame.length > maxBytes) {
                // Preserve both the previously buffered batch and this oversized frame without dropping either.
                if (flushed == null || flushed.length == 0) {
                    return frame;
                }
                byte[] combined = new byte[flushed.length + frame.length];
                System.arraycopy(flushed, 0, combined, 0, flushed.length);
                System.arraycopy(frame, 0, combined, flushed.length, frame.length);
                return combined;
            }
            deadlineNanos = nowNanos + windowNanos;
            append(frame);
            return flushed;
        }
        append(frame);
        return null;
    }

    /** Returns the buffered batch once its window has elapsed, or {@code null} when nothing is due. */
    public synchronized byte[] poll(long nowNanos) {
        if (pendingFrames == 0 || nowNanos < deadlineNanos) {
            return null;
        }
        return drainLocked();
    }

    /** True while frames are held, so a scheduler only touches batchers that have work. */
    public synchronized boolean hasPending() {
        return pendingFrames > 0;
    }

    /** Returns and clears the buffered batch, regardless of the window. */
    public synchronized byte[] drain() {
        return drainLocked();
    }

    boolean tryMarkEnqueued() {
        return enqueued.compareAndSet(false, true);
    }

    void clearEnqueued() {
        enqueued.set(false);
    }

    private byte[] drainLocked() {
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
