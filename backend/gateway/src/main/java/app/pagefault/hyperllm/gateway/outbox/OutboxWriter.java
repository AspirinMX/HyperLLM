package app.pagefault.hyperllm.gateway.outbox;

import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    private static final long MAX_EVENT_AGE_SECONDS = 14 * 60;

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
            @Qualifier("gatewayOutboxRepository") OutboxEventRepository repository,
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

    private void flush(List<PendingMoneyEvent> batch) throws InterruptedException {
        List<OutboxEvent> events = batch.stream().map(this::toRow).toList();
        long maxAgeSeconds = Math.max(0, Math.min(settings.getRetryDeadlineSeconds(), MAX_EVENT_AGE_SECONDS));
        while (running && !events.isEmpty()) {
            Instant cutoff = Instant.now().minusSeconds(maxAgeSeconds);
            var current = events.stream().filter(event -> !event.createdAt().toInstant().isBefore(cutoff)).toList();
            int expired = events.size() - current.size();
            if (expired > 0) {
                abandon(expired);
                events = current;
                if (events.isEmpty()) {
                    return;
                }
            }
            try {
                int written = repository.insertIfAbsent(events);
                admissionGate.recordWrite(events.size(), true);
                inserted.addAndGet(written);
                return;
            } catch (RuntimeException ex) {
                admissionGate.recordWrite(events.size(), false);
                failures.incrementAndGet();
                log.debug("Retrying {} settlement appends", events.size(), ex);
                Thread.sleep(settings.getRetryBackoffMs());
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

    private void abandon(int count) {
        abandoned.addAndGet(count);
        queue.recordDropped(count);
        io.micrometer.core.instrument.MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter("gateway_outbox_abandoned_total").increment(count);
        }
        log.error("Leaving {} outbox rows for reconciliation after the gateway deadline", count);
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
