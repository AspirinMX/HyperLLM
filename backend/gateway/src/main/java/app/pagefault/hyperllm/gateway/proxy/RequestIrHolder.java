package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit lifecycle holder for a decoded {@link CanonicalRequest}.
 *
 * <p>Keeping the IR behind a single clearable slot lets retry loops preserve full cross-protocol
 * re-encoding capability while the pre-first-byte retry window is open, and immediately drop the
 * entire IR graph once the retry window closes (last eligible attempt encoded, non-retryable
 * upstream response headers received, or stream cancelled/failed).
 */
final class RequestIrHolder {

    private final AtomicReference<CanonicalRequest> ref;

    RequestIrHolder(CanonicalRequest request) {
        this.ref = new AtomicReference<>(request);
    }

    /** Borrows the IR while additional retry attempts remain possible. */
    CanonicalRequest get() {
        return ref.get();
    }

    /** Takes ownership of the IR on the final possible attempt, clearing the slot immediately. */
    CanonicalRequest take() {
        return ref.getAndSet(null);
    }

    /** Releases the IR once the retry window closes (e.g., 2xx or non-retryable 4xx headers). */
    void release() {
        ref.lazySet(null);
    }

    boolean isPresent() {
        return ref.get() != null;
    }
}
