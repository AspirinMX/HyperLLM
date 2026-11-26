package app.pagefault.hyperllm.gateway.outbox;

import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Bounded hand-off between the request thread and the single outbox writer.
 *
 * <p>Offers never wait for free space. The writer and new admissions observe the same local queue
 * pressure, including a full queue that has yet to reach the sustained-pressure window.
 */
@Component
public class OutboxQueue {

    private static final long NOT_ABOVE_THRESHOLD = Long.MIN_VALUE;
    private static final long SUSTAINED_PRESSURE_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final BlockingQueue<PendingMoneyEvent> queue;
    private final int capacity;
    private final AtomicLong dropped = new AtomicLong();
    private final double pauseRatio;
    private final LongSupplier nanoTime;
    private final AtomicLong aboveThresholdSinceNanos = new AtomicLong(NOT_ABOVE_THRESHOLD);

    @Autowired
    public OutboxQueue(GatewayProperties properties) {
        this(properties, System::nanoTime);
    }

    OutboxQueue(GatewayProperties properties, LongSupplier nanoTime) {
        this.capacity = properties.getOutbox().getQueueCapacity();
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.pauseRatio = properties.getOutbox().getAdmissionPauseRatio();
        this.nanoTime = nanoTime;
    }

    /** @return false when the queue is full and the caller must decide what to do with the event */
    public boolean offer(PendingMoneyEvent event) {
        boolean accepted = queue.offer(event);
        admissionPaused();
        return accepted;
    }

    /**
     * Waits up to {@code waitMs} for one element and then takes whatever else is immediately
     * available, up to {@code max}.
     */
    public List<PendingMoneyEvent> takeBatch(int max, long waitMs) throws InterruptedException {
        PendingMoneyEvent first = queue.poll(waitMs, TimeUnit.MILLISECONDS);
        if (first == null) {
            return List.of();
        }
        List<PendingMoneyEvent> batch = new ArrayList<>(Math.min(max, 256));
        batch.add(first);
        queue.drainTo(batch, max - 1);
        admissionPaused();
        return batch;
    }

    public int depth() {
        return queue.size();
    }

    public double saturation() {
        return capacity == 0 ? 0d : (double) queue.size() / capacity;
    }

    boolean admissionPaused() {
        int depth = queue.size();
        if (capacity > 0 && (double) depth / capacity <= pauseRatio && depth < capacity) {
            if (aboveThresholdSinceNanos.get() != NOT_ABOVE_THRESHOLD) {
                aboveThresholdSinceNanos.set(NOT_ABOVE_THRESHOLD);
            }
            return false;
        }
        long now = nanoTime.getAsLong();
        if (now == NOT_ABOVE_THRESHOLD) {
            now = NOT_ABOVE_THRESHOLD + 1L;
        }
        long since = aboveThresholdSinceNanos.get();
        if (since == NOT_ABOVE_THRESHOLD) {
            if (aboveThresholdSinceNanos.compareAndSet(NOT_ABOVE_THRESHOLD, now)) {
                since = now;
            } else {
                long updated = aboveThresholdSinceNanos.get();
                since = (updated == NOT_ABOVE_THRESHOLD) ? now : updated;
            }
        }
        return depth == capacity || now - since >= SUSTAINED_PRESSURE_NANOS;
    }

    public long droppedCount() {
        return dropped.get();
    }

    void recordDropped(int count) {
        dropped.addAndGet(count);
    }
}
