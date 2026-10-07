package app.pagefault.hyperllm.admin.config;

import app.pagefault.hyperllm.admin.web.AdminPrincipal;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HS256 JWT tokens strictly scoped to the operator admin control plane.
 */
@Component
public class AdminJwtService {

    public static final String ISSUER = "hyperllm-admin";
    public static final String AUDIENCE = "admin-api";
    public static final String TYPE_ACCESS = "ADMIN_ACCESS";
    public static final String TYPE_MFA_CHALLENGE = "ADMIN_MFA_CHALLENGE";

    private final SecretKey signingKey;
    private final long accessTtlMinutes;
    private final long mfaChallengeTtlMinutes;

    public AdminJwtService(AdminProperties properties) {
        String secret = properties.getSecurity().getAdminJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("hyperllm.security.admin-jwt-secret must be at least 32 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = properties.getSecurity().getAccessTtlMinutes();
        this.mfaChallengeTtlMinutes = properties.getSecurity().getMfaChallengeTtlMinutes();
    }

    public String issueAccessToken(UUID adminId, AdminRole role, boolean mfaVerified) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTtlMinutes * 60L);
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(adminId.toString())
                .id(UuidV7.next().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("role", role.name())
                .claim("mfa", mfaVerified)
                .claim("type", TYPE_ACCESS)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public String issueMfaChallengeToken(UUID adminId, AdminRole role, String scope) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(mfaChallengeTtlMinutes * 60L);
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(adminId.toString())
                .id(UuidV7.next().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("role", role.name())
                .claim("scope", scope)
                .claim("type", TYPE_MFA_CHALLENGE)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public AdminPrincipal parseAccessToken(String token) {
        Claims claims = parseClaims(token, TYPE_ACCESS);
        return new AdminPrincipal(
                UUID.fromString(claims.getSubject()),
                AdminRole.valueOf(claims.get("role", String.class)),
                Boolean.TRUE.equals(claims.get("mfa", Boolean.class)),
                TYPE_ACCESS,
                null
        );
    }

    public AdminPrincipal parseMfaChallengeToken(String token, String expectedScope) {
        Claims claims = parseClaims(token, TYPE_MFA_CHALLENGE);
        String scope = claims.get("scope", String.class);
        if (!expectedScope.equals(scope)) {
            throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "MFA token scope mismatch");
        }
        return new AdminPrincipal(
                UUID.fromString(claims.getSubject()),
                AdminRole.valueOf(claims.get("role", String.class)),
                false,
                TYPE_MFA_CHALLENGE,
                scope
        );
    }

    public long accessTtlSeconds() {
        return accessTtlMinutes * 60L;
    }

    private Claims parseClaims(String token, String expectedType) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .require("type", expectedType)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new HyperLlmException(ErrorCode.AUTH_TOKEN_EXPIRED, "Admin token expired", ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid admin token", ex);
        }
    }
}
