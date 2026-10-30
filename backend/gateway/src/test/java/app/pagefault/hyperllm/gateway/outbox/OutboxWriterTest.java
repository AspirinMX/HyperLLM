package app.pagefault.hyperllm.gateway.outbox;

import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxWriterTest {

    private final RecordingRepository repository = new RecordingRepository();
    private final GatewayProperties properties = new GatewayProperties();

    @Test
    void writesEverythingQueuedInABatch() {
        properties.getOutbox().setFlushIntervalMs(20);
        OutboxQueue queue = new OutboxQueue(properties);
        OutboxWriter writer = newWriter(queue);
        writer.start();
        try {
            for (int i = 0; i < 5; i++) {
                queue.offer(event("e" + i));
            }
            awaitRows(5);
            // The writer flushes as soon as it has anything, so the split across batches is not
            // fixed; what matters is that every row lands and no batch exceeds the bound.
            assertThat(repository.rows()).isEqualTo(5);
            assertThat(repository.batches()).isNotEmpty();
            assertThat(repository.batches()).allSatisfy(batch ->
                    assertThat(batch.size()).isLessThanOrEqualTo(properties.getOutbox().getMaxBatchRows()));
        } finally {
            writer.stop();
        }
    }

    @Test
    void retriesAFailingInsertUntilItSucceeds() {
        properties.getOutbox().setFlushIntervalMs(10);
        properties.getOutbox().setRetryBackoffMs(10);
        repository.failuresBeforeSuccess.set(2);
        OutboxQueue queue = new OutboxQueue(properties);
        OutboxWriter writer = newWriter(queue);
        writer.start();
        try {
            queue.offer(event("e1"));
            awaitRows(1);
            assertThat(writer.failureCount()).isEqualTo(2);
            assertThat(writer.abandonedCount()).isZero();
        } finally {
            writer.stop();
        }
    }

    @Test
    void abandonsAndCountsRowsOnceTheDeadlinePasses() {
        properties.getOutbox().setFlushIntervalMs(10);
        properties.getOutbox().setRetryBackoffMs(1);
        properties.getOutbox().setRetryDeadlineSeconds(10);
        OutboxQueue queue = new OutboxQueue(properties);
        OutboxWriter writer = newWriter(queue);
        writer.start();
        try {
            OffsetDateTime expired = OffsetDateTime.now().minusSeconds(11);
            queue.offer(new PendingMoneyEvent(UUID.randomUUID(), "org-1", "CONSUME", "{}", expired));
            queue.offer(new PendingMoneyEvent(UUID.randomUUID(), "org-1", "CONSUME", "{}", expired));
            awaitCondition(() -> writer.abandonedCount() == 2);
            assertThat(repository.rows()).isZero();
            assertThat(writer.abandonedCount()).isEqualTo(2);
            assertThat(queue.droppedCount()).isEqualTo(2);
        } finally {
            writer.stop();
        }
    }

    @Test
    void refusesToQueueBeyondItsCapacity() {
        properties.getOutbox().setQueueCapacity(2);
        OutboxQueue queue = new OutboxQueue(properties);

        assertThat(queue.offer(event("a"))).isTrue();
        assertThat(queue.offer(event("b"))).isTrue();
        assertThat(queue.offer(event("c"))).isFalse();
        assertThat(queue.saturation()).isEqualTo(1d);
    }

    private OutboxWriter newWriter(OutboxQueue queue) {
        return new OutboxWriter(
                queue,
                repository,
                new OutboxAdmissionGate(queue),
                properties,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    }

    private PendingMoneyEvent event(String key) {
        return new PendingMoneyEvent(UUID.randomUUID(), "org-1", "CONSUME", key, OffsetDateTime.now());
    }

    private void awaitRows(int expected) {
        awaitCondition(() -> repository.rows() >= expected);
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private final class RecordingRepository implements OutboxEventRepository {

        private final List<List<PendingMoneyEvent>> batches = new CopyOnWriteArrayList<>();
        private final AtomicInteger rows = new AtomicInteger();
        private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();

        List<List<PendingMoneyEvent>> batches() {
            return batches;
        }

        int rows() {
            return rows.get();
        }

        @Override
        public boolean insertIfAbsent(OutboxEvent event) {
            return insertIfAbsent(List.of(event)) == 1;
        }

        @Override
        public int insertIfAbsent(List<OutboxEvent> events) {
            if (failuresBeforeSuccess.getAndDecrement() > 0) {
                throw new IllegalStateException("database unavailable");
            }
            rows.addAndGet(events.size());
            List<PendingMoneyEvent> batch = new ArrayList<>();
            for (OutboxEvent event : events) {
                batch.add(new PendingMoneyEvent(event.id(), event.aggregateId(), event.type(), event.payload(), event.createdAt()));
            }
            batches.add(batch);
            return events.size();
        }

        @Override
        public boolean exists(UUID eventId) {
            return false;
        }

        @Override
        public Optional<UUID> watermark() {
            return Optional.empty();
        }

        @Override
        public Optional<OffsetDateTime> watermarkUpdatedAt() {
            return Optional.empty();
        }

        @Override
        public void advanceWatermark(UUID eventId) {
        }

        @Override
        public List<OutboxEvent> listUnconfirmedCredits(OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public boolean unconfirmedCreditsFit(UUID orgId,
                app.pagefault.hyperllm.common.messaging.BillingMoneyEvent.EventType type, long capacityMicros) {
            return true;
        }

        @Override
        public int deleteUpTo(UUID watermark, int limit) {
            return 0;
        }

        @Override
        public int deleteOlderThan(OffsetDateTime cutoff) {
            return 0;
        }
    }
}
