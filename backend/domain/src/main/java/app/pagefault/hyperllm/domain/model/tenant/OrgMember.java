package app.pagefault.hyperllm.domain.model.tenant;

import app.pagefault.hyperllm.domain.model.enums.OrgRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrgMember(
        UUID id,
        UUID orgId,
        UUID userId,
        OrgRole role,
        OffsetDateTime joinedAt,
        OffsetDateTime updatedAt
) {
}
