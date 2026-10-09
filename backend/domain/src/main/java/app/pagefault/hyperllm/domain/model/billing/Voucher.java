package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.VoucherStatus;
import app.pagefault.hyperllm.domain.model.enums.VoucherType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Voucher(
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
        UUID createdByAdminId,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
