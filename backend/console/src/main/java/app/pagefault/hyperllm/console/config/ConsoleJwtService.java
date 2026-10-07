package app.pagefault.hyperllm.console.config;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.console.web.ConsolePrincipal;
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
 * Issues and verifies HS256 JWT tokens strictly scoped to the developer console domain.
 */
@Component
public class ConsoleJwtService {

    public static final String ISSUER = "hyperllm-console";
    public static final String AUDIENCE = "console-api";
    public static final String TOKEN_TYPE = "CONSOLE_ACCESS";

    private final SecretKey signingKey;
    private final long accessTtlMinutes;

    public ConsoleJwtService(ConsoleProperties properties) {
        String secret = properties.getSecurity().getConsoleJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("hyperllm.security.console-jwt-secret must be at least 32 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = properties.getSecurity().getAccessTtlMinutes();
    }

    public String issueAccessToken(UUID userId, String email) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTtlMinutes * 60L);
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(userId.toString())
                .id(UuidV7.next().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("email", email)
                .claim("type", TOKEN_TYPE)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public ConsolePrincipal parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .require("type", TOKEN_TYPE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            return new ConsolePrincipal(userId, email);
        } catch (ExpiredJwtException ex) {
            throw new HyperLlmException(ErrorCode.AUTH_TOKEN_EXPIRED, "Access token expired", ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new HyperLlmException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid console access token", ex);
        }
    }

    public long accessTtlSeconds() {
        return accessTtlMinutes * 60L;
    }
}
