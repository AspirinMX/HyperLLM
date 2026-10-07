package app.pagefault.hyperllm.domain.model.tenant;

import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Project(
        UUID id,
        UUID orgId,
        String name,
        ProjectEnvironment environment,
        Long monthlyHardCapMicros,
        Long monthlySoftCapMicros,
        List<String> allowedModels,
        Integer rpmLimit,
        Integer tpmLimit,
        Integer maxConcurrency,
        boolean zdrDebugEnabled,
        short zdrDebugTtlDays,
        UUID guardrailPolicyId,
        ProjectStatus status,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
