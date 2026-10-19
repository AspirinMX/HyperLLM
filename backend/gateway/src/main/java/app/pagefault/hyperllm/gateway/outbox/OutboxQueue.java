package app.pagefault.hyperllm.gateway.outbox;

import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded hand-off between the request thread and the single outbox writer.
 *
 * <p>The request path only ever appends here: no database call, no lock, no waiting. When the queue
 * is full the caller learns about it instead of blocking, because a growing backlog has to turn into
 * a refused admission rather than a slower request.
 */
@Component
public class OutboxQueue {

    private final BlockingQueue<PendingMoneyEvent> queue;
    private final int capacity;
    private final AtomicLong dropped = new AtomicLong();

    public OutboxQueue(GatewayProperties properties) {
        this.capacity = properties.getOutbox().getQueueCapacity();
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** @return false when the queue is full and the caller must decide what to do with the event */
    public boolean offer(PendingMoneyEvent event) {
        return queue.offer(event);
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
        return batch;
    }

    public int depth() {
        return queue.size();
    }

    public double saturation() {
        return capacity == 0 ? 0d : (double) queue.size() / capacity;
    }

    public long droppedCount() {
        return dropped.get();
    }

    void recordDropped(int count) {
        dropped.addAndGet(count);
    }
}
