package app.pagefault.hyperllm.admin.web;

import app.pagefault.hyperllm.domain.model.enums.AdminRole;

import java.util.UUID;

/**
 * Authenticated operator identity extracted from the admin JWT.
 */
public record AdminPrincipal(
        UUID adminId,
        AdminRole role,
        boolean mfaVerified,
        String tokenType,
        String mfaScope
) {
}
