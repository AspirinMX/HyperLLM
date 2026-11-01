package app.pagefault.hyperllm.domain.model.enums;

/** Why a payment needs an operator to look at it. */
public enum PaymentAnomalyKind {
    SIGNATURE,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    ORDER_MISMATCH,
    TRANSACTION_CONFLICT,
    UNMATCHED_PAYMENT,
    CHANNEL_CONFLICT
}
