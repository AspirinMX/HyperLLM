package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ChannelCapacityServiceTest {

    @Test
    void bypassesRedisCompletelyWhenChannelHasNoLimits() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ChannelCapacityService service = new ChannelCapacityService(redis, new GatewayProperties());
        GatewayChannelCandidate unlimited = new GatewayChannelCandidate(
                UUID.randomUUID(), UUID.randomUUID(), "unlimited", "helsinki",
                "OPENAI", ProtocolFamily.OPENAI, AuthScheme.BEARER, "http://127.0.0.1:18080/v1",
                "gpt-bench", (short) 1, 100, 10_000, new byte[0], (short) 1, null,
                null, null, null);

        assertThat(service.hasLimits(unlimited)).isFalse();
        assertThat(service.hasConcurrencyLimit(unlimited)).isFalse();
        assertThat(service.acquire(unlimited.channelId(), "attempt-1", unlimited, 100L).block()).isEqualTo("OK");
        service.release(unlimited.channelId(), "attempt-1", unlimited).block();

        verifyNoInteractions(redis);
    }

    @Test
    void skipsReleaseWhenOnlyRpmLimitConfigured() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ChannelCapacityService service = new ChannelCapacityService(redis, new GatewayProperties());
        GatewayChannelCandidate rpmOnly = new GatewayChannelCandidate(
                UUID.randomUUID(), UUID.randomUUID(), "rpm-only", "helsinki",
                "OPENAI", ProtocolFamily.OPENAI, AuthScheme.BEARER, "http://127.0.0.1:18080/v1",
                "gpt-bench", (short) 1, 100, 10_000, new byte[0], (short) 1, null,
                60, null, null);

        assertThat(service.hasLimits(rpmOnly)).isTrue();
        assertThat(service.hasConcurrencyLimit(rpmOnly)).isFalse();
        service.release(rpmOnly.channelId(), "attempt-1", rpmOnly).block();

        verifyNoInteractions(redis);
    }
}
