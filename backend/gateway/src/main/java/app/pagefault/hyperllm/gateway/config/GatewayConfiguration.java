package app.pagefault.hyperllm.gateway.config;

import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.protocol.adapter.ProtocolRegistry;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
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
    public ProtocolRegistry protocolRegistry(ObjectMapper objectMapper) {
        return new ProtocolRegistry(objectMapper);
    }

    @Bean
    public ProtocolErrorFormatter protocolErrorFormatter(ObjectMapper objectMapper) {
        return new ProtocolErrorFormatter(objectMapper);
    }

    @Bean(name = "domainFallbackScheduler", destroyMethod = "dispose")
    public Scheduler domainFallbackScheduler() {
        return Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor(), "domain-fallback");
    }

    @Bean
    public RedisScript<String> admissionLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/admission.lua"), String.class);
    }

    @SuppressWarnings("rawtypes")
    @Bean
    public RedisScript<java.util.List> deductWalletLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/deduct_wallet.lua"), java.util.List.class);
    }

    @SuppressWarnings("rawtypes")
    @Bean
    public RedisScript<java.util.List> deductWalletSplitLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/deduct_wallet_split.lua"), java.util.List.class);
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

    @Bean(name = "preholdReleaseLuaScript")
    public RedisScript<String> preholdReleaseLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_release.lua"), String.class);
    }

    @Bean(name = "preholdReclaimLuaScript")
    public RedisScript<String> preholdReclaimLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_reclaim.lua"), String.class);
    }

    @Bean
    public RedisScript<Long> releaseConcurrencyLuaScript() {
        return RedisScript.of(new ClassPathResource("lua/release_concurrency.lua"), Long.class);
    }
}