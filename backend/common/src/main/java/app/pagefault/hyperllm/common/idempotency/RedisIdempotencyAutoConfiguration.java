package app.pagefault.hyperllm.common.idempotency;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Selects the Redis backed store wherever a Redis client exists.
 *
 * <p>It is a class of its own, guarded at class level, so a module without the Redis client never
 * loads it: keeping the choice inside the shared configuration would have forced every module to
 * carry the class just to discover it cannot use it.
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
public class RedisIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore redisIdempotencyStore(StringRedisTemplate redisTemplate) {
        return new RedisIdempotencyStore(redisTemplate);
    }
}
