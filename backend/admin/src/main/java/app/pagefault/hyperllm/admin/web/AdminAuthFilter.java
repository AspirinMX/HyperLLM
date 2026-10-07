package app.pagefault.hyperllm.admin.web;

import app.pagefault.hyperllm.admin.config.AdminJwtService;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Enforces admin HS256 JWT authentication and MFA-challenge token scope isolation.
 */
@Component
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_POST_PATHS = Set.of(
            "/api/v1/admin/auth/login",
            "/api/v1/admin/auth/refresh"
    );

    private final AdminJwtService jwtService;
    private final AdminProblemAdvice problemAdvice;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/admin/")) {
            return true;
        }
        return "POST".equalsIgnoreCase(request.getMethod()) && PUBLIC_POST_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeError(response, ErrorCode.AUTH_INVALID_CREDENTIALS, "Missing admin Bearer token");
            return;
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        String path = request.getRequestURI();
        try {
            AdminPrincipal principal;
            if ("/api/v1/admin/auth/mfa/setup".equals(path)) {
                principal = jwtService.parseMfaChallengeToken(token, "MFA_SETUP");
            } else if ("/api/v1/admin/auth/mfa/verify".equals(path)) {
                principal = jwtService.parseMfaChallengeToken(token, "MFA_VERIFY");
            } else {
                principal = jwtService.parseAccessToken(token);
            }
            request.setAttribute(AdminRequestContext.PRINCIPAL_ATTR, principal);
            filterChain.doFilter(request, response);
        } catch (HyperLlmException ex) {
            writeError(response, ex.getErrorCode(), ex.getMessage());
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        ProblemDetail problem = problemAdvice.buildProblem(code, detail, List.of());
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
