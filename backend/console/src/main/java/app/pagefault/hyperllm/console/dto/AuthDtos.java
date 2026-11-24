package app.pagefault.hyperllm.console.dto;

import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SendEmailCodeRequest(
            @NotBlank @Email String email,
            String purpose
    ) {
    }

    public record SendEmailCodeResponse(
            boolean sent,
            int expiresInSeconds,
            String debugCode
    ) {
    }

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(min = 1, max = 100) String displayName,
            @NotBlank String emailCode,
            String locale
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record AuthTokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            UserProfileDto user
    ) {
    }

    public record OrgMembershipSummaryDto(
            UUID orgId,
            String orgName,
            String slug,
            boolean personal,
            OrgRole role
    ) {
    }

    public record UserProfileDto(
            UUID id,
            String email,
            String displayName,
            String locale,
            String preferredCurrency,
            UUID defaultOrgId,
            List<OrgMembershipSummaryDto> organizations
    ) {
    }

    public record UpdateProfileRequest(
            @NotBlank @Size(min = 1, max = 100) String displayName,
            @NotBlank @Size(max = 16) String locale,
            @NotBlank @Size(min = 3, max = 3) String preferredCurrency
    ) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {
    }
}
