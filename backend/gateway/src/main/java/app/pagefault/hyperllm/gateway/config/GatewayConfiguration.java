package app.pagefault.hyperllm.gateway.config;

import app.pagefault.hyperllm.common.cache.LuaScripts;
import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DefaultEventLoopGroupProvider;
import io.lettuce.core.resource.EventLoopGroupProvider;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
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
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    /**
     * Single shared Netty I/O {@link LoopResources} owned by Spring Boot's {@link ReactorResourceFactory}.
     * Reused across the Reactor Netty HTTP server, {@code UpstreamWebClientPool} (with per-EventLoop
     * affinity buckets), and Lettuce Redis I/O so a 4-vCPU gateway runs exactly 4 Netty EventLoop
     * threads instead of 12 (4 server + 4 upstream + 4 Lettuce) competing for the same cores.
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

    @Bean(destroyMethod = "shutdown")
    public ClientResources lettuceClientResources(
            @Qualifier("gatewayLoopResources") LoopResources gatewayLoopResources
    ) {
        EventLoopGroup sharedGroup = gatewayLoopResources.onClient(true);
        return DefaultClientResources.builder()
                .eventLoopGroupProvider(new SharedEventLoopGroupProvider(sharedGroup))
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
                    "HyperLLM Gateway runtime: os={} arch={} cpus={} maxHeapMB={} nettyEpoll={} epollCause={} sharedEventLoops={} upstreamMaxConnPerProvider={}",
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

    /**
     * Exposes the gateway's shared Netty {@link EventLoopGroup} to Lettuce without letting Lettuce
     * shut down the HTTP server's EventLoopGroup on client close.
     */
    static final class SharedEventLoopGroupProvider implements EventLoopGroupProvider {

        private final EventLoopGroup sharedGroup;
        private final int poolSize;
        private volatile DefaultEventLoopGroupProvider fallback;

        SharedEventLoopGroupProvider(EventLoopGroup sharedGroup) {
            this.sharedGroup = sharedGroup;
            this.poolSize = countExecutors(sharedGroup);
        }

        @Override
        public <T extends EventLoopGroup> T allocate(Class<T> type) {
            if (type.isInstance(sharedGroup)) {
                return type.cast(sharedGroup);
            }
            return fallback().allocate(type);
        }

        @Override
        public int threadPoolSize() {
            return poolSize;
        }

        @Override
        public Future<Boolean> release(EventExecutorGroup eventLoopGroup, long quietPeriod, long timeout, TimeUnit unit) {
            if (eventLoopGroup == sharedGroup) {
                return ImmediateEventExecutor.INSTANCE.newSucceededFuture(Boolean.TRUE);
            }
            DefaultEventLoopGroupProvider fb = fallback;
            return fb != null
                    ? fb.release(eventLoopGroup, quietPeriod, timeout, unit)
                    : ImmediateEventExecutor.INSTANCE.newSucceededFuture(Boolean.TRUE);
        }

        @Override
        public Future<Boolean> shutdown(long quietPeriod, long timeout, TimeUnit unit) {
            DefaultEventLoopGroupProvider fb = fallback;
            return fb != null
                    ? fb.shutdown(quietPeriod, timeout, unit)
                    : ImmediateEventExecutor.INSTANCE.newSucceededFuture(Boolean.TRUE);
        }

        private DefaultEventLoopGroupProvider fallback() {
            DefaultEventLoopGroupProvider fb = fallback;
            if (fb == null) {
                synchronized (this) {
                    fb = fallback;
                    if (fb == null) {
                        fb = new DefaultEventLoopGroupProvider(poolSize);
                        fallback = fb;
                    }
                }
            }
            return fb;
        }
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