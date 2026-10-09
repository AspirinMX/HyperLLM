package app.pagefault.hyperllm.domain.model.payment;

import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.OrderType;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

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
        String packSpecJson,
        String externalTxId,
        String idempotencyKey,
        OrderStatus status,
        OffsetDateTime paidAt,
        OffsetDateTime expiresAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
