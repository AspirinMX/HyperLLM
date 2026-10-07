package app.pagefault.hyperllm.domain;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.domain.model.auth.AdminSession;
import app.pagefault.hyperllm.domain.model.auth.AdminUser;
import app.pagefault.hyperllm.domain.model.auth.RefreshToken;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.auth.UserIdentity;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import app.pagefault.hyperllm.domain.model.enums.AdminStatus;
import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.IdentityProvider;
import app.pagefault.hyperllm.domain.model.enums.InvitationStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.model.tenant.ApiKey;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;
import app.pagefault.hyperllm.domain.model.tenant.OrgInvitation;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.model.tenant.ProjectMember;
import app.pagefault.hyperllm.domain.repository.AdminSessionRepository;
import app.pagefault.hyperllm.domain.repository.AdminUserRepository;
import app.pagefault.hyperllm.domain.repository.ApiKeyRepository;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.OrgInvitationRepository;
import app.pagefault.hyperllm.domain.repository.OrgMemberRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.ProjectMemberRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import app.pagefault.hyperllm.domain.repository.RefreshTokenRepository;
import app.pagefault.hyperllm.domain.repository.UserIdentityRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAndKeyRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private UserIdentityRepository userIdentityRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private AdminUserRepository adminUserRepository;
    @Autowired private AdminSessionRepository adminSessionRepository;
    @Autowired private CustomerTierRepository customerTierRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrgMemberRepository orgMemberRepository;
    @Autowired private OrgInvitationRepository orgInvitationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;

    @Test
    void persistsFullAuthTenantAndHydratedGatewayKeyContext() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID userId = UuidV7.next();
        UUID devId = UuidV7.next();
        CustomerTier standard = customerTierRepository.findActiveByCode("STANDARD").orElseThrow();

        userRepository.insert(new User(
                userId, "owner-" + userId + "@acme.dev", now, "Owner", null,
                "zh-CN", "USD", null, UserStatus.ACTIVE, null, null, 0L, now, now
        ));
        userRepository.insert(new User(
                devId, "dev-" + devId + "@acme.dev", now, "Developer", null,
                "en-US", "USD", null, UserStatus.ACTIVE, null, null, 0L, now, now
        ));

        userIdentityRepository.insert(new UserIdentity(
                UuidV7.next(), userId, IdentityProvider.PASSWORD, userId.toString(),
                null, "$2a$10$hash", "owner@acme.dev", "{}", null, now, now
        ));

        UUID familyId = UuidV7.next();
        UUID tokenId = UuidV7.next();
        String rtHash = "rt-" + tokenId;
        refreshTokenRepository.insert(new RefreshToken(
                tokenId, userId, familyId, rtHash, "203.0.113.10", "Mozilla/5.0",
                now.plusDays(14), null, null, now
        ));
        assertThat(refreshTokenRepository.findByTokenHash(rtHash))
                .isPresent()
                .get()
                .extracting(RefreshToken::clientIp)
                .isEqualTo("203.0.113.10");
        assertThat(refreshTokenRepository.markRotated(tokenId, now.plusMinutes(1))).isTrue();
        assertThat(refreshTokenRepository.revokeFamily(familyId, now.plusMinutes(2))).isEqualTo(1);
        assertThat(refreshTokenRepository.revokeFamily(familyId, now.plusMinutes(3))).isZero();

        UUID adminId = UuidV7.next();
        adminUserRepository.insert(new AdminUser(
                adminId, "ops_" + adminId.toString().substring(0, 8), "ops-" + adminId + "@hyperllm.dev",
                "$2a$10$admin", AdminRole.SUPER_ADMIN, new byte[]{1, 2, 3}, false, null,
                List.of("10.0.0.0/8", "192.168.1.5/32"), AdminStatus.ACTIVE, null, null, 0L, now, now
        ));
        assertThat(adminUserRepository.findById(adminId))
                .isPresent()
                .get()
                .satisfies(a -> assertThat(a.allowedCidrs()).containsExactly("10.0.0.0/8", "192.168.1.5/32"));

        adminSessionRepository.insert(new AdminSession(
                UuidV7.next(), adminId, "admin-sess-" + adminId, now, "10.0.0.5", "curl",
                now.plusHours(8), null, now
        ));

        UUID orgId = UuidV7.next();
        organizationRepository.insert(new Organization(
                orgId, "Acme Workspace", "acme-" + orgId.toString().substring(0, 8),
                userId, standard.id(), 9500, 100, false, OrgStatus.ACTIVE, null, 0L, now, now
        ));
        orgMemberRepository.insert(new OrgMember(UuidV7.next(), orgId, userId, OrgRole.OWNER, now, now));
        orgMemberRepository.insert(new OrgMember(UuidV7.next(), orgId, devId, OrgRole.DEVELOPER, now, now));

        UUID prodProjId = UuidV7.next();
        UUID stagingProjId = UuidV7.next();
        projectRepository.insert(new Project(
                prodProjId, orgId, "prod", ProjectEnvironment.PRODUCTION, 10_000_000L, 8_000_000L,
                List.of("gpt-5", "claude-sonnet-4-5"), 600, 200_000, 30, true, (short) 5, null,
                ProjectStatus.ACTIVE, null, 0L, now, now
        ));
        projectRepository.insert(new Project(
                stagingProjId, orgId, "staging", ProjectEnvironment.STAGING, null, null,
                null, 120, null, null, false, (short) 3, null,
                ProjectStatus.ACTIVE, null, 0L, now, now
        ));

        projectMemberRepository.replaceMembers(stagingProjId, orgId, List.of(devId), userId);
        assertThat(projectRepository.listByOrgForMember(orgId, devId, PageQuery.of(1, 10)).items())
                .extracting(Project::id)
                .containsExactly(stagingProjId);

        UUID invId = UuidV7.next();
        orgInvitationRepository.insert(new OrgInvitation(
                invId, orgId, "newdev@acme.dev", OrgRole.DEVELOPER, List.of(stagingProjId),
                "inv-" + invId, userId, now.plusDays(3), null, InvitationStatus.PENDING, now
        ));
        assertThat(orgInvitationRepository.markAccepted(invId, now.plusMinutes(5))).isTrue();

        UUID keyId = UuidV7.next();
        String keyHash = "hmac-" + keyId;
        apiKeyRepository.insert(new ApiKey(
                keyId, prodProjId, orgId, userId, "Prod Gateway Key", "sk-hl-ab12...wxyz",
                keyHash, (short) 1, List.of("claude-sonnet-4-5"), List.of("203.0.113.0/24"),
                300, 100_000, 20, 5_000_000L, now.plusDays(30), null,
                ApiKeyStatus.ACTIVE, null, 0L, now, now
        ));

        GatewayKeyContext ctx = apiKeyRepository.findGatewayKeyContextByHash(keyHash).orElseThrow();
        assertThat(ctx.apiKeyId()).isEqualTo(keyId);
        assertThat(ctx.effectiveDiscountBps()).isEqualTo(9500);
        assertThat(ctx.keyAllowedModels()).containsExactly("claude-sonnet-4-5");
        assertThat(ctx.projectAllowedModels()).containsExactly("gpt-5", "claude-sonnet-4-5");
        assertThat(ctx.ipWhitelist()).containsExactly("203.0.113.0/24");
        assertThat(ctx.zdrDebugEnabled()).isTrue();
        assertThat(ctx.zdrDebugTtlDays()).isEqualTo((short) 5);
    }
}
