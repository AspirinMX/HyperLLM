package app.pagefault.hyperllm.gateway.cache;

import app.pagefault.hyperllm.common.billing.LedgerLagSignal;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Samples the same rebuild gate for every tenant without blocking the request thread. */
@Slf4j
@Component
public class LedgerHealthGate {

    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private static final long STALE_MILLIS = 90_000L;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Mono<LedgerLagSignal> cachedSignal;
    private final AtomicBoolean alarmed = new AtomicBoolean();

    public LedgerHealthGate(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cachedSignal = Mono.defer(this::fetch).cache(CACHE_TTL);
    }

    public Mono<LedgerLagSignal> current() {
        return cachedSignal.map(signal -> isFresh(signal) ? signal : unavailable());
    }

    private Mono<LedgerLagSignal> fetch() {
        return redisTemplate.opsForValue().get(GatewayRedisKeys.ledgerLagSignal())
                .flatMap(payload -> Mono.fromCallable(() -> decode(payload)))
                .filter(LedgerHealthGate::isFresh)
                .doOnNext(signal -> alarmed.set(false))
                .switchIfEmpty(Mono.defer(() -> redisTemplate.hasKey(GatewayRedisKeys.ledgerLagArmed())
                        .map(armed -> armed ? unavailable() : healthy())))
                .timeout(Duration.ofMillis(400))
                .onErrorResume(ex -> {
                    if (alarmed.compareAndSet(false, true)) {
                        log.error("P1 cannot read ledger lag signal, treating the mirror as current", ex);
                    }
                    return Mono.just(healthy());
                });
    }

    private LedgerLagSignal decode(String payload) throws JsonProcessingException {
        return objectMapper.readValue(payload, LedgerLagSignal.class);
    }

    private LedgerLagSignal unavailable() {
        if (alarmed.compareAndSet(false, true)) {
            log.error("P1 ledger lag monitor has stopped publishing, treating the mirror as current");
        }
        return healthy();
    }

    private static LedgerLagSignal healthy() {
        return LedgerLagSignal.healthy(0L, 0d, System.currentTimeMillis());
    }

    private static boolean isFresh(LedgerLagSignal signal) {
        return System.currentTimeMillis() - signal.updatedAtEpochMs() < STALE_MILLIS;
    }
}
