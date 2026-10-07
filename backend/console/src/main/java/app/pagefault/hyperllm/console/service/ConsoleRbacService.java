package app.pagefault.hyperllm.console.service;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.OrgMemberRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.ProjectMemberRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Dual-layer RBAC guard verifying {@code org_members} and {@code project_members} permissions.
 */
@Service
@RequiredArgsConstructor
public class ConsoleRbacService {

    public record ProjectAccess(Project project, Organization org, OrgMember member) {
    }

    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public Organization requireActiveOrg(UUID orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND));
    }

    public OrgMember requireOrgMembership(UUID orgId, UUID userId) {
        requireActiveOrg(orgId);
        return orgMemberRepository.findByOrgAndUser(orgId, userId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Not a member of this organization"));
    }

    public OrgMember requireOrgRole(UUID orgId, UUID userId, OrgRole first, OrgRole... rest) {
        OrgMember member = requireOrgMembership(orgId, userId);
        Set<OrgRole> allowed = EnumSet.of(first, rest);
        if (!allowed.contains(member.role())) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Insufficient organization role");
        }
        return member;
    }

    public ProjectAccess requireProjectAccess(UUID projectId, UUID userId, boolean writeProjectSettings) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_PROJECT_NOT_FOUND));
        Organization org = requireActiveOrg(project.orgId());
        if (org.status() != OrgStatus.ACTIVE && writeProjectSettings) {
            throw new HyperLlmException(ErrorCode.TENANT_ORG_SUSPENDED);
        }
        OrgMember member = orgMemberRepository.findByOrgAndUser(org.id(), userId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Not a member of this organization"));
        if (writeProjectSettings) {
            if (member.role() != OrgRole.OWNER && member.role() != OrgRole.ADMIN) {
                throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Only OWNER or ADMIN may modify project settings");
            }
            return new ProjectAccess(project, org, member);
        }
        if (member.role() == OrgRole.DEVELOPER && !projectMemberRepository.hasAccess(projectId, userId)) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Developer does not have access to this project");
        }
        return new ProjectAccess(project, org, member);
    }

    public ProjectAccess requireKeyManageAccess(UUID projectId, UUID userId) {
        ProjectAccess access = requireProjectAccess(projectId, userId, false);
        if (access.member().role() == OrgRole.BILLING) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "BILLING role cannot manage API keys or sandbox tokens");
        }
        return access;
    }
}
