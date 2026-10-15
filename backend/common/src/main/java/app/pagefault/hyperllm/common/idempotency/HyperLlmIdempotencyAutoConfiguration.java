package app.pagefault.hyperllm.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers one idempotency implementation for the whole platform, so the behaviour cannot drift
 * between the control plane and the data plane.
 *
 * <p>The store is chosen at creation time through an {@link ObjectProvider} rather than with
 * {@code @ConditionalOnBean}: auto-configurations have no guaranteed ordering relative to the Redis
 * one, and a condition evaluated too early would silently downgrade the store to process local.
 */
@AutoConfiguration
@ConditionalOnClass(IdempotencyStore.class)
public class HyperLlmIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore idempotencyStore(ObjectProvider<StringRedisTemplate> redisTemplate) {
        StringRedisTemplate template = redisTemplate.getIfAvailable();
        return template != null ? new RedisIdempotencyStore(template) : new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyExecutor idempotencyExecutor(IdempotencyStore store) {
        return new IdempotencyExecutor(store);
    }

    @Bean
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(IdempotencyExecutor executor, ObjectMapper objectMapper) {
        return new IdempotentAspect(executor, objectMapper);
    }

    @Bean
    @ConditionalOnClass(name = "reactor.core.publisher.Mono")
    @ConditionalOnMissingBean
    public ReactiveIdempotentAspect reactiveIdempotentAspect(IdempotencyStore store) {
        return new ReactiveIdempotentAspect(store);
    }
}
