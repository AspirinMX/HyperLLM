package app.pagefault.hyperllm.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hyperllm")
public class ConsoleProperties {

    private Security security = new Security();
    private Billing billing = new Billing();
    private Auth auth = new Auth();
    private Alerts alerts = new Alerts();
    private Analytics analytics = new Analytics();

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

    @Data
    public static class Alerts {
        /** Only for local development; production webhook targets must be HTTPS. */
        private boolean allowHttp = false;
    }

    @Data
    public static class Analytics {
        private String url = "jdbc:clickhouse://localhost:8123/hyperllm";
        private String username = "hyperllm";
        private String password = "hyperllm_dev_password";
        private int poolSize = 4;
        private int connectTimeoutMs = 3_000;
    }
}
