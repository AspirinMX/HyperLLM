package app.pagefault.hyperllm.domain.model.tenant;

import app.pagefault.hyperllm.domain.model.enums.InvitationStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrgInvitation(
        UUID id,
        UUID orgId,
        String email,
        OrgRole role,
        List<UUID> projectIds,
        String tokenHash,
        UUID invitedByUserId,
        OffsetDateTime expiresAt,
        OffsetDateTime acceptedAt,
        InvitationStatus status,
        OffsetDateTime createdAt
) {
}
