package app.pagefault.hyperllm.gateway.config;

import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.protocol.adapter.ProtocolRegistry;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    @Bean
    public AesGcmCipher aesGcmCipher(GatewayProperties properties) {
        return AesGcmCipher.fromBase64((byte) 1, properties.getSecurity().getAesMasterKey());
    }

    @Bean
    public TokenEstimator tokenEstimator() {
        return new TokenEstimator();
    }

    @Bean
    public ProtocolRegistry protocolRegistry(ObjectMapper objectMapper, TokenEstimator tokenEstimator) {
        return new ProtocolRegistry(objectMapper, tokenEstimator);
    }

    @Bean
    public ProtocolErrorFormatter protocolErrorFormatter(ObjectMapper objectMapper) {
        return new ProtocolErrorFormatter(objectMapper);
    }

    @Bean
    public ProtocolTranslator protocolTranslator(
            ObjectMapper objectMapper,
            TokenEstimator tokenEstimator,
            ProtocolErrorFormatter protocolErrorFormatter
    ) {
        return new ProtocolTranslator(objectMapper, tokenEstimator, protocolErrorFormatter);
    }

    @Bean(name = "domainFallbackScheduler", destroyMethod = "dispose")
    public Scheduler domainFallbackScheduler() {
        return Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor(), "domain-fallback");
    }

    @SuppressWarnings("rawtypes")
    @Bean(name = "preholdSnapshotLuaScript")
    public RedisScript<java.util.List> preholdSnapshotLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_snapshot.lua"), java.util.List.class);
    }

    @SuppressWarnings("rawtypes")
    @Bean(name = "loadTenantLuaScript")
    public RedisScript<java.util.List> loadTenantLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/load_tenant.lua"), java.util.List.class);
    }

    @Bean(name = "preholdAdmitLuaScript")
    public RedisScript<String> preholdAdmitLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_admit.lua"), String.class);
    }

    @Bean(name = "preholdRenewLuaScript")
    public RedisScript<Long> preholdRenewLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_renew.lua"), Long.class);
    }

    @SuppressWarnings("rawtypes")
    @Bean(name = "preholdSettleLuaScript")
    public RedisScript<java.util.List> preholdSettleLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_settle.lua"), java.util.List.class);
    }

    @Bean(name = "preholdServedLuaScript")
    public RedisScript<Long> preholdServedLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_mark_served.lua"), Long.class);
    }

    @Bean(name = "budgetInitLuaScript")
    public RedisScript<String> budgetInitLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/budget_init.lua"), String.class);
    }

}