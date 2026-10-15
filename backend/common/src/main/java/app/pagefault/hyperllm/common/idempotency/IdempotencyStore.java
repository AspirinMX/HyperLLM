package app.pagefault.hyperllm.common.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * Backing store for the idempotency guard.
 *
 * <p>Implementations must make {@link #tryAcquire} atomic: two concurrent callers with the same key
 * may never both observe {@link AcquireStatus#ACQUIRED}.
 */
public interface IdempotencyStore {

    AcquireStatus tryAcquire(String key, Duration inFlightTtl);

    /** Marks the key completed and stores the value a {@link DuplicatePolicy#REPLAY} call returns. */
    void complete(String key, String encodedResult, Duration resultTtl);

    /** Drops the claim so a retry may execute again. Called when the guarded method threw. */
    void release(String key);

    /** The stored result of a completed key, if it has not expired. */
    Optional<String> result(String key);
}
