package app.pagefault.hyperllm.common.idempotency;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The framework-agnostic state machine shared by every idempotent call site.
 *
 * <p>Keeping it free of Spring and Redis is what allows the same semantics to be tested without a
 * container and reused by the blocking control plane and the reactive data plane alike.
 */
public final class IdempotencyExecutor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyExecutor.class);

    /** Encodes the guarded method's result so a replay can return it without re-execution. */
    public interface Codec<T> {
        String encode(T value);

        T decode(String raw);

        /** Codec for methods whose result is not replayed (void, or intentionally discarded). */
        static <T> Codec<T> none() {
            return new Codec<>() {
                @Override
                public String encode(T value) {
                    return "{}";
                }

                @Override
                public T decode(String raw) {
                    return null;
                }
            };
        }
    }

    /** A completed call plus whether its value came from the store instead of the method. */
    public record Outcome<T>(T value, boolean replayed) {
    }

    private final IdempotencyStore store;

    public IdempotencyExecutor(IdempotencyStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public <T> Outcome<T> execute(
            String key,
            DuplicatePolicy policy,
            Duration inFlightTtl,
            Duration resultTtl,
            Codec<T> codec,
            Supplier<T> action
    ) {
        AcquireStatus status = store.tryAcquire(key, inFlightTtl);
        return switch (status) {
            case ACQUIRED -> new Outcome<>(runAndComplete(key, resultTtl, codec, action), false);
            case IN_FLIGHT -> throw new HyperLlmException(ErrorCode.IDEMPOTENT_IN_FLIGHT);
            case COMPLETED -> replay(key, policy, codec);
        };
    }

    private <T> T runAndComplete(String key, Duration resultTtl, Codec<T> codec, Supplier<T> action) {
        T value;
        try {
            value = action.get();
        } catch (RuntimeException ex) {
            releaseQuietly(key);
            throw ex;
        }
        // A failed completion must not fail the call that already succeeded; the claim stays in
        // flight and expires on its own, which is the safe direction.
        try {
            store.complete(key, codec.encode(value), resultTtl);
        } catch (RuntimeException ex) {
            log.error("idempotency completion failed key={}", key, ex);
        }
        return value;
    }

    private <T> Outcome<T> replay(String key, DuplicatePolicy policy, Codec<T> codec) {
        if (policy == DuplicatePolicy.REJECT) {
            throw new HyperLlmException(ErrorCode.IDEMPOTENT_REPLAYED);
        }
        Optional<String> stored = store.result(key);
        if (stored.isEmpty()) {
            // Completed but no replayable payload: treat exactly like an in-flight claim, since the
            // first call's effect is already recorded and must not be repeated.
            throw new HyperLlmException(ErrorCode.IDEMPOTENT_IN_FLIGHT);
        }
        return new Outcome<>(codec.decode(stored.get()), true);
    }

    private void releaseQuietly(String key) {
        try {
            store.release(key);
        } catch (RuntimeException ex) {
            log.error("idempotency release failed key={}", key, ex);
        }
    }
}
