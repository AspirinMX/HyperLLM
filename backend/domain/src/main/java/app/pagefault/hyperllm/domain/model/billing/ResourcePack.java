package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ResourcePack(
        UUID id,
        UUID orgId,
        String packName,
        PackUnitType unitType,
        List<String> applicableModels,
        long totalUnits,
        long remainingUnits,
        UUID purchaseOrderId,
        OffsetDateTime expiresAt,
        EntitlementStatus status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
