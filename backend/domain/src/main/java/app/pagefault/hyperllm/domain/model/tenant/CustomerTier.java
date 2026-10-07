package app.pagefault.hyperllm.domain.model.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CustomerTier(
        UUID id,
        String tierCode,
        String displayName,
        int defaultDiscountBps,
        int defaultOrgConcurrency,
        String description,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
