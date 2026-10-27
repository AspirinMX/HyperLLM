package app.pagefault.hyperllm.gateway.outbox;

import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The only thread that writes settlement rows, and the only place that decides an event is lost.
 *
 * <p>One writer keeps the inserts sequential: concurrent appends contend for the right edge of the
 * primary-key btree and for WAL locks, and the table is designed as an append-only log. A batch is
 * retried with backoff for as long as the failure could be transient; only when the retry deadline
 * passes is the batch abandoned, and that is counted rather than swallowed, because an abandoned row
 * is exactly the window the mirror copy and the re-delivery job exist to cover.
 */
@Slf4j
@Component
public class OutboxWriter implements SmartLifecycle {

    private final OutboxQueue queue;
    private final OutboxEventRepository repository;
    private final OutboxAdmissionGate admissionGate;
    private final GatewayProperties.Outbox settings;
    private final ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry;
    private final AtomicLong inserted = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();

    private volatile boolean running;
    private Thread worker;

    public OutboxWriter(
            OutboxQueue queue,
            OutboxEventRepository repository,
            OutboxAdmissionGate admissionGate,
            GatewayProperties properties,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry
    ) {
        this.queue = queue;
        this.repository = repository;
        this.admissionGate = admissionGate;
        this.settings = properties.getOutbox();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        io.micrometer.core.instrument.MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.gauge("gateway_outbox_queue_depth", queue, OutboxQueue::depth);
        }
        running = true;
        worker = Thread.ofPlatform().name("outbox-writer").daemon(true).start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                List<PendingMoneyEvent> batch = queue.takeBatch(settings.getMaxBatchRows(), settings.getFlushIntervalMs());
                if (!batch.isEmpty()) {
                    flush(batch);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.error("outbox writer loop failed", ex);
            }
        }
    }

    private void flush(List<PendingMoneyEvent> batch) {
        List<OutboxEvent> events = batch.stream()
                .map(this::toRow)
                .toList();
        long deadline = System.currentTimeMillis() + settings.getRetryDeadlineSeconds() * 1000L;
        while (true) {
            try {
                int written = repository.insertIfAbsent(events);
                admissionGate.recordWrite(events.size(), true);
                inserted.addAndGet(written);
                return;
            } catch (RuntimeException ex) {
                admissionGate.recordWrite(events.size(), false);
                failures.incrementAndGet();
                if (System.currentTimeMillis() >= deadline) {
                    abandoned.addAndGet(events.size());
                    queue.recordDropped(events.size());
                    io.micrometer.core.instrument.MeterRegistry registry = meterRegistry.getIfAvailable();
                    if (registry != null) {
                        registry.counter("gateway_outbox_abandoned_total").increment(events.size());
                    }
                    log.error("abandoning {} outbox rows after the retry deadline", events.size(), ex);
                    return;
                }
                sleep(settings.getRetryBackoffMs());
            }
        }
    }

    private OutboxEvent toRow(PendingMoneyEvent event) {
        return new OutboxEvent(
                event.eventId(),
                settings.getAggregateType(),
                event.orgId(),
                event.eventType(),
                event.payload(),
                event.occurredAt());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Duration.ofMillis(millis).toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public long insertedCount() {
        return inserted.get();
    }

    public long failureCount() {
        return failures.get();
    }

    public long abandonedCount() {
        return abandoned.get();
    }
}
