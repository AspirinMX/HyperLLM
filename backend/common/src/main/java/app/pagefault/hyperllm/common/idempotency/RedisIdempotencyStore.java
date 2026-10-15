package app.pagefault.hyperllm.common.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis backed store. Every operation is a single command or script, so no state transition is ever
 * split across round trips.
 *
 * <p>Values carry a one character state prefix: {@code P} means a claim is in flight, {@code R}
 * means the call completed and the remainder is the replayable payload.
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

    static final String KEY_PREFIX = "idem:";

    private static final String CLAIM_LUA = """
            local current = redis.call('GET', KEYS[1])
            if current then
                if string.sub(current, 1, 1) == 'R' then
                    return 2
                end
                return 1
            end
            redis.call('SET', KEYS[1], 'P', 'PX', ARGV[1])
            return 0
            """;

    private static final RedisScript<Long> CLAIM = new DefaultRedisScript<>(CLAIM_LUA, Long.class);

    private final StringRedisTemplate redis;

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public AcquireStatus tryAcquire(String key, Duration inFlightTtl) {
        Long outcome = redis.execute(CLAIM, List.of(redisKey(key)), List.of(String.valueOf(inFlightTtl.toMillis())));
        if (outcome == null) {
            return AcquireStatus.IN_FLIGHT;
        }
        return switch (outcome.intValue()) {
            case 0 -> AcquireStatus.ACQUIRED;
            case 2 -> AcquireStatus.COMPLETED;
            default -> AcquireStatus.IN_FLIGHT;
        };
    }

    @Override
    public void complete(String key, String encodedResult, Duration resultTtl) {
        redis.opsForValue().set(redisKey(key), "R" + encodedResult, resultTtl);
    }

    @Override
    public void release(String key) {
        redis.delete(redisKey(key));
    }

    @Override
    public Optional<String> result(String key) {
        String stored = redis.opsForValue().get(redisKey(key));
        if (stored == null || stored.isEmpty() || stored.charAt(0) != 'R') {
            return Optional.empty();
        }
        return Optional.of(stored.substring(1));
    }

    private static String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
