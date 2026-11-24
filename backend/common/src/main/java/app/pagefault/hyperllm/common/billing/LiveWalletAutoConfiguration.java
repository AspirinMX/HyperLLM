package app.pagefault.hyperllm.common.billing;

import app.pagefault.hyperllm.common.cache.LuaScripts;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Registers the live balance read wherever a Redis client exists.
 *
 * <p>The script and the reader are both defined once, so the console and the operator console can
 * never answer "what can this tenant spend" in two different ways.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
public class LiveWalletAutoConfiguration {

    @SuppressWarnings("rawtypes")
    @Bean
    @ConditionalOnMissingBean(name = "walletSnapshotScript")
    public RedisScript<List> walletSnapshotScript() {
        return LuaScripts.loadClasspath("lua/prehold_snapshot.lua", List.class);
    }

    /** Skipped wherever there is no Redis client, so a module without one still boots. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public LiveWalletReader liveWalletReader(
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("walletSnapshotScript") RedisScript<List> script
    ) {
        return new LiveWalletReader(redisTemplate, script);
    }
}
