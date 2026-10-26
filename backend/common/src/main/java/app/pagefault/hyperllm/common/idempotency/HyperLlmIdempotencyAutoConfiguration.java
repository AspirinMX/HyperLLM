package app.pagefault.hyperllm.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers one idempotency implementation for the whole platform, so the behaviour cannot drift
 * between the control plane and the data plane.
 *
 * <p>The Redis backed store is selected by {@link RedisIdempotencyAutoConfiguration}, which runs
 * first wherever a client exists; what remains here is the in-memory fallback plus the machinery
 * every module can use.
 */
@AutoConfiguration(after = RedisIdempotencyAutoConfiguration.class)
public class HyperLlmIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore();
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
    @ConditionalOnClass(name = {"reactor.core.publisher.Mono", "org.aspectj.lang.annotation.Aspect"})
    @ConditionalOnMissingBean
    public ReactiveIdempotentAspect reactiveIdempotentAspect(IdempotencyStore store) {
        return new ReactiveIdempotentAspect(store);
    }
}
