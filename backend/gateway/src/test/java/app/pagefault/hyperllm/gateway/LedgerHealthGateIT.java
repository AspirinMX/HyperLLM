package app.pagefault.hyperllm.gateway;

import app.pagefault.hyperllm.common.billing.LedgerLagSignal;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.gateway.cache.LedgerHealthGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four states the gate has to tell apart. The third one is the subtle case: a signal is present
 * but stale, which is the same as the monitor being gone.
 */
@Tag("integration")
class LedgerHealthGateIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private LettuceConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private ObjectMapper objectMapper;
    private LedgerHealthGate gate;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
        redis.execute(connection -> connection.serverCommands().flushAll()).blockLast();
        objectMapper = new ObjectMapper();
        gate = new LedgerHealthGate(redis, objectMapper);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void blocksNothingWhenTheFeatureWasNeverDeployed() {
        assertThat(gate.current().block(Duration.ofSeconds(2)).degraded()).isFalse();
    }

    @Test
    void blocksARebuildWhileTheConsumerIsBehind() {
        publish(LedgerLagSignal.degraded(9_000L, 120d, System.currentTimeMillis()), true);

        assertThat(gate.current().block(Duration.ofSeconds(2)).degraded()).isTrue();
    }

    @Test
    void letsARebuildThroughOnceTheSignalStoppedBeingRefreshed() {
        publish(LedgerLagSignal.degraded(9_000L, 120d, System.currentTimeMillis() - 200_000L), true);

        assertThat(gate.current().block(Duration.ofSeconds(2)).degraded()).isFalse();
    }

    @Test
    void letsARebuildThroughWhenOnlyTheMarkerRemains() {
        redis.opsForValue().set(GatewayRedisKeys.ledgerLagArmed(), "1").block();

        assertThat(gate.current().block(Duration.ofSeconds(2)).degraded()).isFalse();
    }

    @Test
    void letsARebuildThroughWhenTheConsumerIsKeepingUp() {
        publish(LedgerLagSignal.healthy(10L, 0.5d, System.currentTimeMillis()), true);

        assertThat(gate.current().block(Duration.ofSeconds(2)).degraded()).isFalse();
    }

    private void publish(LedgerLagSignal signal, boolean armed) {
        try {
            redis.opsForValue()
                    .set(GatewayRedisKeys.ledgerLagSignal(), objectMapper.writeValueAsString(signal))
                    .block();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        if (armed) {
            redis.opsForValue().set(GatewayRedisKeys.ledgerLagArmed(), "1").block();
        }
    }
}
