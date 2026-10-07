package app.pagefault.hyperllm.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hyperllm")
public class GatewayProperties {

    private Security security = new Security();
    private Cache cache = new Cache();
    private Upstream upstream = new Upstream();

    @Data
    public static class Security {
        private String aesMasterKey;
        private String keyPepperV1;
        private String keyPepperV2;
    }

    @Data
    public static class Cache {
        private long l1KeyTtlSeconds = 60;
        private long l1RouteTtlSeconds = 60;
        private long l2KeyTtlSeconds = 1800;
        private long l2RouteTtlSeconds = 1800;
        private long maxEntries = 10_000;
    }

    @Data
    public static class Upstream {
        private int connectTimeoutMs = 5_000;
        private int responseTimeoutSeconds = 120;
        private int maxRetryAttempts = 2;
        private int maxConnectionsPerProvider = 200;
    }
}
