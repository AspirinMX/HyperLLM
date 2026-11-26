package app.pagefault.hyperllm.gateway.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe per-request batcher that turns a stream of downstream SSE frames into fewer, larger
 * socket writes.
 *
 * <p>Supports both legacy {@code byte[]} batching and zero-copy Netty direct {@link ByteBuf} /
 * {@link CompositeByteBuf} coalescing ({@link #offerBuf}, {@link #pollBuf}, {@link #drainBuf},
 * {@link #release}), passing off-heap slices straight to Linux gathering {@code writev(2)} without
 * intermediate heap copies.
 *
 * <p>The first frame is never delayed — time-to-first-token is user-visible and the window is only
 * applied once a stream is flowing.
 */
public final class SseFrameBatcher {

    private final long windowNanos;
    private final int maxBytes;
    private final int maxFrames;
    private final AtomicBoolean enqueued = new AtomicBoolean(false);
    private final AtomicBoolean bufEnqueued = new AtomicBoolean(false);

    // Legacy byte[] path state
    private byte[] pending;
    private int pendingLength;
    private int pendingFrames;
    private long deadlineNanos;
    private boolean firstFrameSent;

    // Zero-copy Netty ByteBuf path state
    private ByteBuf pendingBuf;
    private int pendingBufBytes;
    private int pendingBufFrames;
    private long bufDeadlineNanos;
    private boolean firstBufSent;

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

    /** True while byte[] frames are held, so a scheduler only touches batchers that have work. */
    public synchronized boolean hasPending() {
        return pendingFrames > 0;
    }

    /** Returns and clears the buffered byte[] batch, regardless of the window. */
    public synchronized byte[] drain() {
        return drainLocked();
    }

    // ---- Zero-copy Direct ByteBuf / CompositeByteBuf coalescing ----

    /**
     * Takes ownership of {@code frame} and returns a {@link ByteBuf} that must be emitted immediately
     * (transferring ownership back to the caller), or {@code null} when buffered zero-copy until the
     * window elapses or batch limits are hit.
     */
    public synchronized ByteBuf offerBuf(ByteBufAllocator allocator, ByteBuf frame, long nowNanos) {
        Objects.requireNonNull(allocator, "allocator");
        if (frame == null) {
            return null;
        }
        int readable = frame.readableBytes();
        if (readable <= 0) {
            frame.release();
            return null;
        }
        if (!firstBufSent && pendingBuf == null) {
            firstBufSent = true;
            return frame;
        }
        if (pendingBuf == null) {
            if (readable > maxBytes) {
                return frame;
            }
            bufDeadlineNanos = nowNanos + windowNanos;
            pendingBuf = frame;
            pendingBufBytes = readable;
            pendingBufFrames = 1;
            return null;
        }
        if (pendingBufBytes + readable > maxBytes || pendingBufFrames + 1 > maxFrames) {
            ByteBuf flushed = drainBufLocked();
            if (readable > maxBytes) {
                return flushed == null ? frame : combineZeroCopy(allocator, flushed, frame);
            }
            bufDeadlineNanos = nowNanos + windowNanos;
            pendingBuf = frame;
            pendingBufBytes = readable;
            pendingBufFrames = 1;
            return flushed;
        }
        pendingBuf = combineZeroCopy(allocator, pendingBuf, frame);
        pendingBufBytes += readable;
        pendingBufFrames++;
        return null;
    }

    /** Returns the buffered {@link ByteBuf} batch once its window has elapsed, or {@code null}. */
    public synchronized ByteBuf pollBuf(long nowNanos) {
        if (pendingBuf == null || nowNanos < bufDeadlineNanos) {
            return null;
        }
        return drainBufLocked();
    }

    /** True while direct {@link ByteBuf} frames are buffered. */
    public synchronized boolean hasPendingBuf() {
        return pendingBuf != null;
    }

    /** Returns and clears all buffered {@link ByteBuf} frames, transferring ownership to the caller. */
    public synchronized ByteBuf drainBuf() {
        return drainBufLocked();
    }

    /** Releases any held direct {@link ByteBuf} on stream cancellation or transport error. */
    public synchronized void release() {
        ByteBuf held = drainBufLocked();
        if (held != null) {
            held.release();
        }
    }

    boolean tryMarkEnqueued() {
        return enqueued.compareAndSet(false, true);
    }

    void clearEnqueued() {
        enqueued.set(false);
    }

    boolean tryMarkBufEnqueued() {
        return bufEnqueued.compareAndSet(false, true);
    }

    void clearBufEnqueued() {
        bufEnqueued.set(false);
    }

    private ByteBuf combineZeroCopy(ByteBufAllocator allocator, ByteBuf first, ByteBuf second) {
        if (first instanceof CompositeByteBuf composite) {
            composite.addComponent(true, second);
            return composite;
        }
        CompositeByteBuf composite = allocator.compositeDirectBuffer(Math.min(Math.max(maxFrames, 4), 32));
        composite.addComponents(true, first, second);
        return composite;
    }

    private ByteBuf drainBufLocked() {
        ByteBuf out = pendingBuf;
        pendingBuf = null;
        pendingBufBytes = 0;
        pendingBufFrames = 0;
        return out;
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
        int needed = pendingLength + frame.length;
        if (pending == null) {
            pending = new byte[Math.max(needed, Math.min(maxBytes, 1024))];
        } else if (needed > pending.length) {
            int next = Math.max(needed, Math.min(maxBytes, pending.length * 2));
            pending = java.util.Arrays.copyOf(pending, next);
        }
        System.arraycopy(frame, 0, pending, pendingLength, frame.length);
        pendingLength = needed;
        pendingFrames++;
    }
}
