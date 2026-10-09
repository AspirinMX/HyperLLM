package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.OverrideMode;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrgModelPricingOverride(
        UUID id,
        UUID orgId,
        UUID virtualModelId,
        OverrideMode overrideMode,
        Integer discountRatioBps,
        String fixedPricingJson,
        OffsetDateTime effectiveFrom,
        OffsetDateTime effectiveTo,
        ToggleStatus status,
        UUID createdByAdminId,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
