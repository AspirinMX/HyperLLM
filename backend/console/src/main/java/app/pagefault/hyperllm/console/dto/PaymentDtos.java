package app.pagefault.hyperllm.console.dto;

import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.OrderType;
import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreatePaymentOrderRequest(
            @NotNull OrderType orderType,
            @NotNull PaymentChannel paymentChannel,
            @Positive Long payAmountMinor,
            UUID packSkuId
    ) {
    }

    public record PaymentInstructionDto(
            String action,
            String method,
            Map<String, String> fields,
            String redirectUrl,
            String codeUrl
    ) {
    }

    /**
     * @param paymentStatus     the order state
     * @param fulfillmentStatus NONE before payment, PENDING after the grant was authorized but not
     *                          yet folded, APPLIED once the ledger row exists
     */
    public record PaymentOrderDto(
            UUID id,
            String orderNo,
            OrderType orderType,
            PaymentChannel paymentChannel,
            String payCurrency,
            long payAmountMinor,
            String fxRate,
            long creditMicros,
            JsonNode packSpec,
            OrderStatus paymentStatus,
            String fulfillmentStatus,
            UUID fulfillmentEventId,
            PaymentInstructionDto instruction,
            OffsetDateTime paidAt,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt
    ) {
    }

    public record PackSkuDto(
            UUID id,
            String code,
            String name,
            PackUnitType unitType,
            List<String> applicableModels,
            long totalUnits,
            long priceMicros,
            int validDays,
            PackSkuStatus status,
            long version
    ) {
    }

    public record RedeemVoucherRequest(@NotBlank String code) {
    }

    public record RedemptionDto(
            UUID grantEventId,
            long amountMicros,
            List<String> applicableModels,
            OffsetDateTime expiresAt
    ) {
    }
}
