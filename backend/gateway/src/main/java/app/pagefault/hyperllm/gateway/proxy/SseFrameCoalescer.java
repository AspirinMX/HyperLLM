package app.pagefault.hyperllm.gateway.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link SseFrameBatcher} instances from one shared tick without duplicate queue entries.
 *
 * <p>Supports both legacy {@code byte[]} sinks and zero-copy direct {@link ByteBuf} sinks
 * ({@link #offerBuf}, {@link #drainBuf}, {@link #release}).
 */
public final class SseFrameCoalescer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SseFrameCoalescer.class.getName());

    /** Where a flushed byte[] batch goes: the per-request frame sink. */
    public interface Sink {
        void emit(byte[] batch);
    }

    /** Where a flushed zero-copy direct {@link ByteBuf} batch goes (receives ownership). */
    public interface BufSink {
        void emit(ByteBuf batch);
    }

    private record Entry(SseFrameBatcher batcher, Sink sink) {
    }

    private record BufEntry(SseFrameBatcher batcher, BufSink sink) {
    }

    private final ScheduledExecutorService ticker;
    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();
    private final Queue<BufEntry> pendingBufs = new ConcurrentLinkedQueue<>();
    private final long windowNanos;
    private final int maxBytes;
    private final int maxFrames;

    public SseFrameCoalescer(long windowNanos, long tickMillis, int maxBytes, int maxFrames) {
        this.windowNanos = windowNanos;
        this.maxBytes = maxBytes;
        this.maxFrames = maxFrames;
        this.ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-frame-coalescer");
            thread.setDaemon(true);
            return thread;
        });
        this.ticker.scheduleWithFixedDelay(this::flushDue, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    public SseFrameBatcher newBatcher() {
        return new SseFrameBatcher(windowNanos, maxBytes, maxFrames);
    }

    /** Offers a byte[] frame, emitting whatever is due right away; registers at most once while work is held. */
    public void offer(SseFrameBatcher batcher, Sink sink, byte[] frame) {
        boolean shouldEnqueue;
        synchronized (batcher) {
            byte[] immediate = batcher.offer(frame, System.nanoTime());
            if (immediate != null) {
                sink.emit(immediate);
            }
            shouldEnqueue = batcher.hasPending() && batcher.tryMarkEnqueued();
        }
        if (shouldEnqueue) {
            pending.add(new Entry(batcher, sink));
        }
    }

    /** Emits everything still buffered in byte[] mode for a finishing or cancelled stream. */
    public void drain(SseFrameBatcher batcher, Sink sink) {
        synchronized (batcher) {
            byte[] rest = batcher.drain();
            if (rest != null) {
                sink.emit(rest);
            }
        }
    }

    /**
     * Offers a direct {@link ByteBuf} frame zero-copy (taking ownership), emitting immediately when
     * due and enqueuing the batcher at most once while buffered bytes remain.
     */
    public void offerBuf(SseFrameBatcher batcher, ByteBufAllocator allocator, BufSink sink, ByteBuf frame) {
        boolean shouldEnqueue;
        synchronized (batcher) {
            ByteBuf immediate = batcher.offerBuf(allocator, frame, System.nanoTime());
            if (immediate != null) {
                emitBufSafely(sink, immediate);
            }
            shouldEnqueue = batcher.hasPendingBuf() && batcher.tryMarkBufEnqueued();
        }
        if (shouldEnqueue) {
            pendingBufs.add(new BufEntry(batcher, sink));
        }
    }

    /** Emits all buffered direct {@link ByteBuf} frames for a cleanly completing stream. */
    public void drainBuf(SseFrameBatcher batcher, BufSink sink) {
        synchronized (batcher) {
            ByteBuf rest = batcher.drainBuf();
            if (rest != null) {
                emitBufSafely(sink, rest);
            }
        }
    }

    /** Releases any held direct {@link ByteBuf} frames on stream cancel or error. */
    public void release(SseFrameBatcher batcher) {
        batcher.release();
    }

    int pendingQueueSize() {
        return pending.size() + pendingBufs.size();
    }

    private static void emitBufSafely(BufSink sink, ByteBuf buf) {
        try {
            sink.emit(buf);
        } catch (RuntimeException ex) {
            if (buf.refCnt() > 0) {
                buf.release();
            }
            throw ex;
        }
    }

    private void flushDue() {
        long now = System.nanoTime();
        int size = pending.size();
        for (int i = 0; i < size; i++) {
            Entry entry = pending.poll();
            if (entry == null) {
                break;
            }
            SseFrameBatcher batcher = entry.batcher();
            boolean requeue;
            synchronized (batcher) {
                try {
                    byte[] batch = batcher.poll(now);
                    if (batch != null) {
                        entry.sink().emit(batch);
                    }
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Coalesced flush failed", ex);
                } finally {
                    batcher.clearEnqueued();
                    requeue = batcher.hasPending() && batcher.tryMarkEnqueued();
                }
            }
            if (requeue) {
                pending.add(entry);
            }
        }

        int bufSize = pendingBufs.size();
        for (int i = 0; i < bufSize; i++) {
            BufEntry entry = pendingBufs.poll();
            if (entry == null) {
                break;
            }
            SseFrameBatcher batcher = entry.batcher();
            boolean requeue;
            synchronized (batcher) {
                try {
                    ByteBuf batch = batcher.pollBuf(now);
                    if (batch != null) {
                        emitBufSafely(entry.sink(), batch);
                    }
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Coalesced ByteBuf flush failed", ex);
                } finally {
                    batcher.clearBufEnqueued();
                    requeue = batcher.hasPendingBuf() && batcher.tryMarkBufEnqueued();
                }
            }
            if (requeue) {
                pendingBufs.add(entry);
            }
        }
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        BufEntry e;
        while ((e = pendingBufs.poll()) != null) {
            e.batcher().release();
        }
    }
}
