package app.pagefault.hyperllm.console.dto;

import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.InvitationStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class TenantDtos {

    private TenantDtos() {
    }

    public record CreateOrgRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$") String slug
    ) {
    }

    public record UpdateOrgRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotNull Long version
    ) {
    }

    public record OrgDto(
            UUID id,
            String name,
            String slug,
            UUID ownerUserId,
            UUID tierId,
            int maxConcurrency,
            boolean personal,
            OrgStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record OrgMemberDto(
            UUID memberId,
            UUID orgId,
            UUID userId,
            String email,
            String displayName,
            OrgRole role,
            OffsetDateTime joinedAt
    ) {
    }

    public record UpdateOrgMemberRoleRequest(@NotNull OrgRole role) {
    }

    public record CreateInvitationRequest(
            @NotBlank @Email String email,
            @NotNull OrgRole role,
            List<UUID> projectIds
    ) {
    }

    public record InvitationResponseDto(
            UUID id,
            UUID orgId,
            String email,
            OrgRole role,
            List<UUID> projectIds,
            InvitationStatus status,
            OffsetDateTime expiresAt,
            String invitationToken
    ) {
    }

    public record AcceptInvitationRequest(@NotBlank String token) {
    }

    public record CreateProjectRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            @NotNull ProjectEnvironment environment,
            @Min(0) Long monthlyHardCapMicros,
            @Min(0) Long monthlySoftCapMicros,
            List<String> allowedModels,
            @Min(1) Integer rpmLimit,
            @Min(1) Integer tpmLimit,
            @Min(1) Integer maxConcurrency
    ) {
    }

    public record UpdateProjectRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            @Min(0) Long monthlyHardCapMicros,
            @Min(0) Long monthlySoftCapMicros,
            List<String> allowedModels,
            @Min(1) Integer rpmLimit,
            @Min(1) Integer tpmLimit,
            @Min(1) Integer maxConcurrency,
            @NotNull ProjectStatus status,
            @NotNull Long version
    ) {
    }

    public record ProjectDto(
            UUID id,
            UUID orgId,
            String name,
            ProjectEnvironment environment,
            Long monthlyHardCapMicros,
            Long monthlySoftCapMicros,
            List<String> allowedModels,
            Integer rpmLimit,
            Integer tpmLimit,
            Integer maxConcurrency,
            boolean zdrDebugEnabled,
            short zdrDebugTtlDays,
            ProjectStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ReplaceProjectMembersRequest(@NotNull List<UUID> userIds) {
    }

    public record CreateApiKeyRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            List<String> allowedModels,
            List<String> ipWhitelist,
            @Min(1) Integer rpmLimit,
            @Min(1) Integer tpmLimit,
            @Min(1) Integer maxConcurrency,
            @Min(0) Long quotaLimitMicros,
            OffsetDateTime expiresAt
    ) {
    }

    public record UpdateApiKeyRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            List<String> allowedModels,
            List<String> ipWhitelist,
            @Min(1) Integer rpmLimit,
            @Min(1) Integer tpmLimit,
            @Min(1) Integer maxConcurrency,
            @Min(0) Long quotaLimitMicros,
            OffsetDateTime expiresAt,
            @NotNull ApiKeyStatus status,
            @NotNull Long version
    ) {
    }

    public record ApiKeyDto(
            UUID id,
            UUID projectId,
            UUID orgId,
            String name,
            String keyPrefix,
            List<String> allowedModels,
            List<String> ipWhitelist,
            Integer rpmLimit,
            Integer tpmLimit,
            Integer maxConcurrency,
            Long quotaLimitMicros,
            OffsetDateTime expiresAt,
            OffsetDateTime lastUsedAt,
            ApiKeyStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record CreatedApiKeyDto(
            ApiKeyDto apiKey,
            String rawKey
    ) {
    }
}
