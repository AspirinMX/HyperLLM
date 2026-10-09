package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.GrantSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record BonusGrant(
        UUID id,
        UUID orgId,
        GrantSource grantSource,
        UUID voucherId,
        UUID redemptionCodeId,
        List<String> applicableModels,
        long initialMicros,
        long remainingMicros,
        OffsetDateTime expiresAt,
        EntitlementStatus status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
