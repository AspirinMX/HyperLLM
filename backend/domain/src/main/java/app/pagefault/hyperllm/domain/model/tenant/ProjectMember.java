package app.pagefault.hyperllm.domain.model.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectMember(
        UUID id,
        UUID projectId,
        UUID orgId,
        UUID userId,
        UUID grantedByUserId,
        OffsetDateTime createdAt
) {
}
