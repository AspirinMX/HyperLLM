package app.pagefault.hyperllm.admin.web;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import jakarta.servlet.http.HttpServletRequest;

import java.util.EnumSet;

public final class AdminRequestContext {

    static final String PRINCIPAL_ATTR = AdminPrincipal.class.getName();

    private AdminRequestContext() {
    }

    public static AdminPrincipal requirePrincipal(HttpServletRequest request) {
        Object attr = request.getAttribute(PRINCIPAL_ATTR);
        if (attr instanceof AdminPrincipal principal) {
            return principal;
        }
        throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Admin authentication required");
    }

    public static AdminPrincipal requireRole(HttpServletRequest request, AdminRole first, AdminRole... rest) {
        AdminPrincipal principal = requirePrincipal(request);
        if (!EnumSet.of(first, rest).contains(principal.role())) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Insufficient admin role");
        }
        return principal;
    }

    public static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "127.0.0.1" : remote;
    }
}
