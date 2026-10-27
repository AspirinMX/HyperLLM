package app.pagefault.hyperllm.gateway.outbox;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.gateway.billing.BillingAdmissionException;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OutboxAdmissionGateTest {

    private static final PendingMoneyEvent EVENT =
            new PendingMoneyEvent(UUID.randomUUID(), "org", "CONSUME", "{}", OffsetDateTime.now());

    private final AtomicLong now = new AtomicLong();
    private final OutboxQueue queue;
    private final OutboxAdmissionGate gate;

    OutboxAdmissionGateTest() {
        var properties = new GatewayProperties();
        properties.getOutbox().setQueueCapacity(4);
        queue = new OutboxQueue(properties, now::get);
        gate = new OutboxAdmissionGate(queue, now::get);
    }

    @Test
    void refusesImmediatelyWhenFullAndRecoversAfterDrain() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            queue.offer(EVENT);
        }
        assertPaused();
        queue.takeBatch(2, 0);
        gate.check();
    }

    @Test
    void requiresContinuousPressureAboveTheThreshold() throws InterruptedException {
        queue.offer(EVENT);
        queue.offer(EVENT);
        queue.offer(EVENT);
        advance(29);
        gate.check();
        queue.takeBatch(1, 0);
        queue.offer(EVENT);
        advance(29);
        gate.check();
        advance(1);
        assertPaused();
        queue.takeBatch(1, 0);
        gate.check();
    }

    @Test
    void failureRatioRequiresVolumeAndDecaysAutomatically() {
        gate.recordWrite(1, false);
        gate.recordWrite(48, true);
        gate.check();
        gate.recordWrite(1, true);
        assertPaused();
        gate.recordWrite(50, true);
        gate.check();
        advance(1);
        gate.recordWrite(50, false);
        assertPaused();
        advance(60);
        gate.check();
    }

    private void advance(long seconds) {
        now.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private void assertPaused() {
        assertThatExceptionOfType(BillingAdmissionException.class)
                .isThrownBy(gate::check)
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.OUTBOX_UNAVAILABLE);
                    assertThat(ex.getRetryAfterSeconds()).isPositive();
                });
    }
}
