package app.pagefault.hyperllm.domain.model.payment;

import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.enums.PaymentNotificationOutcome;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Durable evidence of one verified channel notification. */
public record PaymentNotification(
        UUID id,
        PaymentChannel paymentChannel,
        String notificationId,
        String orderNo,
        String externalTxId,
        PaymentNotificationOutcome outcome,
        String detailJson,
        OffsetDateTime receivedAt,
        OffsetDateTime processedAt
) {
}
