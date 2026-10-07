package app.pagefault.hyperllm.admin.service;

import app.pagefault.hyperllm.admin.config.AdminJwtService;
import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.admin.config.TotpService;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminAuthResponse;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminLoginRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminMfaSetupRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminMfaVerifyRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.MfaSetupChallengeResponse;
import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.net.CidrMatcher;
import app.pagefault.hyperllm.domain.model.auth.AdminSession;
import app.pagefault.hyperllm.domain.model.auth.AdminUser;
import app.pagefault.hyperllm.domain.model.enums.AdminStatus;
import app.pagefault.hyperllm.domain.repository.AdminSessionRepository;
import app.pagefault.hyperllm.domain.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    public static final String ADMIN_REFRESH_COOKIE_NAME = "hl_admin_rt";
    private static final String MFA_PENDING_KEY_PREFIX = "gw:admin:mfa_pending:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public record AdminSessionResult(AdminAuthResponse body, String rawSessionToken) {
    }

    private final AdminProperties properties;
    private final AdminJwtService jwtService;
    private final AesGcmCipher aesGcmCipher;
    private final PasswordEncoder passwordEncoder;
    private final AdminUserRepository adminUserRepository;
    private final AdminSessionRepository adminSessionRepository;
    private final StringRedisTemplate redisTemplate;

    public AdminSessionResult login(AdminLoginRequest req, String clientIp, String userAgent) {
        AdminUser admin = adminUserRepository.findActiveByLogin(req.login().trim())
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        if (admin.status() != AdminStatus.ACTIVE) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Admin account is locked or disabled");
        }
        if (!CidrMatcher.isAllowed(clientIp, admin.allowedCidrs())) {
            throw new HyperLlmException(ErrorCode.AUTH_IP_BLOCKED, "Client IP not permitted for admin login");
        }
        if (!passwordEncoder.matches(req.password(), admin.passwordHash())) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        if (properties.getAdmin().getMfa().isRequired()) {
            String scope = admin.mfaEnabled() ? "MFA_VERIFY" : "MFA_SETUP";
            String challenge = jwtService.issueMfaChallengeToken(admin.id(), admin.role(), scope);
            return new AdminSessionResult(
                    new AdminAuthResponse(
                            true, scope, null, challenge,
                            properties.getSecurity().getMfaChallengeTtlMinutes() * 60L,
                            admin.id(), admin.username(), admin.email(), admin.role(), false
                    ),
                    null
            );
        }

        return issueFullSession(admin, false, clientIp, userAgent);
    }

    public Object setupMfa(UUID adminId, AdminMfaSetupRequest req, String clientIp, String userAgent) {
        AdminUser admin = requireActiveAdmin(adminId);
        if (admin.mfaEnabled()) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "TOTP MFA is already enabled for this account");
        }
        if (req == null || req.code() == null || req.code().isBlank()) {
            String secret = TotpService.generateBase32Secret();
            redisTemplate.opsForValue().set(MFA_PENDING_KEY_PREFIX + adminId, secret, Duration.ofMinutes(10));
            String uri = TotpService.buildOtpAuthUri("HyperLLM-Admin", admin.email(), secret);
            return new MfaSetupChallengeResponse(secret, uri);
        }

        String secret = redisTemplate.opsForValue().get(MFA_PENDING_KEY_PREFIX + adminId);
        if (secret == null && req.secret() != null && !req.secret().isBlank()) {
            secret = req.secret().trim();
        }
        if (secret == null || !TotpService.verifyCode(secret, req.code().trim(), Instant.now())) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid TOTP verification code");
        }
        byte[] encrypted = aesGcmCipher.encryptUtf8(secret, adminId.toString());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!adminUserRepository.updateTotpSetup(adminId, encrypted, true, "[]", admin.version(), now)) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        redisTemplate.delete(MFA_PENDING_KEY_PREFIX + adminId);
        AdminUser updated = requireActiveAdmin(adminId);
        return issueFullSession(updated, true, clientIp, userAgent);
    }

    public AdminSessionResult verifyMfa(UUID adminId, AdminMfaVerifyRequest req, String clientIp, String userAgent) {
        AdminUser admin = requireActiveAdmin(adminId);
        if (!admin.mfaEnabled() || admin.totpSecretEncrypted() == null) {
            throw new HyperLlmException(ErrorCode.AUTH_MFA_REQUIRED, "TOTP MFA not yet configured");
        }
        String secret = aesGcmCipher.decryptUtf8(admin.totpSecretEncrypted(), adminId.toString());
        if (!TotpService.verifyCode(secret, req.code().trim(), Instant.now())) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid TOTP verification code");
        }
        return issueFullSession(admin, true, clientIp, userAgent);
    }

    @Transactional
    public AdminSessionResult refresh(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Missing admin session cookie");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AdminSession session = adminSessionRepository.findByTokenHash(HmacSha256.sha256Hex(rawSessionToken.trim()))
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_TOKEN_EXPIRED, "Unknown admin session"));
        if (session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
            throw new HyperLlmException(ErrorCode.AUTH_TOKEN_EXPIRED, "Admin session revoked or expired");
        }
        AdminUser admin = requireActiveAdmin(session.adminUserId());
        boolean mfaVerified = session.mfaVerifiedAt() != null;
        String jwt = jwtService.issueAccessToken(admin.id(), admin.role(), mfaVerified);
        return new AdminSessionResult(
                new AdminAuthResponse(
                        false, null, jwt, null, jwtService.accessTtlSeconds(),
                        admin.id(), admin.username(), admin.email(), admin.role(), mfaVerified
                ),
                rawSessionToken.trim()
        );
    }

    @Transactional
    public void logout(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            return;
        }
        adminSessionRepository.revokeByTokenHash(
                HmacSha256.sha256Hex(rawSessionToken.trim()),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private AdminSessionResult issueFullSession(AdminUser admin, boolean mfaVerified, String clientIp, String userAgent) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        adminUserRepository.updateLastLogin(admin.id(), now);

        String rawSessionToken = generateOpaqueToken();
        OffsetDateTime expiresAt = now.plusDays(properties.getSecurity().getRefreshTtlDays());
        adminSessionRepository.insert(new AdminSession(
                UuidV7.next(),
                admin.id(),
                HmacSha256.sha256Hex(rawSessionToken),
                mfaVerified ? now : null,
                clientIp,
                truncateUserAgent(userAgent),
                expiresAt,
                null,
                now
        ));

        String accessToken = jwtService.issueAccessToken(admin.id(), admin.role(), mfaVerified);
        return new AdminSessionResult(
                new AdminAuthResponse(
                        false, null, accessToken, null, jwtService.accessTtlSeconds(),
                        admin.id(), admin.username(), admin.email(), admin.role(), mfaVerified
                ),
                rawSessionToken
        );
    }

    private AdminUser requireActiveAdmin(UUID adminId) {
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        if (admin.status() != AdminStatus.ACTIVE) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Admin account is disabled");
        }
        return admin;
    }

    private static String generateOpaqueToken() {
        byte[] buf = new byte[32];
        SECURE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String truncateUserAgent(String ua) {
        if (ua == null) {
            return null;
        }
        return ua.length() > 500 ? ua.substring(0, 500) : ua;
    }
}
