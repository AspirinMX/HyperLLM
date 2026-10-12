package app.pagefault.hyperllm.domain.billing;

/** Which bucket a request was charged against, in deduction order. */
public enum AllocationType {
    PACK,
    BONUS,
    CASH
}
