package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.enums.InvitationStatus;
import app.pagefault.hyperllm.domain.model.tenant.OrgInvitation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgInvitationRepository {

    void insert(OrgInvitation invitation);

    Optional<OrgInvitation> findByTokenHash(String tokenHash);

    List<OrgInvitation> listByOrgAndStatus(UUID orgId, InvitationStatus status);

    boolean markAccepted(UUID invitationId, OffsetDateTime acceptedAt);

    boolean revoke(UUID orgId, UUID invitationId);
}
