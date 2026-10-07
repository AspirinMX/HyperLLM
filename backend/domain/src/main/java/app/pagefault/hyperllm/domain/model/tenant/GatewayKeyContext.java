package app.pagefault.hyperllm.domain.model.tenant;

import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hydrated key/project/org/tier snapshot used by gateway hot-path caching and fallback lookup.
 */
public record GatewayKeyContext(
        UUID apiKeyId,
        UUID projectId,
        UUID orgId,
        String keyPrefix,
        String keyHash,
        short pepperVersion,
        ApiKeyStatus apiKeyStatus,
        OffsetDateTime apiKeyExpiresAt,
        List<String> keyAllowedModels,
        List<String> ipWhitelist,
        Integer keyRpmLimit,
        Integer keyTpmLimit,
        Integer keyMaxConcurrency,
        Long keyQuotaLimitMicros,
        ProjectStatus projectStatus,
        List<String> projectAllowedModels,
        Integer projectRpmLimit,
        Integer projectTpmLimit,
        Integer projectMaxConcurrency,
        Long projectMonthlyHardCapMicros,
        Long projectMonthlySoftCapMicros,
        boolean zdrDebugEnabled,
        short zdrDebugTtlDays,
        OrgStatus orgStatus,
        int orgMaxConcurrency,
        int effectiveDiscountBps
) {
}
