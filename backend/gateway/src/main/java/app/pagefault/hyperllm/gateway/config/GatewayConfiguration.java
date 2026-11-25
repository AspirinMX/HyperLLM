package app.pagefault.hyperllm.gateway.config;

import app.pagefault.hyperllm.common.cache.LuaScripts;
import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.protocol.adapter.ProtocolRegistry;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.epoll.Epoll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    @Bean
    public ApplicationRunner gatewayRuntimeDiagnostics(GatewayProperties properties) {
        return args -> {
            boolean epollAvailable = Epoll.isAvailable();
            Throwable cause = epollAvailable ? null : Epoll.unavailabilityCause();
            log.info(
                    "HyperLLM Gateway runtime: os={} arch={} cpus={} maxHeapMB={} nettyEpoll={} epollCause={} upstreamMaxConnPerProvider={}",
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"),
                    Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                    epollAvailable,
                    cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "none",
                    properties.getUpstream().getMaxConnectionsPerProvider()
            );
        };
    }

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

    @Bean(name = "protocolTranslationScheduler", destroyMethod = "dispose")
    public Scheduler protocolTranslationScheduler() {
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        return Schedulers.newParallel("protocol-translate", workers, true);
    }

    @SuppressWarnings("rawtypes")
    @Bean(name = "preholdSnapshotLuaScript")
    public RedisScript<java.util.List> preholdSnapshotLuaScript() {
        return LuaScripts.loadClasspath("lua/prehold_snapshot.lua", java.util.List.class);
    }

    @SuppressWarnings("rawtypes")
    @Bean(name = "loadTenantLuaScript")
    public RedisScript<java.util.List> loadTenantLuaScript() {
        return LuaScripts.loadClasspath("lua/load_tenant.lua", java.util.List.class);
    }

    @Bean(name = "preholdAdmitLuaScript")
    public RedisScript<String> preholdAdmitLuaScript() {
        return LuaScripts.loadClasspath("lua/prehold_admit.lua", String.class);
    }

    @Bean(name = "preholdRenewLuaScript")
    public RedisScript<Long> preholdRenewLuaScript() {
        return LuaScripts.loadClasspath("lua/prehold_renew.lua", Long.class);
    }

    @SuppressWarnings("rawtypes")
    @Bean(name = "preholdSettleLuaScript")
    public RedisScript<java.util.List> preholdSettleLuaScript() {
        return LuaScripts.loadClasspath("lua/prehold_settle.lua", java.util.List.class);
    }

    @Bean(name = "preholdServedLuaScript")
    public RedisScript<Long> preholdServedLuaScript() {
        return LuaScripts.loadClasspath("lua/prehold_mark_served.lua", Long.class);
    }

    @Bean(name = "budgetInitLuaScript")
    public RedisScript<String> budgetInitLuaScript() {
        return LuaScripts.loadClasspath("lua/budget_init.lua", String.class);
    }

}