package app.pagefault.hyperllm.console.service;

import app.pagefault.hyperllm.common.crypto.ApiKeyGenerator;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.console.config.ConsoleCacheSyncService;
import app.pagefault.hyperllm.console.config.ConsoleProperties;
import app.pagefault.hyperllm.console.dto.TenantDtos.ApiKeyDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreateApiKeyRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreateProjectRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreatedApiKeyDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.ProjectDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.ReplaceProjectMembersRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.UpdateApiKeyRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.UpdateProjectRequest;
import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.tenant.ApiKey;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.ApiKeyRepository;
import app.pagefault.hyperllm.domain.repository.OrgMemberRepository;
import app.pagefault.hyperllm.domain.repository.ProjectMemberRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConsoleProjectAndKeyService {

    private final ConsoleRbacService rbac;
    private final ConsoleProperties properties;
    private final ConsoleCacheSyncService cacheSync;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final ApiKeyRepository apiKeyRepository;

    public PageResponse<ProjectDto> listProjects(UUID orgId, UUID userId, PageQuery pageQuery) {
        OrgMember member = rbac.requireOrgMembership(orgId, userId);
        PageResponse<Project> page = (member.role() == OrgRole.DEVELOPER)
                ? projectRepository.listByOrgForMember(orgId, userId, pageQuery)
                : projectRepository.listByOrg(orgId, pageQuery);
        return new PageResponse<>(
                page.items().stream().map(ConsoleProjectAndKeyService::toProjectDto).toList(),
                page.page(), page.pageSize(), page.total()
        );
    }

    @Transactional
    public ProjectDto createProject(UUID orgId, UUID userId, CreateProjectRequest req) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID projectId = UuidV7.next();
        Project project = new Project(
                projectId, orgId, req.name().trim(), req.environment(),
                req.monthlyHardCapMicros(), req.monthlySoftCapMicros(),
                req.allowedModels(), req.rpmLimit(), req.tpmLimit(), req.maxConcurrency(),
                false, (short) 7, null, ProjectStatus.ACTIVE, null, 0L, now, now
        );
        projectRepository.insert(project);
        return toProjectDto(project);
    }

    public ProjectDto getProject(UUID projectId, UUID userId) {
        return toProjectDto(rbac.requireProjectAccess(projectId, userId, false).project());
    }

    @Transactional
    public ProjectDto updateProject(UUID projectId, UUID userId, UpdateProjectRequest req) {
        rbac.requireProjectAccess(projectId, userId, true);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean ok = projectRepository.updateSettings(
                projectId, req.name().trim(), req.monthlyHardCapMicros(), req.monthlySoftCapMicros(),
                req.allowedModels(), req.rpmLimit(), req.tpmLimit(), req.maxConcurrency(),
                req.status(), req.version(), now
        );
        if (!ok) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictKeyHashesAfterCommit(apiKeyRepository.listActiveKeyHashesByProject(projectId));
        return toProjectDto(projectRepository.findById(projectId).orElseThrow());
    }

    @Transactional
    public void deleteProject(UUID projectId, UUID userId, long version) {
        rbac.requireProjectAccess(projectId, userId, true);
        List<String> keyHashes = apiKeyRepository.listActiveKeyHashesByProject(projectId);
        if (!projectRepository.softDelete(projectId, version, OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictKeyHashesAfterCommit(keyHashes);
    }

    public List<UUID> listProjectMembers(UUID projectId, UUID userId) {
        rbac.requireProjectAccess(projectId, userId, true);
        return projectMemberRepository.listUserIdsByProject(projectId);
    }

    @Transactional
    public List<UUID> replaceProjectMembers(UUID projectId, UUID userId, ReplaceProjectMembersRequest req) {
        ConsoleRbacService.ProjectAccess access = rbac.requireProjectAccess(projectId, userId, true);
        for (UUID targetUserId : req.userIds()) {
            if (orgMemberRepository.findByOrgAndUser(access.org().id(), targetUserId).isEmpty()) {
                throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Target user is not an organization member: " + targetUserId);
            }
        }
        projectMemberRepository.replaceMembers(projectId, access.org().id(), req.userIds(), userId);
        return projectMemberRepository.listUserIdsByProject(projectId);
    }

    public PageResponse<ApiKeyDto> listApiKeys(UUID projectId, UUID userId, PageQuery pageQuery) {
        rbac.requireKeyManageAccess(projectId, userId);
        PageResponse<ApiKey> page = apiKeyRepository.listByProject(projectId, pageQuery);
        return new PageResponse<>(
                page.items().stream().map(ConsoleProjectAndKeyService::toKeyDto).toList(),
                page.page(), page.pageSize(), page.total()
        );
    }

    @Transactional
    public CreatedApiKeyDto createApiKey(UUID projectId, UUID userId, CreateApiKeyRequest req) {
        ConsoleRbacService.ProjectAccess access = rbac.requireKeyManageAccess(projectId, userId);
        ApiKeyGenerator.GeneratedApiKey generated = ApiKeyGenerator.generateLiveKey();
        String keyHash = HmacSha256.hmacHex(generated.rawKey(), properties.getSecurity().getKeyPepperV1());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID keyId = UuidV7.next();

        ApiKey apiKey = new ApiKey(
                keyId, projectId, access.org().id(), userId, req.name().trim(),
                generated.keyPrefix(), keyHash, (short) 1,
                req.allowedModels(), req.ipWhitelist(),
                req.rpmLimit(), req.tpmLimit(), req.maxConcurrency(), req.quotaLimitMicros(),
                req.expiresAt(), null, ApiKeyStatus.ACTIVE, null, 0L, now, now
        );
        apiKeyRepository.insert(apiKey);
        cacheSync.warmKeyContextAfterCommit(apiKeyRepository.findGatewayKeyContextByHash(keyHash));
        return new CreatedApiKeyDto(toKeyDto(apiKey), generated.rawKey());
    }

    @Transactional
    public ApiKeyDto updateApiKey(UUID keyId, UUID userId, UpdateApiKeyRequest req) {
        ApiKey existing = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.KEY_NOT_FOUND));
        rbac.requireKeyManageAccess(existing.projectId(), userId);
        if (existing.status() == ApiKeyStatus.REVOKED) {
            throw new HyperLlmException(ErrorCode.KEY_ALREADY_REVOKED);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean ok = apiKeyRepository.updateConfig(
                keyId, req.name().trim(), req.allowedModels(), req.ipWhitelist(),
                req.rpmLimit(), req.tpmLimit(), req.maxConcurrency(), req.quotaLimitMicros(),
                req.expiresAt(), req.status(), req.version(), now
        );
        if (!ok) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictKeyHashesAfterCommit(List.of(existing.keyHash()));
        return toKeyDto(apiKeyRepository.findById(keyId).orElseThrow());
    }

    @Transactional
    public void revokeApiKey(UUID keyId, UUID userId, long version) {
        ApiKey existing = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.KEY_NOT_FOUND));
        rbac.requireKeyManageAccess(existing.projectId(), userId);
        if (existing.status() == ApiKeyStatus.REVOKED) {
            throw new HyperLlmException(ErrorCode.KEY_ALREADY_REVOKED);
        }
        if (!apiKeyRepository.revoke(keyId, version, OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictKeyHashesAfterCommit(List.of(existing.keyHash()));
    }

    private static ProjectDto toProjectDto(Project p) {
        return new ProjectDto(
                p.id(), p.orgId(), p.name(), p.environment(),
                p.monthlyHardCapMicros(), p.monthlySoftCapMicros(), p.allowedModels(),
                p.rpmLimit(), p.tpmLimit(), p.maxConcurrency(),
                p.zdrDebugEnabled(), p.zdrDebugTtlDays(), p.status(),
                p.version(), p.createdAt(), p.updatedAt()
        );
    }

    private static ApiKeyDto toKeyDto(ApiKey k) {
        return new ApiKeyDto(
                k.id(), k.projectId(), k.orgId(), k.name(), k.keyPrefix(),
                k.allowedModels(), k.ipWhitelist(),
                k.rpmLimit(), k.tpmLimit(), k.maxConcurrency(), k.quotaLimitMicros(),
                k.expiresAt(), k.lastUsedAt(), k.status(), k.version(), k.createdAt(), k.updatedAt()
        );
    }
}
