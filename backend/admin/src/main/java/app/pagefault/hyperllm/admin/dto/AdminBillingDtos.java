package app.pagefault.hyperllm.admin.dto;

import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.OrderType;
import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyKind;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyStatus;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.enums.VoucherStatus;
import app.pagefault.hyperllm.domain.model.enums.VoucherType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AdminBillingDtos {

    private AdminBillingDtos() {
    }

    public record CreatePackSkuRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull PackUnitType unitType,
            @NotEmpty List<String> applicableModels,
            @Positive long totalUnits,
            @Positive long priceMicros,
            @Positive int validDays,
            @NotNull PackSkuStatus status
    ) {
    }

    public record UpdatePackSkuRequest(
            @NotBlank String name,
            @NotNull PackUnitType unitType,
            @NotEmpty List<String> applicableModels,
            @Positive long totalUnits,
            @Positive long priceMicros,
            @Positive int validDays,
            @NotNull PackSkuStatus status,
            @NotNull Long version
    ) {
    }

    public record PackSkuAdminDto(
            UUID id,
            String code,
            String name,
            PackUnitType unitType,
            List<String> applicableModels,
            long totalUnits,
            long priceMicros,
            int validDays,
            PackSkuStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record CreateVoucherRequest(
            @NotBlank String name,
            @NotNull VoucherType voucherType,
            @Positive long amountMicros,
            List<String> applicableModels,
            Integer validDays,
            OffsetDateTime fixedExpiresAt,
            Integer maxRedemptionsTotal,
            @Positive int maxRedemptionsPerOrg
    ) {
    }

    public record VoucherAdminDto(
            UUID id,
            String name,
            VoucherType voucherType,
            long amountMicros,
            List<String> applicableModels,
            Integer validDays,
            OffsetDateTime fixedExpiresAt,
            Integer maxRedemptionsTotal,
            int maxRedemptionsPerOrg,
            VoucherStatus status,
            long version,
            OffsetDateTime createdAt
    ) {
    }

    public record UpdateVoucherStatusRequest(
            @NotNull VoucherStatus status,
            @NotNull Long version
    ) {
    }

    public record CreateCodeBatchRequest(@Positive @Max(10_000) int quantity) {
    }

    public record CodeBatchResponse(
            UUID batchId,
            UUID voucherId,
            int quantity,
            List<String> codes
    ) {
    }

    public record PaymentAnomalyDto(
            UUID id,
            UUID orgId,
            UUID orderId,
            PaymentChannel paymentChannel,
            PaymentAnomalyKind kind,
            PaymentAnomalyStatus status,
            String detailJson,
            OffsetDateTime createdAt
    ) {
    }

    public record ResolveAnomalyRequest(
            @NotNull PaymentAnomalyStatus status,
            @NotBlank String resolution
    ) {
    }

    public record RefundOrderRequest(
            @Positive long refundedAmountMinor,
            @NotBlank String note
    ) {
    }

    public record PaymentOrderAdminDto(
            UUID id,
            String orderNo,
            UUID orgId,
            OrderType orderType,
            PaymentChannel paymentChannel,
            String payCurrency,
            long payAmountMinor,
            long creditMicros,
            OrderStatus status,
            String externalTxId,
            UUID fulfillmentEventId,
            OffsetDateTime paidAt,
            OffsetDateTime refundedAt,
            long refundedAmountMinor,
            OffsetDateTime createdAt
    ) {
    }
}
