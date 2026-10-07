package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminAuthResponse;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminLoginRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminMfaSetupRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminMfaVerifyRequest;
import app.pagefault.hyperllm.admin.service.AdminAuthService;
import app.pagefault.hyperllm.admin.service.AdminAuthService.AdminSessionResult;
import app.pagefault.hyperllm.admin.web.AdminPrincipal;
import app.pagefault.hyperllm.admin.web.AdminRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private static final String ADMIN_COOKIE_PATH = "/api/v1/admin/auth";

    private final AdminAuthService authService;
    private final AdminProperties properties;

    @PostMapping("/login")
    public AdminAuthResponse login(
            @Valid @RequestBody AdminLoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AdminSessionResult result = authService.login(
                body,
                AdminRequestContext.extractClientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT)
        );
        if (result.rawSessionToken() != null) {
            writeSessionCookie(response, result.rawSessionToken(),
                    Duration.ofDays(properties.getSecurity().getRefreshTtlDays()));
        }
        return result.body();
    }

    @PostMapping("/mfa/setup")
    public ResponseEntity<Object> setupMfa(
            @RequestBody(required = false) AdminMfaSetupRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AdminPrincipal principal = AdminRequestContext.requirePrincipal(request);
        Object outcome = authService.setupMfa(
                principal.adminId(),
                body,
                AdminRequestContext.extractClientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT)
        );
        if (outcome instanceof AdminSessionResult session) {
            writeSessionCookie(response, session.rawSessionToken(),
                    Duration.ofDays(properties.getSecurity().getRefreshTtlDays()));
            return ResponseEntity.ok(session.body());
        }
        return ResponseEntity.ok(outcome);
    }

    @PostMapping("/mfa/verify")
    public AdminAuthResponse verifyMfa(
            @Valid @RequestBody AdminMfaVerifyRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AdminPrincipal principal = AdminRequestContext.requirePrincipal(request);
        AdminSessionResult session = authService.verifyMfa(
                principal.adminId(),
                body,
                AdminRequestContext.extractClientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT)
        );
        writeSessionCookie(response, session.rawSessionToken(),
                Duration.ofDays(properties.getSecurity().getRefreshTtlDays()));
        return session.body();
    }

    @PostMapping("/refresh")
    public AdminAuthResponse refresh(
            @CookieValue(name = AdminAuthService.ADMIN_REFRESH_COOKIE_NAME, required = false) String sessionToken
    ) {
        return authService.refresh(sessionToken).body();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AdminAuthService.ADMIN_REFRESH_COOKIE_NAME, required = false) String sessionToken,
            HttpServletResponse response
    ) {
        authService.logout(sessionToken);
        writeSessionCookie(response, "", Duration.ZERO);
        return ResponseEntity.noContent().build();
    }

    private void writeSessionCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(AdminAuthService.ADMIN_REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.getSecurity().isCookieSecure())
                .sameSite("Lax")
                .path(ADMIN_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
