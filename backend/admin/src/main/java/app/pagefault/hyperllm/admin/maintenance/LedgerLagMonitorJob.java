package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.common.billing.LedgerLagSignal;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes how far the ledger consumer is behind, so the gateway can tell a rebuild that is safe
 * from one that would overwrite live state with a stale snapshot.
 *
 * <p>The measurement is record lag taken from the broker, not from the consumer's own metrics: every
 * partition counts, including the ones no instance happens to be consuming right now, and nothing has
 * to be instrumented in the data plane to get it.
 *
 * <p>The first sample of a run cannot tell a rate from nothing, so the monitor keeps the previous
 * reading and reports a drain time only once it has two.
 */
@Slf4j
@Component
public class LedgerLagMonitorJob {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String bootstrapServers;
    private final String consumerGroup;
    private final long recordsThreshold;
    private final double drainSecondsThreshold;
    private final long signalTtlSeconds;
    private final ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry;

    private AdminClient adminClient;
    private Long previousLag;
    private long previousSampleAt;
    private volatile double lastLevel = 0d;

    public LedgerLagMonitorJob(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:hyperllm-admin-billing}") String consumerGroup,
            @Value("${hyperllm.billing.lag-monitor.records-threshold:5000}") long recordsThreshold,
            @Value("${hyperllm.billing.lag-monitor.drain-seconds-threshold:60}") double drainSecondsThreshold,
            @Value("${hyperllm.billing.lag-monitor.signal-ttl-seconds:60}") long signalTtlSeconds,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.bootstrapServers = bootstrapServers;
        this.consumerGroup = consumerGroup;
        this.recordsThreshold = recordsThreshold;
        this.drainSecondsThreshold = drainSecondsThreshold;
        this.signalTtlSeconds = signalTtlSeconds;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${hyperllm.billing.lag-monitor.interval:PT15S}")
    public void sample() {
        try {
            long lag = currentLag();
            long now = System.currentTimeMillis();
            double rate = 0d;
            if (previousLag != null && now > previousSampleAt) {
                double seconds = (now - previousSampleAt) / 1000d;
                rate = Math.max(0d, (previousLag - lag) / Math.max(seconds, 0.001d));
            }
            previousLag = lag;
            previousSampleAt = now;
            double drain = LedgerLagLevel.drainSeconds(lag, rate);
            boolean degraded = LedgerLagLevel.isDegraded(lag, drain, recordsThreshold, drainSecondsThreshold);
            publish(degraded ? LedgerLagSignal.degraded(lag, drain, now)
                    : LedgerLagSignal.healthy(lag, drain, now));
        } catch (Exception ex) {
            // A broker hiccup must not erase the last signal: leaving it to expire is the same as
            // telling the gateway the monitor stopped, which it already knows how to handle.
            log.warn("cannot sample ledger lag", ex);
        }
    }

    /** Writes the signal and the marker that tells a missing signal from an undeployed feature. */
    void publish(LedgerLagSignal signal) {
        try {
            redisTemplate.opsForValue().set(
                    GatewayRedisKeys.ledgerLagSignal(),
                    objectMapper.writeValueAsString(signal),
                    Duration.ofSeconds(signalTtlSeconds));
            redisTemplate.opsForValue().set(GatewayRedisKeys.ledgerLagArmed(), "1");
            lastLevel = signal.degraded() ? 1d : 0d;
            io.micrometer.core.instrument.MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                registry.gauge("hyperllm_ledger_lag_level", this, job -> job.lastLevel);
            }
        } catch (Exception ex) {
            log.warn("cannot publish ledger lag signal", ex);
        }
    }

    /** Sum of end offsets minus committed offsets across every partition of the group. */
    private long currentLag() throws Exception {
        AdminClient client = client();
        Map<TopicPartition, OffsetAndMetadata> committed =
                client.listConsumerGroupOffsets(consumerGroup).partitionsToOffsetAndMetadata()
                        .get(10, TimeUnit.SECONDS);
        if (committed.isEmpty()) {
            return 0L;
        }
        Map<TopicPartition, OffsetSpec> specs = new HashMap<>();
        committed.keySet().forEach(tp -> specs.put(tp, OffsetSpec.latest()));
        ListOffsetsResult result = client.listOffsets(specs);
        long lag = 0L;
        for (TopicPartition partition : committed.keySet()) {
            long end = result.partitionResult(partition).get(10, TimeUnit.SECONDS).offset();
            long position = committed.get(partition).offset();
            lag += Math.max(0L, end - position);
        }
        return lag;
    }

    private synchronized AdminClient client() {
        if (adminClient == null) {
            adminClient = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        }
        return adminClient;
    }

    @PreDestroy
    void close() {
        if (adminClient != null) {
            adminClient.close(Duration.ofSeconds(2));
        }
    }
}
