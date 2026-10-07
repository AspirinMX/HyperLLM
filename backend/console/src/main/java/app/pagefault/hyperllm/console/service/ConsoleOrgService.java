package app.pagefault.hyperllm.console.service;

import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.console.config.ConsoleCacheSyncService;
import app.pagefault.hyperllm.console.dto.TenantDtos.AcceptInvitationRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreateInvitationRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreateOrgRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.InvitationResponseDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.OrgDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.OrgMemberDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.UpdateOrgMemberRoleRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.UpdateOrgRequest;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.enums.InvitationStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.model.tenant.OrgInvitation;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.model.tenant.ProjectMember;
import app.pagefault.hyperllm.domain.repository.ApiKeyRepository;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.OrgInvitationRepository;
import app.pagefault.hyperllm.domain.repository.OrgMemberRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.ProjectMemberRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConsoleOrgService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ConsoleRbacService rbac;
    private final ConsoleCacheSyncService cacheSync;
    private final CustomerTierRepository customerTierRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final OrgInvitationRepository orgInvitationRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final ApiKeyRepository apiKeyRepository;

    public List<OrgDto> listUserOrgs(UUID userId) {
        return organizationRepository.listActiveByUserId(userId).stream()
                .map(ConsoleOrgService::toDto)
                .toList();
    }

    @Transactional
    public OrgDto createOrg(UUID userId, CreateOrgRequest req) {
        String slug = req.slug().trim().toLowerCase(Locale.ROOT);
        if (organizationRepository.findActiveBySlug(slug).isPresent()) {
            throw new HyperLlmException(ErrorCode.TENANT_SLUG_EXISTS);
        }
        CustomerTier standardTier = customerTierRepository.findActiveByCode("STANDARD")
                .orElseThrow(() -> new IllegalStateException("STANDARD customer tier seed missing"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = UuidV7.next();

        Organization org = new Organization(
                orgId, req.name().trim(), slug, userId, standardTier.id(),
                null, standardTier.defaultOrgConcurrency(), false, OrgStatus.ACTIVE,
                null, 0L, now, now
        );
        organizationRepository.insert(org);
        orgMemberRepository.insert(new OrgMember(UuidV7.next(), orgId, userId, OrgRole.OWNER, now, now));

        Wallet wallet = new Wallet(orgId, 0L, 0L, 0L, 0L, 0L, 0L, null, 0L, now);
        walletRepository.insert(wallet);
        cacheSync.syncWalletAfterCommit(wallet, OrgStatus.ACTIVE.name());
        return toDto(org);
    }

    public OrgDto getOrg(UUID orgId, UUID userId) {
        rbac.requireOrgMembership(orgId, userId);
        return toDto(rbac.requireActiveOrg(orgId));
    }

    @Transactional
    public OrgDto updateOrg(UUID orgId, UUID userId, UpdateOrgRequest req) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!organizationRepository.updateName(orgId, req.name().trim(), req.version(), now)) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictKeyHashesAfterCommit(apiKeyRepository.listActiveKeyHashesByOrg(orgId));
        return toDto(rbac.requireActiveOrg(orgId));
    }

    public List<OrgMemberDto> listMembers(UUID orgId, UUID userId) {
        rbac.requireOrgMembership(orgId, userId);
        return orgMemberRepository.listByOrgId(orgId).stream().map(member -> {
            User user = userRepository.findById(member.userId()).orElse(null);
            return new OrgMemberDto(
                    member.id(),
                    member.orgId(),
                    member.userId(),
                    user == null ? null : user.email(),
                    user == null ? null : user.displayName(),
                    member.role(),
                    member.joinedAt()
            );
        }).toList();
    }

    @Transactional
    public OrgMemberDto updateMemberRole(UUID orgId, UUID memberId, UUID actorUserId, UpdateOrgMemberRoleRequest req) {
        rbac.requireOrgRole(orgId, actorUserId, OrgRole.OWNER, OrgRole.ADMIN);
        OrgMember target = orgMemberRepository.findById(memberId)
                .filter(m -> m.orgId().equals(orgId))
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Organization member not found"));
        if (target.role() == OrgRole.OWNER || req.role() == OrgRole.OWNER) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Cannot alter or assign OWNER role directly");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        orgMemberRepository.updateRole(memberId, req.role(), now);
        User user = userRepository.findById(target.userId()).orElse(null);
        return new OrgMemberDto(
                target.id(), target.orgId(), target.userId(),
                user == null ? null : user.email(),
                user == null ? null : user.displayName(),
                req.role(), target.joinedAt()
        );
    }

    @Transactional
    public void removeMember(UUID orgId, UUID memberId, UUID actorUserId) {
        rbac.requireOrgRole(orgId, actorUserId, OrgRole.OWNER, OrgRole.ADMIN);
        OrgMember target = orgMemberRepository.findById(memberId)
                .filter(m -> m.orgId().equals(orgId))
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Organization member not found"));
        if (target.role() == OrgRole.OWNER) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Cannot remove organization owner");
        }
        projectMemberRepository.deleteByOrgAndUser(orgId, target.userId());
        orgMemberRepository.deleteById(memberId);
    }

    @Transactional
    public InvitationResponseDto createInvitation(UUID orgId, UUID actorUserId, CreateInvitationRequest req) {
        rbac.requireOrgRole(orgId, actorUserId, OrgRole.OWNER, OrgRole.ADMIN);
        if (req.role() == OrgRole.OWNER) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Cannot invite member directly as OWNER");
        }
        String inviteEmail = req.email().trim().toLowerCase(Locale.ROOT);
        userRepository.findActiveByEmail(inviteEmail).ifPresent(existingUser -> {
            if (orgMemberRepository.findByOrgAndUser(orgId, existingUser.id()).isPresent()) {
                throw new HyperLlmException(ErrorCode.TENANT_MEMBER_EXISTS);
            }
        });
        List<UUID> projectIds = req.projectIds() == null ? List.of() : req.projectIds().stream().distinct().toList();
        for (UUID projectId : projectIds) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_PROJECT_NOT_FOUND));
            if (!project.orgId().equals(orgId)) {
                throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Project does not belong to target organization");
            }
        }

        byte[] rawBytes = new byte[24];
        SECURE_RANDOM.nextBytes(rawBytes);
        String rawToken = "inv-" + Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusDays(7);
        UUID invId = UuidV7.next();

        orgInvitationRepository.insert(new OrgInvitation(
                invId, orgId, inviteEmail, req.role(), projectIds,
                HmacSha256.sha256Hex(rawToken), actorUserId, expiresAt, null, InvitationStatus.PENDING, now
        ));
        return new InvitationResponseDto(invId, orgId, inviteEmail, req.role(), projectIds, InvitationStatus.PENDING, expiresAt, rawToken);
    }

    @Transactional
    public OrgDto acceptInvitation(UUID userId, AcceptInvitationRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        OrgInvitation invitation = orgInvitationRepository.findByTokenHash(HmacSha256.sha256Hex(req.token().trim()))
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_INVITATION_INVALID));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (invitation.status() != InvitationStatus.PENDING || !invitation.expiresAt().isAfter(now)) {
            throw new HyperLlmException(ErrorCode.TENANT_INVITATION_INVALID);
        }
        if (!invitation.email().equalsIgnoreCase(user.email())) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Invitation email does not match signed-in account");
        }
        if (!orgInvitationRepository.markAccepted(invitation.id(), now)) {
            throw new HyperLlmException(ErrorCode.TENANT_INVITATION_INVALID);
        }
        if (orgMemberRepository.findByOrgAndUser(invitation.orgId(), userId).isPresent()) {
            throw new HyperLlmException(ErrorCode.TENANT_MEMBER_EXISTS);
        }

        orgMemberRepository.insert(new OrgMember(UuidV7.next(), invitation.orgId(), userId, invitation.role(), now, now));
        if (invitation.projectIds() != null) {
            for (UUID projectId : invitation.projectIds()) {
                projectMemberRepository.insert(new ProjectMember(
                        UuidV7.next(), projectId, invitation.orgId(), userId, invitation.invitedByUserId(), now
                ));
            }
        }
        return toDto(rbac.requireActiveOrg(invitation.orgId()));
    }

    private static OrgDto toDto(Organization org) {
        return new OrgDto(
                org.id(), org.name(), org.slug(), org.ownerUserId(), org.tierId(),
                org.maxConcurrency(), org.personal(), org.status(), org.version(),
                org.createdAt(), org.updatedAt()
        );
    }
}
