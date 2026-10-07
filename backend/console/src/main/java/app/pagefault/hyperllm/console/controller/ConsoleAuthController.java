package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.console.config.ConsoleProperties;
import app.pagefault.hyperllm.console.dto.AuthDtos.AuthTokenResponse;
import app.pagefault.hyperllm.console.dto.AuthDtos.LoginRequest;
import app.pagefault.hyperllm.console.dto.AuthDtos.RegisterRequest;
import app.pagefault.hyperllm.console.dto.AuthDtos.SendEmailCodeRequest;
import app.pagefault.hyperllm.console.dto.AuthDtos.SendEmailCodeResponse;
import app.pagefault.hyperllm.console.dto.AuthDtos.UserProfileDto;
import app.pagefault.hyperllm.console.service.ConsoleAuthService;
import app.pagefault.hyperllm.console.web.ConsolePrincipal;
import app.pagefault.hyperllm.console.web.ConsoleRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class ConsoleAuthController {

    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final ConsoleAuthService authService;
    private final ConsoleProperties properties;

    @PostMapping("/email-codes")
    public SendEmailCodeResponse sendEmailCode(@Valid @RequestBody SendEmailCodeRequest request) {
        return authService.sendEmailCode(request);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthTokenResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        var session = authService.register(
                request,
                ConsoleRequestContext.extractClientIp(servletRequest),
                servletRequest.getHeader(HttpHeaders.USER_AGENT)
        );
        writeRefreshCookie(servletResponse, session.rawRefreshToken(),
                Duration.ofDays(properties.getSecurity().getRefreshTtlDays()));
        return ResponseEntity.status(HttpStatus.CREATED).body(session.body());
    }

    @PostMapping("/login")
    public AuthTokenResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        var session = authService.login(
                request,
                ConsoleRequestContext.extractClientIp(servletRequest),
                servletRequest.getHeader(HttpHeaders.USER_AGENT)
        );
        writeRefreshCookie(servletResponse, session.rawRefreshToken(),
                Duration.ofDays(properties.getSecurity().getRefreshTtlDays()));
        return session.body();
    }

    @PostMapping("/refresh")
    public AuthTokenResponse refresh(
            @CookieValue(name = ConsoleAuthService.REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        var session = authService.refresh(
                refreshToken,
                ConsoleRequestContext.extractClientIp(servletRequest),
                servletRequest.getHeader(HttpHeaders.USER_AGENT)
        );
        writeRefreshCookie(servletResponse, session.rawRefreshToken(),
                Duration.ofDays(properties.getSecurity().getRefreshTtlDays()));
        return session.body();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = ConsoleAuthService.REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse servletResponse
    ) {
        authService.logout(refreshToken);
        writeRefreshCookie(servletResponse, "", Duration.ZERO);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserProfileDto me(HttpServletRequest servletRequest) {
        ConsolePrincipal principal = ConsoleRequestContext.requirePrincipal(servletRequest);
        return authService.me(principal.userId());
    }

    private void writeRefreshCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(ConsoleAuthService.REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.getSecurity().isCookieSecure())
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
