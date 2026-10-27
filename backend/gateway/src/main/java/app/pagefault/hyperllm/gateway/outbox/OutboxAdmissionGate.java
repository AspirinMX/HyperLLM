package app.pagefault.hyperllm.gateway.outbox;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.gateway.billing.BillingAdmissionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Refuses new reservations on this gateway while its billing writer is overloaded. */
@Component
public class OutboxAdmissionGate {

    private static final int WINDOW_SECONDS = 60;
    private static final int MIN_ATTEMPTED_ROWS = 50;
    private static final long RETRY_AFTER_SECONDS = 5;

    private final OutboxQueue queue;
    private final LongSupplier nanoTime;
    private final long[] seconds = new long[WINDOW_SECONDS];
    private final long[] attempts = new long[WINDOW_SECONDS];
    private final long[] failures = new long[WINDOW_SECONDS];
    private volatile Long lastFailureSecond;

    @Autowired
    public OutboxAdmissionGate(OutboxQueue queue) {
        this(queue, System::nanoTime);
    }

    OutboxAdmissionGate(OutboxQueue queue, LongSupplier nanoTime) {
        this.queue = queue;
        this.nanoTime = nanoTime;
    }

    public void check() {
        long now = currentSecond();
        Long lastFailure = lastFailureSecond;
        if (queue.admissionPaused()
                || (lastFailure != null && now - lastFailure < WINDOW_SECONDS && failureRateExceeded(now))) {
            throw new BillingAdmissionException(ErrorCode.OUTBOX_UNAVAILABLE, RETRY_AFTER_SECONDS);
        }
    }

    synchronized void recordWrite(int rows, boolean succeeded) {
        long second = currentSecond();
        int slot = Math.floorMod(second, WINDOW_SECONDS);
        if (seconds[slot] != second) {
            seconds[slot] = second;
            attempts[slot] = 0;
            failures[slot] = 0;
        }
        attempts[slot] += rows;
        if (!succeeded) {
            failures[slot] += rows;
            lastFailureSecond = second;
        }
    }

    private synchronized boolean failureRateExceeded(long now) {
        long attempted = 0;
        long failed = 0;
        for (int slot = 0; slot < WINDOW_SECONDS; slot++) {
            if (now - seconds[slot] < WINDOW_SECONDS) {
                attempted += attempts[slot];
                failed += failures[slot];
            }
        }
        return attempted >= MIN_ATTEMPTED_ROWS && failed > attempted / 100;
    }

    private long currentSecond() {
        return Math.floorDiv(nanoTime.getAsLong(), TimeUnit.SECONDS.toNanos(1));
    }
}
