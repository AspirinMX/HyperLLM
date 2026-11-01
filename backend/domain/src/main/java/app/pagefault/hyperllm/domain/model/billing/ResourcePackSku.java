package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A purchasable pack definition; an order snapshots it before the first payment is created. */
public record ResourcePackSku(
        UUID id,
        String code,
        String name,
        PackUnitType unitType,
        List<String> applicableModels,
        long totalUnits,
        long priceMicros,
        int validDays,
        PackSkuStatus status,
        UUID createdByAdminId,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
