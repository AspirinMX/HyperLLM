package app.pagefault.hyperllm.common.idempotency;

/** What a completed idempotency key means for the next caller. */
public enum DuplicatePolicy {

    /** Reject the duplicate with {@code IDEMPOTENT_REPLAYED}. */
    REJECT,

    /** Return the stored result of the first call, without executing the method again. */
    REPLAY
}
