package app.pagefault.hyperllm.gateway.cache;

import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-process SingleFlight deduplicator coalescing concurrent cold-cache database fallbacks per key.
 */
public final class SingleFlight<K, V> {

    private final ConcurrentHashMap<K, Mono<V>> inFlight = new ConcurrentHashMap<>();

    public Mono<V> execute(K key, Supplier<Mono<V>> loader) {
        return inFlight.computeIfAbsent(key, k ->
                Mono.defer(loader)
                        .doFinally(signal -> inFlight.remove(k))
                        .cache()
        );
    }
}
