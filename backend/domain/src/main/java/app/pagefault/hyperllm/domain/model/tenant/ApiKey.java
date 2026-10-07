package app.pagefault.hyperllm.domain.model.tenant;

import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ApiKey(
        UUID id,
        UUID projectId,
        UUID orgId,
        UUID createdByUserId,
        String name,
        String keyPrefix,
        String keyHash,
        short pepperVersion,
        List<String> allowedModels,
        List<String> ipWhitelist,
        Integer rpmLimit,
        Integer tpmLimit,
        Integer maxConcurrency,
        Long quotaLimitMicros,
        OffsetDateTime expiresAt,
        OffsetDateTime lastUsedAt,
        ApiKeyStatus status,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
