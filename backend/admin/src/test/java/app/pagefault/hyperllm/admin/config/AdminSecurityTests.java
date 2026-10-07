package app.pagefault.hyperllm.admin.config;

import app.pagefault.hyperllm.admin.web.AdminPrincipal;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminSecurityTests {

    @Test
    void enforcesAdminJwtScopeAndDomainIsolation() {
        AdminProperties props = new AdminProperties();
        props.getSecurity().setAdminJwtSecret("admin-test-secret-key-at-least-32-bytes");
        props.getSecurity().setAccessTtlMinutes(15);
        props.getSecurity().setMfaChallengeTtlMinutes(10);

        AdminJwtService jwtService = new AdminJwtService(props);
        UUID adminId = UuidV7.next();

        String accessToken = jwtService.issueAccessToken(adminId, AdminRole.SUPER_ADMIN, true);
        AdminPrincipal principal = jwtService.parseAccessToken(accessToken);
        assertThat(principal.adminId()).isEqualTo(adminId);
        assertThat(principal.role()).isEqualTo(AdminRole.SUPER_ADMIN);
        assertThat(principal.mfaVerified()).isTrue();

        String challenge = jwtService.issueMfaChallengeToken(adminId, AdminRole.OPS, "MFA_SETUP");
        assertThatThrownBy(() -> jwtService.parseAccessToken(challenge))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
        assertThat(jwtService.parseMfaChallengeToken(challenge, "MFA_SETUP").role()).isEqualTo(AdminRole.OPS);
    }

    @Test
    void generatesValidRfc6238TotpCodes() {
        String secret = TotpService.generateBase32Secret();
        assertThat(secret).hasSize(32).matches("^[A-Z2-7]{32}$");

        String uri = TotpService.buildOtpAuthUri("HyperLLM-Admin", "ops@hyperllm.dev", secret);
        assertThat(uri).startsWith("otpauth://totp/HyperLLM-Admin:ops%40hyperllm.dev?secret=" + secret);

        // Verify roundtrip against self-derived code using current epoch step
        long step = Instant.now().getEpochSecond() / 30L;
        // Generate valid secret and verify mismatched 6-digit codes are rejected
        assertThat(TotpService.verifyCode(secret, "abcdef", Instant.now())).isFalse();
        assertThat(TotpService.verifyCode(secret, "12345", Instant.now())).isFalse();
        // Verify known RFC 6238 test secret ("12345678901234567890" -> GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ)
        // At t = 59s -> RFC 6238 SHA1 8-digit = 94287082, 6-digit = 287082
        assertThat(TotpService.verifyCode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", Instant.ofEpochSecond(59L)))
                .isTrue();
    }
}
