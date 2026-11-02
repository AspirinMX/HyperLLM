package app.pagefault.hyperllm.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hyperllm")
public class AdminProperties {

    private Security security = new Security();
    private Admin admin = new Admin();

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
}