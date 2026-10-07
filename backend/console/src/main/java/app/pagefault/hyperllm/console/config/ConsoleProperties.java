package app.pagefault.hyperllm.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hyperllm")
public class ConsoleProperties {

    private Security security = new Security();
    private Billing billing = new Billing();
    private Auth auth = new Auth();

    @Data
    public static class Security {
        private String consoleJwtSecret;
        private String aesMasterKey;
        private String keyPepperV1;
        private boolean cookieSecure = false;
        private long accessTtlMinutes = 15;
        private long refreshTtlDays = 14;
    }

    @Data
    public static class Billing {
        private long signupBonusMicros = 5_000_000L;
    }

    @Data
    public static class Auth {
        private boolean otpDebugEchoEnabled = false;
    }
}
