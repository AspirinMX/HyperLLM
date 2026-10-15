package app.pagefault.hyperllm.common.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process local store. Intended for tests and for single instance deployments; it gives no
 * protection across instances, so production wiring selects {@link RedisIdempotencyStore}.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Slot(String result, long expiresAtNanos) {
    }

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    @Override
    public AcquireStatus tryAcquire(String key, Duration inFlightTtl) {
        AtomicReference<AcquireStatus> observed = new AtomicReference<>();
        long now = System.nanoTime();
        slots.compute(key, (ignored, current) -> {
            if (current != null && current.expiresAtNanos() > now) {
                observed.set(current.result() == null ? AcquireStatus.IN_FLIGHT : AcquireStatus.COMPLETED);
                return current;
            }
            observed.set(AcquireStatus.ACQUIRED);
            return new Slot(null, now + inFlightTtl.toNanos());
        });
        return observed.get();
    }

    @Override
    public void complete(String key, String encodedResult, Duration resultTtl) {
        slots.put(key, new Slot(encodedResult, System.nanoTime() + resultTtl.toNanos()));
    }

    @Override
    public void release(String key) {
        slots.remove(key);
    }

    @Override
    public Optional<String> result(String key) {
        Slot slot = slots.get(key);
        if (slot == null) {
            return Optional.empty();
        }
        if (slot.expiresAtNanos() <= System.nanoTime()) {
            slots.remove(key, slot);
            return Optional.empty();
        }
        return Optional.ofNullable(slot.result());
    }

    /** Number of live keys, for tests and diagnostics. */
    public int size() {
        return slots.size();
    }
}
