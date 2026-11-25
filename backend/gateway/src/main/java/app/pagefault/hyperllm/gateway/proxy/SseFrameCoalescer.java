package app.pagefault.hyperllm.gateway.proxy;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link SseFrameBatcher} instances from one shared tick.
 *
 * <p>Batching needs a deadline, but a timer per connection would cost more than the writes it saves
 * (2,000 streams x 200 ticks/s). Instead every batcher that holds frames is registered once, a single
 * daemon thread polls them, and the entry is re-registered only while it still has work.
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

    /** Offers a frame, emitting whatever is due right away; registers the request while work is held. */
    public void offer(SseFrameBatcher batcher, Sink sink, byte[] frame) {
        byte[] immediate = batcher.offer(frame, System.nanoTime());
        if (immediate != null) {
            sink.emit(immediate);
        }
        if (batcher.hasPending()) {
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

    private void flushDue() {
        int size = pending.size();
        long now = System.nanoTime();
        for (int i = 0; i < size; i++) {
            Entry entry = pending.poll();
            if (entry == null) {
                return;
            }
            try {
                byte[] batch = entry.batcher().poll(now);
                if (batch != null) {
                    entry.sink().emit(batch);
                }
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Coalesced flush failed", ex);
            } finally {
                if (entry.batcher().hasPending()) {
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
