package app.pagefault.hyperllm.common.idempotency;

/** Outcome of claiming an idempotency key. */
public enum AcquireStatus {

    /** The caller now owns the key and must execute the method. */
    ACQUIRED,

    /** Another caller owns the key and has not finished yet. */
    IN_FLIGHT,

    /** The key has already been completed; the stored result may be replayable. */
    COMPLETED
}
