package app.pagefault.hyperllm.domain.model.tenant;

import app.pagefault.hyperllm.domain.model.enums.OrgStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Organization(
        UUID id,
        String name,
        String slug,
        UUID ownerUserId,
        UUID tierId,
        Integer discountRatioBps,
        int maxConcurrency,
        boolean personal,
        OrgStatus status,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
