package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.RedemptionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RedemptionCode(
        UUID id,
        UUID voucherId,
        String codeHash,
        String codeHint,
        UUID redeemedOrgId,
        UUID redeemedByUserId,
        OffsetDateTime redeemedAt,
        OffsetDateTime expiresAt,
        RedemptionStatus status,
        OffsetDateTime createdAt
) {
}
