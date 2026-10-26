package app.pagefault.hyperllm.gateway.cache;

import app.pagefault.hyperllm.common.billing.LedgerLagSignal;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides whether a tenant may be rebuilt from the database right now.
 *
 * <p>A rebuild writes a database snapshot into the real-time authority. That is only sound while the
 * consumer is keeping up: if it is behind, the snapshot trails the deductions the gateway has already
 * made, and rebuilding would hand the tenant back money it has spent. So the decision is taken from
 * the published lag signal, and it is the same decision for every tenant.
 *
 * <p>The signal expires. Its absence therefore means one of two very different things, and they are
 * distinguished by the marker the publisher leaves behind: no marker at all means the feature was
 * never deployed, while a marker with no signal means the publisher stopped. A stopped monitor is an
 * operator problem and must not become a customer outage, so it is treated as healthy and logged.
 */
@Slf4j
@Component
public class LedgerHealthGate {

    private static final long CACHE_MILLIS = 5_000L;
    private static final long STALE_MILLIS = 90_000L;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private volatile LedgerLagSignal cached;
    private volatile long cachedAt;
    private volatile boolean warnedAboutMissingSignal;

    public LedgerHealthGate(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** @return the signal as last observed, or a healthy default when there is nothing to go on */
    public LedgerLagSignal current() {
        long now = System.currentTimeMillis();
        LedgerLagSignal snapshot = cached;
        if (snapshot != null && now - cachedAt < CACHE_MILLIS) {
            return snapshot;
        }
        LedgerLagSignal fresh = fetch();
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    private LedgerLagSignal fetch() {
        try {
            String payload = redisTemplate.opsForValue()
                    .get(GatewayRedisKeys.ledgerLagSignal())
                    .block(java.time.Duration.ofMillis(200));
            if (payload != null && !payload.isBlank()) {
                warnedAboutMissingSignal = false;
                return objectMapper.readValue(payload, LedgerLagSignal.class);
            }
            Boolean armed = redisTemplate.hasKey(GatewayRedisKeys.ledgerLagArmed())
                    .block(java.time.Duration.ofMillis(200));
            if (Boolean.TRUE.equals(armed)) {
                if (!warnedAboutMissingSignal) {
                    warnedAboutMissingSignal = true;
                    log.warn("ledger lag monitor has stopped publishing; treating the mirror as current");
                }
            }
            return LedgerLagSignal.healthy(0L, 0d, System.currentTimeMillis());
        } catch (Exception ex) {
            log.warn("cannot read ledger lag signal, treating the mirror as current", ex);
            return LedgerLagSignal.healthy(0L, 0d, System.currentTimeMillis());
        }
    }

    /**
     * @return true when a rebuild would write a snapshot that is known to trail the deductions
     */
    public boolean blocksRebuild() {
        LedgerLagSignal signal = current();
        if (!signal.degraded()) {
            return false;
        }
        return System.currentTimeMillis() - signal.updatedAtEpochMs() < STALE_MILLIS;
    }
}
