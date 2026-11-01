package app.pagefault.hyperllm.domain.model.enums;

/** What the confirmation path did with one verified channel notification. */
public enum PaymentNotificationOutcome {
    FULFILLED,
    DUPLICATE,
    MISMATCH,
    REJECTED,
    ERROR
}
