package app.pagefault.hyperllm.console.service;

import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.billing.WalletCreditOutbox;
import app.pagefault.hyperllm.console.config.ConsoleCacheSyncService;
import app.pagefault.hyperllm.console.config.ConsoleJwtService;
import app.pagefault.hyperllm.console.config.ConsoleProperties;
import app.pagefault.hyperllm.console.dto.AuthDtos.AuthTokenResponse;
import app.pagefault.hyperllm.console.dto.AuthDtos.ChangePasswordRequest;
import app.pagefault.hyperllm.console.dto.AuthDtos.LoginRequest;
import app.pagefault.hyperllm.console.dto.AuthDtos.OrgMembershipSummaryDto;
import app.pagefault.hyperllm.console.dto.AuthDtos.RegisterRequest;
import app.pagefault.hyperllm.console.dto.AuthDtos.SendEmailCodeRequest;
import app.pagefault.hyperllm.console.dto.AuthDtos.SendEmailCodeResponse;
import app.pagefault.hyperllm.console.dto.AuthDtos.UpdateProfileRequest;
import app.pagefault.hyperllm.console.dto.AuthDtos.UserProfileDto;
import app.pagefault.hyperllm.domain.model.auth.RefreshToken;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.auth.UserIdentity;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.enums.IdentityProvider;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.OrgMemberRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import app.pagefault.hyperllm.domain.repository.RefreshTokenRepository;
import app.pagefault.hyperllm.domain.repository.UserIdentityRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleAuthService {

    public static final String REFRESH_COOKIE_NAME = "hl_console_rt";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public record AuthSessionResult(AuthTokenResponse body, String rawRefreshToken) {
    }

    private final ConsoleProperties properties;
    private final ConsoleJwtService jwtService;
    private final ConsoleCacheSyncService cacheSync;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CustomerTierRepository customerTierRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final ProjectRepository projectRepository;
    private final WalletRepository walletRepository;
    private final WalletCreditOutbox walletCreditOutbox;

    public SendEmailCodeResponse sendEmailCode(SendEmailCodeRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        String purpose = (req.purpose() == null || req.purpose().isBlank()) ? "REGISTER" : req.purpose().trim();
        if (!cacheSync.tryAcquireOtpCooldown(purpose, email)) {
            throw new HyperLlmException(ErrorCode.AUTH_OTP_RATE_LIMITED);
        }
        String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        cacheSync.storeOtpCode(purpose, email, code);
        log.info("Issued email verification code for {} (purpose={})", email, purpose);
        String echo = properties.getAuth().isOtpDebugEchoEnabled() ? code : null;
        return new SendEmailCodeResponse(true, 600, echo);
    }

    @Transactional
    public AuthSessionResult register(RegisterRequest req, String clientIp, String userAgent) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (!cacheSync.verifyAndConsumeOtp("REGISTER", email, req.emailCode().trim())) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_OTP);
        }
        if (userRepository.findActiveByEmail(email).isPresent()) {
            throw new HyperLlmException(ErrorCode.AUTH_EMAIL_EXISTS);
        }
        CustomerTier standardTier = customerTierRepository.findActiveByCode("STANDARD")
                .orElseThrow(() -> new IllegalStateException("STANDARD customer tier seed missing"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID userId = UuidV7.next();
        UUID orgId = UuidV7.next();
        UUID projectId = UuidV7.next();
        String locale = (req.locale() == null || req.locale().isBlank()) ? "en-US" : req.locale().trim();

        userRepository.insert(new User(
                userId, email, now, req.displayName().trim(), null, locale, "USD",
                orgId, UserStatus.ACTIVE, now, null, 0L, now, now
        ));
        userIdentityRepository.insert(new UserIdentity(
                UuidV7.next(), userId, IdentityProvider.PASSWORD, userId.toString(),
                null, passwordEncoder.encode(req.password()), email, "{}", now, now, now
        ));
        organizationRepository.insert(new Organization(
                orgId, "Personal Workspace", "personal-" + orgId,
                userId, standardTier.id(), null, standardTier.defaultOrgConcurrency(), true,
                OrgStatus.ACTIVE, null, 0L, now, now
        ));
        orgMemberRepository.insert(new OrgMember(
                UuidV7.next(), orgId, userId, OrgRole.OWNER, now, now
        ));
        projectRepository.insert(new Project(
                projectId, orgId, "default", ProjectEnvironment.PRODUCTION, null, null,
                null, null, null, null, false, (short) 7, null, ProjectStatus.ACTIVE, null, 0L, now, now
        ));

        long signupBonus = Math.max(0L, properties.getBilling().getSignupBonusMicros());
        walletRepository.insert(new Wallet(orgId, 0L, 0L, 0L, 0L, 0L, 0L, null, 0L, now));
        if (signupBonus > 0L) {
            walletCreditOutbox.append(orgId, "signup-bonus:" + orgId, 0L, signupBonus,
                    BillingMoneyEvent.CreditSource.SIGNUP, "Welcome signup bonus", now);
        }

        User user = userRepository.findById(userId).orElseThrow();
        return issueSession(user, UuidV7.next(), clientIp, userAgent, now);
    }

    @Transactional
    public AuthSessionResult login(LoginRequest req, String clientIp, String userAgent) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findActiveByEmail(email)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        if (user.status() != UserStatus.ACTIVE) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "User account is suspended");
        }
        UserIdentity passwordIdentity = userIdentityRepository.findByUserId(user.id()).stream()
                .filter(id -> id.provider() == IdentityProvider.PASSWORD)
                .findFirst()
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        if (passwordIdentity.passwordHash() == null
                || !passwordEncoder.matches(req.password(), passwordIdentity.passwordHash())) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        userRepository.updateLastLogin(user.id(), now);
        userIdentityRepository.updateLastUsed(passwordIdentity.id(), now);
        return issueSession(user, UuidV7.next(), clientIp, userAgent, now);
    }

    @Transactional(noRollbackFor = HyperLlmException.class)
    public AuthSessionResult refresh(String rawRefreshToken, String clientIp, String userAgent) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Missing refresh token cookie");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String tokenHash = HmacSha256.sha256Hex(rawRefreshToken.trim());
        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_TOKEN_EXPIRED, "Unknown refresh token"));

        if (existing.revokedAt() != null || !existing.expiresAt().isAfter(now)) {
            throw new HyperLlmException(ErrorCode.AUTH_TOKEN_EXPIRED, "Refresh token revoked or expired");
        }
        if (existing.rotatedAt() != null || !refreshTokenRepository.markRotated(existing.id(), now)) {
            refreshTokenRepository.revokeFamily(existing.tokenFamilyId(), now);
            throw new HyperLlmException(ErrorCode.AUTH_TOKEN_REUSE_DETECTED);
        }

        User user = userRepository.findById(existing.userId())
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        if (user.status() != UserStatus.ACTIVE) {
            refreshTokenRepository.revokeFamily(existing.tokenFamilyId(), now);
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "User account is suspended");
        }
        return issueSession(user, existing.tokenFamilyId(), clientIp, userAgent, now);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.revokeByTokenHash(
                HmacSha256.sha256Hex(rawRefreshToken.trim()),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    public UserProfileDto me(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        return toUserProfile(user);
    }

    @Transactional
    public UserProfileDto updateProfile(UUID userId, UpdateProfileRequest req) {
        userRepository.findById(userId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        String currency = req.preferredCurrency().trim().toUpperCase(Locale.ROOT);
        if (!"USD".equals(currency) && !"CNY".equals(currency)) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Unsupported currency: " + currency);
        }
        String locale = req.locale().trim();
        userRepository.updateProfile(
                userId, req.displayName().trim(), locale, currency, OffsetDateTime.now(ZoneOffset.UTC)
        );
        return toUserProfile(userRepository.findById(userId).orElseThrow());
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        UserIdentity passwordIdentity = userIdentityRepository.findByUserId(userId).stream()
                .filter(id -> id.provider() == IdentityProvider.PASSWORD)
                .findFirst()
                .orElseThrow(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "No password credential configured"));
        if (passwordIdentity.passwordHash() == null
                || !passwordEncoder.matches(req.currentPassword(), passwordIdentity.passwordHash())) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Current password is incorrect");
        }
        userIdentityRepository.updatePasswordHash(
                passwordIdentity.id(),
                passwordEncoder.encode(req.newPassword()),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private AuthSessionResult issueSession(
            User user,
            UUID tokenFamilyId,
            String clientIp,
            String userAgent,
            OffsetDateTime now
    ) {
        String rawRefreshToken = generateOpaqueToken();
        String refreshHash = HmacSha256.sha256Hex(rawRefreshToken);
        OffsetDateTime expiresAt = now.plusDays(properties.getSecurity().getRefreshTtlDays());

        refreshTokenRepository.insert(new RefreshToken(
                UuidV7.next(),
                user.id(),
                tokenFamilyId,
                refreshHash,
                clientIp,
                truncateUserAgent(userAgent),
                expiresAt,
                null,
                null,
                now
        ));

        String accessToken = jwtService.issueAccessToken(user.id(), user.email());
        AuthTokenResponse response = new AuthTokenResponse(
                accessToken,
                "Bearer",
                jwtService.accessTtlSeconds(),
                toUserProfile(user)
        );
        return new AuthSessionResult(response, rawRefreshToken);
    }

    private UserProfileDto toUserProfile(User user) {
        List<Organization> orgs = organizationRepository.listActiveByUserId(user.id());
        List<OrgMembershipSummaryDto> summaries = orgs.stream().map(org -> {
            OrgRole role = orgMemberRepository.findByOrgAndUser(org.id(), user.id())
                    .map(OrgMember::role)
                    .orElse(OrgRole.DEVELOPER);
            return new OrgMembershipSummaryDto(org.id(), org.name(), org.slug(), org.personal(), role);
        }).toList();
        return new UserProfileDto(
                user.id(),
                user.email(),
                user.displayName(),
                user.locale(),
                user.preferredCurrency(),
                user.defaultOrgId(),
                summaries
        );
    }

    private static String generateOpaqueToken() {
        byte[] buf = new byte[32];
        SECURE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String truncateUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > 500 ? userAgent.substring(0, 500) : userAgent;
    }
}
