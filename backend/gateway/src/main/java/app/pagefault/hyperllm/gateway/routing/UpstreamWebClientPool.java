package app.pagefault.hyperllm.gateway.routing;

import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains isolated Reactor Netty {@link ConnectionProvider} pools and {@link WebClient} instances
 * per upstream provider code, sharing a single bounded {@link LoopResources} across providers.
 */
@Component
public class UpstreamWebClientPool {

    private static final int MAX_BUFFER_BYTES = 16 * 1024 * 1024;

    private final GatewayProperties properties;
    private final LoopResources sharedLoopResources;
    private final ConcurrentHashMap<String, ConnectionProvider> pools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebClient> clients = new ConcurrentHashMap<>();

    public UpstreamWebClientPool(GatewayProperties properties) {
        this.properties = properties;
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.sharedLoopResources = LoopResources.create("hyperllm-upstream", workers, true);
    }

    public WebClient clientFor(String providerCode) {
        String key = (providerCode == null || providerCode.isBlank()) ? "DEFAULT" : providerCode;
        return clients.computeIfAbsent(key, this::buildClient);
    }

    private WebClient buildClient(String providerCode) {
        ConnectionProvider provider = ConnectionProvider.builder("hyperllm-" + providerCode)
                .maxConnections(properties.getUpstream().getMaxConnectionsPerProvider())
                .maxIdleTime(Duration.ofSeconds(30))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
        pools.put(providerCode, provider);

        HttpClient httpClient = HttpClient.create(provider)
                .runOn(sharedLoopResources)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getUpstream().getConnectTimeoutMs())
                .responseTimeout(Duration.ofSeconds(properties.getUpstream().getResponseTimeoutSeconds()));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(MAX_BUFFER_BYTES))
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(p -> p.disposeLater().subscribe());
        pools.clear();
        clients.clear();
        sharedLoopResources.disposeLater().subscribe();
    }
}
