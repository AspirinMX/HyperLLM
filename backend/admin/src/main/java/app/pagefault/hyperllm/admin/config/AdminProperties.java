package app.pagefault.hyperllm.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hyperllm")
public class AdminProperties {

    private Security security = new Security();
    private Admin admin = new Admin();
    private Analytics analytics = new Analytics();
    private Alerts alerts = new Alerts();
    private Reconciliation reconciliation = new Reconciliation();

    @Data
    public static class Security {
        private String adminJwtSecret;
        private String aesMasterKey;
        private String keyPepperV1;
        /** Pepper for redemption codes; falls back to the key pepper when absent. */
        private String voucherCodePepper;
        private boolean cookieSecure = false;
        private long accessTtlMinutes = 15;
        private long mfaChallengeTtlMinutes = 10;
        private long refreshTtlDays = 7;
    }

    @Data
    public static class Admin {
        private Mfa mfa = new Mfa();
        private Bootstrap bootstrap = new Bootstrap();
    }

    @Data
    public static class Mfa {
        private boolean required = true;
    }

    @Data
    public static class Bootstrap {
        private String email;
        private String password;
    }

    @Data
    public static class Reconciliation {
        private boolean enabled = true;
    }

    @Data
    public static class Alerts {
        private boolean enabled = true;
        /** Only for local development; production webhook targets must be HTTPS. */
        private boolean allowHttp = false;
        /** Allows a loopback or private target so a test can run a local receiver. */
        private boolean allowLoopback = false;
        private int samplerBatch = 500;
        private int deliveryBatch = 50;
        private int maxAttempts = 5;
        private String smtpHost = "";
        private int smtpPort = 587;
        private String smtpUsername = "";
        private String smtpPassword = "";
        private String mailFrom = "alerts@hyperllm.local";
        private boolean smtpStartTls = true;
    }

    @Data
    public static class Analytics {
        private boolean enabled = true;
        private String consumerGroup = "hyperllm-admin-requests";
        private int concurrency = 3;
        private int rollupHoursPerRun = 24;
        private ClickHouse clickhouse = new ClickHouse();
    }

    @Data
    public static class ClickHouse {
        private String url = "jdbc:clickhouse://localhost:8123/hyperllm";
        private String username = "hyperllm";
        private String password = "hyperllm_dev_password";
        private int poolSize = 4;
        private int connectTimeoutMs = 3_000;
    }
}