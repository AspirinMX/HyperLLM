package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.idempotency.DuplicatePolicy;
import app.pagefault.hyperllm.common.idempotency.Idempotent;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyKind;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.enums.PaymentNotificationOutcome;
import app.pagefault.hyperllm.domain.model.payment.PaymentNotification;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import app.pagefault.hyperllm.domain.repository.PaymentNotificationRepository;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Matches a verified notification to its order and drives the confirmation transaction. */
@Service
@RequiredArgsConstructor
public class PaymentConfirmationService {

    public enum Ack { ACCEPTED, DUPLICATE, IGNORED, MISMATCH }

    private final PaymentOrderRepository orders;
    private final PaymentNotificationRepository notifications;
    private final PaymentConfirmationTx confirmation;
    private final PaymentAnomalyWriter anomalies;

    @Idempotent(namespace = "payment-confirm", key = "#p1.name() + ':' + #p0.notificationId()",
            onDuplicate = DuplicatePolicy.REPLAY, failOpen = true,
            inFlightSeconds = 120, resultSeconds = 86400)
    public Ack confirm(PaymentGateway.VerifiedPayment verified, PaymentChannel channel) {
        if (verified.notificationId() == null || verified.notificationId().isBlank()) {
            throw new HyperLlmException(ErrorCode.PAYMENT_VERIFICATION_FAILED,
                    "The notification has no identity");
        }
        if (verified.state() != PaymentGateway.PaymentState.PAID) {
            record(verified, channel, PaymentNotificationOutcome.REJECTED, null);
            return Ack.IGNORED;
        }
        PaymentOrder order = orders.findByOrderNo(verified.orderNo()).orElse(null);
        if (order == null) {
            anomalies.record(null, null, channel, PaymentAnomalyKind.UNMATCHED_PAYMENT,
                    detail(verified, "No local order for this notification"));
            record(verified, channel, PaymentNotificationOutcome.MISMATCH, null);
            return Ack.MISMATCH;
        }
        if (order.paymentChannel() != channel) {
            anomalies.record(order.orgId(), order.id(), channel, PaymentAnomalyKind.ORDER_MISMATCH,
                    detail(verified, "The order belongs to another channel"));
            record(verified, channel, PaymentNotificationOutcome.MISMATCH, order.orderNo());
            return Ack.MISMATCH;
        }
        if (verified.paidAmountMinor() != order.payAmountMinor()
                || !order.payCurrency().equalsIgnoreCase(verified.currency())) {
            anomalies.record(order.orgId(), order.id(), channel, PaymentAnomalyKind.AMOUNT_MISMATCH,
                    detail(verified, "The paid amount or currency does not match the order"));
            record(verified, channel, PaymentNotificationOutcome.MISMATCH, order.orderNo());
            return Ack.MISMATCH;
        }
        try {
            PaymentConfirmationTx.Result result = confirmation.confirm(order.id(), verified);
            if (result.outcome() == PaymentConfirmationTx.Compensation.DUPLICATE) {
                record(verified, channel, PaymentNotificationOutcome.DUPLICATE, order.orderNo());
                return Ack.DUPLICATE;
            }
            record(verified, channel, PaymentNotificationOutcome.FULFILLED, order.orderNo());
            return Ack.ACCEPTED;
        } catch (HyperLlmException ex) {
            anomalies.record(order.orgId(), order.id(), channel, PaymentAnomalyKind.CHANNEL_CONFLICT,
                    detail(verified, ex.getMessage()));
            record(verified, channel, PaymentNotificationOutcome.MISMATCH, order.orderNo());
            return Ack.MISMATCH;
        }
    }

    private void record(PaymentGateway.VerifiedPayment verified, PaymentChannel channel,
                        PaymentNotificationOutcome outcome, String orderNo) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        notifications.insertIfAbsent(new PaymentNotification(
                UuidV7.next(), channel, verified.notificationId(), orderNo,
                verified.externalTxId(), outcome, null, now, now));
    }

    private static Map<String, Object> detail(PaymentGateway.VerifiedPayment verified, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("orderNo", verified.orderNo());
        detail.put("externalTxId", verified.externalTxId());
        detail.put("amountMinor", verified.paidAmountMinor());
        detail.put("currency", verified.currency());
        return detail;
    }
}
