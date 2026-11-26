package app.pagefault.hyperllm.gateway.config;

import app.pagefault.hyperllm.common.cache.LuaScripts;
import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.client.ReactorResourceFactory;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.resources.LoopResources;

import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    /**
     * Single shared Netty I/O {@link LoopResources} owned by Spring Boot's {@link ReactorResourceFactory}.
     * Reused across the Reactor Netty HTTP server and {@code UpstreamWebClientPool} (with per-EventLoop
     * affinity buckets) so downstream and upstream SSE sockets share the exact same 4 EventLoop threads
     * on a 4-vCPU host without cross-thread frame handoffs.
     */
    @Bean
    public ReactorResourceFactory gatewayReactorResourceFactory() {
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        ReactorResourceFactory factory = new ReactorResourceFactory();
        factory.setUseGlobalResources(false);
        factory.setLoopResourcesSupplier(() -> LoopResources.create("hyperllm-io", workers, true));
        return factory;
    }

    @Bean(name = "gatewayLoopResources")
    public LoopResources gatewayLoopResources(ReactorResourceFactory gatewayReactorResourceFactory) {
        return gatewayReactorResourceFactory.getLoopResources();
    }

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> gatewayNettyServerCustomizer() {
        return factory -> factory.addServerCustomizers(
                httpServer -> httpServer.childOption(ChannelOption.TCP_NODELAY, true));
    }

    /**
     * Dedicates a single lightweight Netty EventLoop thread to Lettuce Redis control-plane RPCs
     * (entitlement mirror, pre-hold admit, markServed, settle). Keeping Redis I/O isolated from
     * the saturated 4-thread SSE data-plane EventLoops prevents head-of-line queuing behind
     * thousands of active SSE streams from inflating pre-TTFT latency while avoiding Lettuce's
     * default multi-thread oversubscription.
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources lettuceClientResources() {
        return DefaultClientResources.builder()
                .ioThreadPoolSize(2)
                .computationThreadPoolSize(2)
                .build();
    }

    @Bean
    public ApplicationRunner gatewayRuntimeDiagnostics(
            GatewayProperties properties,
            @Qualifier("gatewayLoopResources") LoopResources gatewayLoopResources
    ) {
        return args -> {
            boolean epollAvailable = Epoll.isAvailable();
            Throwable cause = epollAvailable ? null : Epoll.unavailabilityCause();
            int loopCount = countExecutors(gatewayLoopResources.onClient(true));
            log.info(
                    "HyperLLM Gateway runtime: os={} arch={} cpus={} maxHeapMB={} nettyEpoll={} epollCause={} sharedEventLoops={} lettuceIoThreads=1 upstreamMaxConnPerProvider={}",
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"),
                    Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                    epollAvailable,
                    cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "none",
                    loopCount,
                    properties.getUpstream().getMaxConnectionsPerProvider()
            );
        };
    }

    private static int countExecutors(EventLoopGroup group) {
        int count = 0;
        for (EventExecutor ignored : group) {
            count++;
        }
        return Math.max(1, count);
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
    public Scheduler protocolTranslationScheduler(GatewayProperties properties) {
        int configured = properties.getUpstream().getTranslationWorkers();
        int workers = configured > 0
                ? configured
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
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