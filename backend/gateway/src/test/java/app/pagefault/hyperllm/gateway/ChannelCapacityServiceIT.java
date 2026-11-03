package app.pagefault.hyperllm.gateway;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.gateway.billing.ChannelCapacityService;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ChannelCapacityServiceIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private LettuceConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private ChannelCapacityService service;
    private UUID channelId;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
        redis.execute(connection -> connection.serverCommands().flushAll()).blockLast();
        service = new ChannelCapacityService(redis, new GatewayProperties());
        channelId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void sharesOneRollingWindowAcrossTenants() {
        GatewayChannelCandidate candidate = candidate(2, null, null);

        assertThat(service.acquire(channelId, "a1", candidate, 10).block()).isEqualTo("OK");
        assertThat(service.acquire(channelId, "a2", candidate, 10).block()).isEqualTo("OK");
        assertThat(service.acquire(channelId, "a3", candidate, 10).block())
                .as("a third attempt in the same window exceeds the channel RPM")
                .isEqualTo("RATE_LIMIT");
        assertThat(redis.opsForHash().get(GatewayRedisKeys.channelRateCounts(channelId),
                String.valueOf(System.currentTimeMillis() / 1000L)).block()).isEqualTo("2");
    }

    @Test
    void reservesTokensAndRejectsWithoutTakingAConcurrencyLease() {
        GatewayChannelCandidate candidate = candidate(null, 100, 1);

        assertThat(service.acquire(channelId, "a1", candidate, 60).block()).isEqualTo("OK");
        assertThat(service.acquire(channelId, "a2", candidate, 60).block()).isEqualTo("RATE_LIMIT");
        assertThat(redis.opsForValue().get(GatewayRedisKeys.channelConcurrency(channelId)).block())
                .as("a token rejection must not leave a concurrency lease behind")
                .isEqualTo("1");

        service.release(channelId, "a1").block();
        assertThat(redis.opsForValue().get(GatewayRedisKeys.channelConcurrency(channelId)).block())
                .isEqualTo("0");
    }

    @Test
    void enforcesConcurrencyAndReleasesOnDemand() {
        GatewayChannelCandidate candidate = candidate(null, null, 1);

        assertThat(service.acquire(channelId, "a1", candidate, 0).block()).isEqualTo("OK");
        assertThat(service.acquire(channelId, "a2", candidate, 0).block()).isEqualTo("CONCURRENCY_LIMIT");
        service.release(channelId, "a1").block();
        assertThat(service.acquire(channelId, "a2", candidate, 0).block()).isEqualTo("OK");
    }

    private static GatewayChannelCandidate candidate(Integer rpm, Integer tpm, Integer concurrency) {
        return new GatewayChannelCandidate(UUID.randomUUID(), UUID.randomUUID(), "channel", "us",
                "openai", ProtocolFamily.OPENAI, AuthScheme.X_API_KEY, "http://upstream",
                "gpt-4o", (short) 1, 1, 0, new byte[0], (short) 1, null, rpm, tpm, concurrency);
    }
}
