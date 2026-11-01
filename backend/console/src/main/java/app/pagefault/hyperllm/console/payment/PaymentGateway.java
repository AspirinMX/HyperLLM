package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;

import java.time.OffsetDateTime;
import java.util.Map;

/** One channel's protocol, signature and transaction lifecycle. */
public interface PaymentGateway {

    PaymentChannel channel();

    boolean enabled();

    /** Creates the payment surface. Idempotent at the channel by the merchant order number. */
    PaymentInstruction create(PaymentOrder order, String subject);

    /** Ask the channel for the current state; used by the return page and the compensation job. */
    PaymentQueryResult query(PaymentOrder order);

    /** Verifies and parses one callback. Throws when the signature or the envelope is invalid. */
    VerifiedPayment verify(Map<String, String> headers, String body);

    /** Best effort close at the channel; a failure is not a local failure. */
    default void close(PaymentOrder order) {
    }

    enum PaymentState {
        PENDING,
        PAID,
        CLOSED,
        FAILED,
        UNKNOWN
    }

    /**
     * @param action      URL the browser posts or navigates to, {@code null} for a code-only surface
     * @param method      POST or GET when a form is returned
     * @param fields      form fields, already signed
     * @param redirectUrl a redirect target, when the channel returns one
     * @param codeUrl     content for a QR code, when the channel returns one
     */
    record PaymentInstruction(
            String channelRef,
            String action,
            String method,
            Map<String, String> fields,
            String redirectUrl,
            String codeUrl,
            String rawJson
    ) {
        public PaymentInstruction {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    record PaymentQueryResult(
            PaymentState state,
            String externalTxId,
            long paidAmountMinor,
            String currency,
            OffsetDateTime paidAt,
            String rawJson
    ) {
    }

    /**
     * A callback whose signature and merchant identity have been verified. The order module still
     * has to match it against the local order; this record is only what the channel asserted.
     */
    record VerifiedPayment(
            String notificationId,
            String orderNo,
            String externalTxId,
            PaymentState state,
            long paidAmountMinor,
            String currency,
            OffsetDateTime paidAt,
            String rawJson
    ) {
    }
}
