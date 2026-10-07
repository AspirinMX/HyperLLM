package app.pagefault.hyperllm.console.web;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Accessor utilities for the authenticated {@link ConsolePrincipal} and request metadata.
 */
public final class ConsoleRequestContext {

    static final String PRINCIPAL_ATTR = ConsolePrincipal.class.getName();

    private ConsoleRequestContext() {
    }

    public static ConsolePrincipal requirePrincipal(HttpServletRequest request) {
        Object attr = request.getAttribute(PRINCIPAL_ATTR);
        if (attr instanceof ConsolePrincipal principal) {
            return principal;
        }
        throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Authentication required");
    }

    public static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
