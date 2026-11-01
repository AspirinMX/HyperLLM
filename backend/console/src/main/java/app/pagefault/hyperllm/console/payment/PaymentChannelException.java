package app.pagefault.hyperllm.console.payment;

/** A channel call or notification that cannot be used as evidence. */
public class PaymentChannelException extends RuntimeException {

    public PaymentChannelException(String message) {
        super(message);
    }

    public PaymentChannelException(String message, Throwable cause) {
        super(message, cause);
    }
}
