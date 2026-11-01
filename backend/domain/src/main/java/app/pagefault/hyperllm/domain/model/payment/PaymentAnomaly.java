package app.pagefault.hyperllm.domain.model.payment;

import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyKind;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyStatus;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A payment that cannot be settled automatically and needs an operator decision. */
public record PaymentAnomaly(
        UUID id,
        UUID orgId,
        UUID orderId,
        PaymentChannel paymentChannel,
        PaymentAnomalyKind kind,
        String detailJson,
        PaymentAnomalyStatus status,
        UUID resolvedByAdminId,
        OffsetDateTime resolvedAt,
        String resolution,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
