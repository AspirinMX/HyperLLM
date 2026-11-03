package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Per-attempt capacity of one upstream channel, shared by every tenant routed to it. */
@Component
@RequiredArgsConstructor
public class ChannelCapacityService {

    private static final RedisScript<String> CAPACITY =
            RedisScript.of(new ClassPathResource("lua/channel_capacity.lua"), String.class);

    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties properties;

    public Mono<String> acquire(UUID channelId, String attemptId, GatewayChannelCandidate candidate,
                                long estimatedTokens) {
        long ttlMillis = Duration.ofSeconds(properties.getBilling().getLeaseTtlSeconds()).toMillis();
        List<String> keys = List.of(
                GatewayRedisKeys.channelRateWindow(channelId),
                GatewayRedisKeys.channelRateCounts(channelId),
                GatewayRedisKeys.channelRateTokens(channelId),
                GatewayRedisKeys.channelConcurrency(channelId));
        List<String> args = List.of(
                attemptId, Long.toString(System.currentTimeMillis()), Long.toString(ttlMillis),
                limit(candidate.channelRpmLimit()), limit(candidate.channelTpmLimit()),
                limit(candidate.channelMaxConcurrency()), Long.toString(Math.max(0L, estimatedTokens)),
                "60", "0");
        return redis.execute(CAPACITY, keys, args).next().defaultIfEmpty("RATE_LIMIT");
    }

    public Mono<Void> release(UUID channelId, String attemptId) {
        List<String> keys = List.of(
                GatewayRedisKeys.channelRateWindow(channelId),
                GatewayRedisKeys.channelRateCounts(channelId),
                GatewayRedisKeys.channelRateTokens(channelId),
                GatewayRedisKeys.channelConcurrency(channelId));
        List<String> args = List.of(attemptId, "0", "0", "0", "0", "0", "0", "60", "1");
        return redis.execute(CAPACITY, keys, args).then();
    }

    private static String limit(Integer value) {
        return value != null && value > 0 ? value.toString() : "0";
    }
}
