package app.pagefault.hyperllm.console.config;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.console.web.ConsolePrincipal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleJwtServiceTest {

    @Test
    void issuesAndVerifiesConsoleAccessTokensWithStrictAudience() {
        ConsoleProperties props = new ConsoleProperties();
        props.getSecurity().setConsoleJwtSecret("console-test-secret-key-at-least-32-bytes");
        props.getSecurity().setAccessTtlMinutes(15);

        ConsoleJwtService jwtService = new ConsoleJwtService(props);
        UUID userId = UuidV7.next();
        String token = jwtService.issueAccessToken(userId, "dev@acme.io");

        ConsolePrincipal parsed = jwtService.parseAccessToken(token);
        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.email()).isEqualTo("dev@acme.io");

        ConsoleProperties otherDomain = new ConsoleProperties();
        otherDomain.getSecurity().setConsoleJwtSecret("completely-different-admin-secret-at-least-32-bytes");
        ConsoleJwtService otherVerifier = new ConsoleJwtService(otherDomain);
        assertThatThrownBy(() -> otherVerifier.parseAccessToken(token))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
}
