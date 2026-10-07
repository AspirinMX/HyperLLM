package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgMemberRepository {

    void insert(OrgMember member);

    Optional<OrgMember> findByOrgAndUser(UUID orgId, UUID userId);

    List<OrgMember> listByOrgId(UUID orgId);

    boolean updateRole(UUID memberId, OrgRole role, OffsetDateTime updatedAt);

    boolean deleteById(UUID memberId);
}
