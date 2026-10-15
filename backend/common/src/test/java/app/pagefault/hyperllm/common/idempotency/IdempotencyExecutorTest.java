package app.pagefault.hyperllm.common.idempotency;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyExecutorTest {

    private static final Duration IN_FLIGHT = Duration.ofSeconds(30);
    private static final Duration RESULT_TTL = Duration.ofMinutes(5);

    private static final IdempotencyExecutor.Codec<String> TEXT = new IdempotencyExecutor.Codec<>() {
        @Override
        public String encode(String value) {
            return value;
        }

        @Override
        public String decode(String raw) {
            return raw;
        }
    };

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final IdempotencyExecutor executor = new IdempotencyExecutor(store);
    private final AtomicInteger executions = new AtomicInteger();

    @Test
    void executesOnceAndReplaysTheStoredResult() {
        String first = executor
                .execute("k", DuplicatePolicy.REPLAY, IN_FLIGHT, RESULT_TTL, TEXT, this::charge)
                .value();
        String second = executor
                .execute("k", DuplicatePolicy.REPLAY, IN_FLIGHT, RESULT_TTL, TEXT, this::charge)
                .value();

        assertThat(first).isEqualTo("charged-1");
        assertThat(second).isEqualTo("charged-1");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void rejectsTheSecondCallWhenPolicyIsReject() {
        executor.execute("k", DuplicatePolicy.REJECT, IN_FLIGHT, RESULT_TTL, TEXT, this::charge);

        assertThatThrownBy(() -> executor.execute(
                "k", DuplicatePolicy.REJECT, IN_FLIGHT, RESULT_TTL, TEXT, this::charge))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_REPLAYED);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void rejectsWhileTheClaimIsStillInFlight() {
        assertThat(store.tryAcquire("k", IN_FLIGHT)).isEqualTo(AcquireStatus.ACQUIRED);

        assertThatThrownBy(() -> executor.execute(
                "k", DuplicatePolicy.REPLAY, IN_FLIGHT, RESULT_TTL, TEXT, this::charge))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_IN_FLIGHT);
        assertThat(executions.get()).isZero();
    }

    @Test
    void releasesTheClaimWhenTheGuardedCallFails() {
        assertThatThrownBy(() -> executor.execute(
                "k", DuplicatePolicy.REJECT, IN_FLIGHT, RESULT_TTL, TEXT, () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        String retried = executor
                .execute("k", DuplicatePolicy.REJECT, IN_FLIGHT, RESULT_TTL, TEXT, this::charge)
                .value();
        assertThat(retried).isEqualTo("charged-1");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void treatsACompletedKeyWithoutPayloadAsInFlightRatherThanReExecuting() {
        store.tryAcquire("k", IN_FLIGHT);
        store.complete("k", null, RESULT_TTL);

        assertThatThrownBy(() -> executor.execute(
                "k", DuplicatePolicy.REPLAY, IN_FLIGHT, RESULT_TTL, TEXT, this::charge))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_IN_FLIGHT);
        assertThat(executions.get()).isZero();
    }

    private String charge() {
        return "charged-" + executions.incrementAndGet();
    }
}
