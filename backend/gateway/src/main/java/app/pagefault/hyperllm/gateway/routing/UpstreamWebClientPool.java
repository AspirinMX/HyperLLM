package app.pagefault.hyperllm.gateway.routing;

import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintains per-provider, per-EventLoop {@link ConnectionProvider} buckets and {@link WebClient}
 * instances bound to the gateway's shared Netty {@link LoopResources}.
 *
 * <p>Thread-per-Core affinity: when a downstream request arrives on server EventLoop {@code EL-k},
 * capturing {@link #resolveAffinitySlot(Thread)} at request entry and dispatching via
 * {@link #clientFor(String, int)} guarantees that the pooled upstream TCP socket also executes on
 * {@code EL-k}. Every SSE frame therefore flows {@code upstream recv -> translate -> downstream writev}
 * on the exact same EventLoop thread with zero cross-thread queue handoffs or {@code eventFdWrite}
 * wakeups.
 */
@Component
public class UpstreamWebClientPool {

    private static final int MAX_BUFFER_BYTES = 16 * 1024 * 1024;
    private static final ExchangeStrategies SHARED_STRATEGIES = ExchangeStrategies.builder()
            .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(MAX_BUFFER_BYTES))
            .build();

    private final GatewayProperties properties;
    private final LoopResources sharedLoopResources;
    private final boolean ownsLoopResources;
    private final EventLoop[] eventLoops;
    private final LoopResources[] pinnedLoops;
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final ConcurrentHashMap<String, List<ConnectionProvider>> poolsByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebClient[]> clientsByProvider = new ConcurrentHashMap<>();

    @Autowired
    public UpstreamWebClientPool(
            GatewayProperties properties,
            @Qualifier("gatewayLoopResources") LoopResources sharedLoopResources
    ) {
        this(properties, sharedLoopResources, false);
    }

    /** Standalone constructor for unit tests running without a Spring Boot server context. */
    public UpstreamWebClientPool(GatewayProperties properties) {
        this(
                properties,
                LoopResources.create("hyperllm-upstream", Math.max(2, Runtime.getRuntime().availableProcessors()), true),
                true
        );
    }

    private UpstreamWebClientPool(
            GatewayProperties properties,
            LoopResources sharedLoopResources,
            boolean ownsLoopResources
    ) {
        this.properties = properties;
        this.sharedLoopResources = sharedLoopResources;
        this.ownsLoopResources = ownsLoopResources;

        EventLoopGroup clientGroup = sharedLoopResources.onClient(true);
        List<EventLoop> loops = new ArrayList<>();
        for (EventExecutor exec : clientGroup) {
            if (exec instanceof EventLoop loop) {
                loops.add(loop);
            }
        }
        this.eventLoops = loops.toArray(new EventLoop[0]);
        this.pinnedLoops = new LoopResources[this.eventLoops.length];
        for (int i = 0; i < this.eventLoops.length; i++) {
            this.pinnedLoops[i] = new PinnedLoopResources(sharedLoopResources, clientGroup, this.eventLoops[i]);
        }
    }

    /**
     * Resolves the EventLoop affinity slot index for {@code thread} in O(workers) pointer comparisons
     * (4 compares on 4 vCPUs, zero allocation), or {@code -1} when called off-EventLoop.
     */
    public int resolveAffinitySlot(Thread thread) {
        if (thread == null || eventLoops.length == 0) {
            return -1;
        }
        for (int i = 0; i < eventLoops.length; i++) {
            if (eventLoops[i].inEventLoop(thread)) {
                return i;
            }
        }
        return -1;
    }

    public int eventLoopCount() {
        return eventLoops.length;
    }

    public WebClient clientFor(String providerCode) {
        return clientFor(providerCode, resolveAffinitySlot(Thread.currentThread()));
    }

    public WebClient clientFor(String providerCode, int affinitySlot) {
        String key = (providerCode == null || providerCode.isBlank()) ? "DEFAULT" : providerCode;
        WebClient[] buckets = clientsByProvider.computeIfAbsent(key, this::buildClientBuckets);
        if (buckets.length == 1) {
            return buckets[0];
        }
        int idx = (affinitySlot >= 0 && affinitySlot < buckets.length)
                ? affinitySlot
                : Math.floorMod(roundRobin.getAndIncrement(), buckets.length);
        return buckets[idx];
    }

    private WebClient[] buildClientBuckets(String providerCode) {
        int bucketCount = Math.max(1, pinnedLoops.length);
        int totalMax = properties.getUpstream().getMaxConnectionsPerProvider();
        // Allow 25% per-bucket headroom over an even split so mild EventLoop skew never triggers
        // premature pool acquisition waits while keeping total connections bounded.
        int perBucketMax = bucketCount == 1
                ? totalMax
                : Math.max(32, (int) Math.ceil((totalMax * 1.25) / bucketCount));

        WebClient[] buckets = new WebClient[bucketCount];
        List<ConnectionProvider> createdPools = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            ConnectionProvider provider = ConnectionProvider.builder("hyperllm-" + providerCode + "-el" + i)
                    .maxConnections(perBucketMax)
                    .maxIdleTime(Duration.ofSeconds(30))
                    .evictInBackground(Duration.ofSeconds(30))
                    .build();
            createdPools.add(provider);

            LoopResources loop = pinnedLoops.length > 0 ? pinnedLoops[i] : sharedLoopResources;
            HttpClient httpClient = HttpClient.create(provider)
                    .runOn(loop)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getUpstream().getConnectTimeoutMs())
                    .responseTimeout(Duration.ofSeconds(properties.getUpstream().getResponseTimeoutSeconds()));

            buckets[i] = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .exchangeStrategies(SHARED_STRATEGIES)
                    .build();
        }
        poolsByProvider.put(providerCode, createdPools);
        return buckets;
    }

    @PreDestroy
    public void shutdown() {
        poolsByProvider.values().forEach(list -> list.forEach(p -> p.disposeLater().subscribe()));
        poolsByProvider.clear();
        clientsByProvider.clear();
        if (ownsLoopResources) {
            sharedLoopResources.disposeLater().subscribe();
        }
    }

    /**
     * {@link LoopResources} view pinned to a single {@link EventLoop} from the shared parent group.
     * Because {@link EventLoop} extends {@link EventLoopGroup}, every channel opened via this view
     * registers strictly on {@code pinnedLoop} without spawning any new threads.
     */
    private static final class PinnedLoopResources implements LoopResources {

        private final LoopResources parent;
        private final EventLoopGroup parentClientGroup;
        private final EventLoop pinnedLoop;

        private PinnedLoopResources(LoopResources parent, EventLoopGroup parentClientGroup, EventLoop pinnedLoop) {
            this.parent = parent;
            this.parentClientGroup = parentClientGroup;
            this.pinnedLoop = pinnedLoop;
        }

        @Override
        public EventLoopGroup onClient(boolean useNative) {
            return pinnedLoop;
        }

        @Override
        public EventLoopGroup onServer(boolean useNative) {
            return pinnedLoop;
        }

        @Override
        public EventLoopGroup onServerSelect(boolean useNative) {
            return pinnedLoop;
        }

        @Override
        public <CHANNEL extends Channel> CHANNEL onChannel(Class<CHANNEL> channelType, EventLoopGroup group) {
            // Pass parentClientGroup (EpollEventLoopGroup / NioEventLoopGroup) rather than the single
            // EventLoop child so DefaultLoopResources selects EpollSocketChannel instead of falling back to NIO.
            return parent.onChannel(channelType, parentClientGroup);
        }

        @Override
        public <CHANNEL extends Channel> Class<? extends CHANNEL> onChannelClass(
                Class<CHANNEL> channelType, EventLoopGroup group) {
            return parent.onChannelClass(channelType, parentClientGroup);
        }

        @Override
        public boolean daemon() {
            return parent.daemon();
        }

        @Override
        public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
            return Mono.empty();
        }
    }
}
