package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.tenant.ProjectMember;

import java.util.List;
import java.util.UUID;

public interface ProjectMemberRepository {

    void insert(ProjectMember member);

    void replaceMembers(UUID projectId, UUID orgId, List<UUID> userIds, UUID grantedByUserId);

    List<UUID> listUserIdsByProject(UUID projectId);

    boolean hasAccess(UUID projectId, UUID userId);
}
