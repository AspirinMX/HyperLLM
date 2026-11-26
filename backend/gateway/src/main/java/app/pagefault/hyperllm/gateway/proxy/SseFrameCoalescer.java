package app.pagefault.hyperllm.gateway.proxy;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link SseFrameBatcher} instances from one shared tick without duplicate queue entries.
 *
 * <p>Each batcher carrying pending bytes is enqueued at most once via
 * {@link SseFrameBatcher#tryMarkEnqueued()} and re-enqueued only if pending bytes remain after the
 * tick clears its queue membership guard.
 */
public final class SseFrameCoalescer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SseFrameCoalescer.class.getName());

    /** Where a flushed batch goes: the per-request frame sink. */
    public interface Sink {
        void emit(byte[] batch);
    }

    private record Entry(SseFrameBatcher batcher, Sink sink) {
    }

    private final ScheduledExecutorService ticker;
    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();
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

    /** Offers a frame, emitting whatever is due right away; registers the batcher at most once while work is held. */
    public void offer(SseFrameBatcher batcher, Sink sink, byte[] frame) {
        byte[] immediate = batcher.offer(frame, System.nanoTime());
        if (immediate != null) {
            sink.emit(immediate);
        }
        if (batcher.hasPending() && batcher.tryMarkEnqueued()) {
            pending.add(new Entry(batcher, sink));
        }
    }

    /** Emits everything still buffered for a finishing or cancelled stream. */
    public void drain(SseFrameBatcher batcher, Sink sink) {
        byte[] rest = batcher.drain();
        if (rest != null) {
            sink.emit(rest);
        }
    }

    int pendingQueueSize() {
        return pending.size();
    }

    private void flushDue() {
        int size = pending.size();
        long now = System.nanoTime();
        for (int i = 0; i < size; i++) {
            Entry entry = pending.poll();
            if (entry == null) {
                return;
            }
            SseFrameBatcher batcher = entry.batcher();
            try {
                byte[] batch = batcher.poll(now);
                if (batch != null) {
                    entry.sink().emit(batch);
                }
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Coalesced flush failed", ex);
            } finally {
                // Clear membership guard first, then re-check hasPending() to avoid missed handoff
                // when offer() appends concurrently between poll() and clearEnqueued().
                batcher.clearEnqueued();
                if (batcher.hasPending() && batcher.tryMarkEnqueued()) {
                    pending.add(entry);
                }
            }
        }
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
