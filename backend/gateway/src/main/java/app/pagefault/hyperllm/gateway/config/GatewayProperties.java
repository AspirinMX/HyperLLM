package app.pagefault.hyperllm.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "hyperllm")
public class GatewayProperties {

    private Security security = new Security();
    private Cache cache = new Cache();
    private Upstream upstream = new Upstream();
    private Billing billing = new Billing();
    private Outbox outbox = new Outbox();

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

        /** Sliding TTL of the wallet and batch keys; every touch extends it by this much. */
        private long slidingTtlSeconds = 300;

        /** How long a key may go untouched between renewals; keeps PEXPIRE off the hot path. */
        private long renewThrottleSeconds = 60;
    }

    @Data
    public static class Upstream {
        private int connectTimeoutMs = 5_000;
        private int responseTimeoutSeconds = 120;
        private int maxRetryAttempts = 2;
        private int maxConnectionsPerProvider = 200;
    }

    /**
     * Pre-hold tuning. The switches exist so a bad rollout can fall back to Phase 1 post-charge
     * behaviour without a redeploy.
     */
    /**
     * Outbox write tuning. The gateway appends one row per settled request, so the write is shaped
     * as an append: a bounded queue drained by a single writer, never a statement per request.
     */
    @Data
    public static class Outbox {
        /** How long the writer waits for a batch to fill before flushing what it has. */
        private long flushIntervalMs = 20;
        /** Rows that make a batch full enough to flush early. */
        private int batchSize = 200;
        /** Upper bound on rows in one insert, so a burst cannot build an unbounded statement. */
        private int maxBatchRows = 500;
        /** Bound on queued rows; the depth is what a saturation alarm reads. */
        private int queueCapacity = 100_000;
        /** Queue depth ratio at which admission should stop rather than grow the backlog. */
        private double admissionPauseRatio = 0.5;
        /** After this long, a failing insert is abandoned and counted as a hole. */
        private long retryDeadlineSeconds = 600;
        /** Backoff between insert attempts. */
        private long retryBackoffMs = 200;
    }

    @Data
    public static class Billing {
        private long leaseTtlSeconds = 300;
        private long heartbeatMinIntervalSeconds = 15;
        /** How often the background sweep reclaims expired holds, in seconds. */
        private long reclaimSweepSeconds = 30;
        /** Holds reclaimed per sweep; the sweep runs again long before this becomes a backlog. */
        private int reclaimSweepBatch = 200;
        private int safetyFactorBps = 11500;
        private int defaultOutputTokens = 8192;
        private int lazyReclaimBatch = 3;
        private long packAvailabilityTtlSeconds = 300;
    }
}