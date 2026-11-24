package app.pagefault.hyperllm.console.web;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.console.config.ConsoleJwtService;
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
 * Enforces console Bearer JWT authentication on protected {@code /api/v1/**} routes.
 */
@Component
@RequiredArgsConstructor
public class ConsoleAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_POST_PATHS = Set.of(
            "/api/v1/auth/email-codes",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    );

    private final ConsoleJwtService jwtService;
    private final ConsoleProblemSupport problemSupport;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        if ("POST".equalsIgnoreCase(request.getMethod())
                && path.startsWith("/api/v1/payment-notifications/")) {
            // Channel callbacks authenticate with their signature, not with a console session.
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod()) && "/api/v1/models".equals(path)) {
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
            writeUnauthorized(response, ErrorCode.AUTH_INVALID_CREDENTIALS, "Missing Bearer access token");
            return;
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        try {
            ConsolePrincipal principal = jwtService.parseAccessToken(token);
            request.setAttribute(ConsoleRequestContext.PRINCIPAL_ATTR, principal);
            filterChain.doFilter(request, response);
        } catch (HyperLlmException ex) {
            writeUnauthorized(response, ex.getErrorCode(), ex.getMessage());
        }
    }

    private void writeUnauthorized(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        ProblemDetail problem = problemSupport.build(code, detail, List.of());
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
