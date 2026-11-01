package app.pagefault.hyperllm.domain.model.payment;

import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.OrderType;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A local order. Everything that decides what a payment buys is frozen here when it is created:
 * the currency, the operational rate, the credit or the pack spec. A later configuration change
 * cannot move an existing order.
 */
public record PaymentOrder(
        UUID id,
        String orderNo,
        UUID orgId,
        UUID createdByUserId,
        OrderType orderType,
        PaymentChannel paymentChannel,
        String payCurrency,
        long payAmountMinor,
        BigDecimal fxRate,
        long creditMicros,
        long invoiceableAmountMinor,
        UUID packSkuId,
        String packSpecJson,
        String requestFingerprint,
        String channelRef,
        String channelPayload,
        String externalTxId,
        String idempotencyKey,
        OrderStatus status,
        UUID fulfillmentEventId,
        OffsetDateTime fulfilledAt,
        OffsetDateTime paidAt,
        OffsetDateTime closedAt,
        String failureCode,
        String failureDetail,
        OffsetDateTime refundedAt,
        long refundedAmountMinor,
        OffsetDateTime expiresAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
