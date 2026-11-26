package app.pagefault.hyperllm.gateway.billing;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Shared 1-second timer registry for active streaming pre-hold heartbeats.
 *
 * <p>Replaces one {@code Flux.interval} subscription per concurrent stream with a single daemon
 * scheduler scanning active leases. Short streams (&lt; {@code periodSeconds}) register and
 * dispose in O(1) without allocating scheduled timer tasks or firing Redis renewals.
 */
@Slf4j
@Component
public class LeaseHeartbeatRegistry {

    private static final long DEFAULT_TICK_MS = 1_000L;

    private static final class Entry {
        final PreHoldService.Lease lease;
        final long periodNanos;
        final long ttlSeconds;
        volatile long nextRenewAtNanos;
        final AtomicBoolean renewing = new AtomicBoolean(false);
        volatile boolean disposed;

        Entry(PreHoldService.Lease lease, long periodNanos, long ttlSeconds, long nextRenewAtNanos) {
            this.lease = lease;
            this.periodNanos = periodNanos;
            this.ttlSeconds = ttlSeconds;
            this.nextRenewAtNanos = nextRenewAtNanos;
        }
    }

    private final PreHoldService preHoldService;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Entry> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    @Autowired
    public LeaseHeartbeatRegistry(PreHoldService preHoldService) {
        this(preHoldService, DEFAULT_TICK_MS, System::nanoTime, true);
    }

    LeaseHeartbeatRegistry(PreHoldService preHoldService, long tickMs, LongSupplier nanoTime, boolean autoStart) {
        this.preHoldService = preHoldService;
        this.nanoTime = nanoTime;
        if (autoStart) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lease-heartbeat");
                t.setDaemon(true);
                return t;
            });
            long period = Math.max(10L, tickMs);
            this.scheduler.scheduleWithFixedDelay(this::tickSafely, period, period, TimeUnit.MILLISECONDS);
        } else {
            this.scheduler = null;
        }
    }

    public Disposable register(PreHoldService.Lease lease, long periodSeconds, long ttlSeconds) {
        long clampedPeriod = Math.max(1L, periodSeconds);
        long periodNanos = TimeUnit.SECONDS.toNanos(clampedPeriod);
        long now = nanoTime.getAsLong();
        Entry entry = new Entry(lease, periodNanos, ttlSeconds, now + periodNanos);
        active.put(lease.holdId(), entry);
        return new Disposable() {
            @Override
            public void dispose() {
                if (!entry.disposed) {
                    entry.disposed = true;
                    active.remove(lease.holdId(), entry);
                }
            }

            @Override
            public boolean isDisposed() {
                return entry.disposed;
            }
        };
    }

    public int activeCount() {
        return active.size();
    }

    private void tickSafely() {
        try {
            tickOnce();
        } catch (Throwable ex) {
            log.warn("Unexpected error in lease heartbeat tick", ex);
        }
    }

    void tickOnce() {
        if (active.isEmpty()) {
            return;
        }
        long now = nanoTime.getAsLong();
        for (Map.Entry<String, Entry> kv : active.entrySet()) {
            String holdId = kv.getKey();
            Entry entry = kv.getValue();
            if (entry.disposed) {
                active.remove(holdId, entry);
                continue;
            }
            if (now - entry.nextRenewAtNanos < 0L) {
                continue;
            }
            if (!entry.renewing.compareAndSet(false, true)) {
                continue;
            }
            if (entry.disposed) {
                entry.renewing.set(false);
                active.remove(holdId, entry);
                continue;
            }
            try {
                preHoldService.renew(entry.lease, entry.ttlSeconds)
                        .defaultIfEmpty(false)
                        .doFinally(sig -> entry.renewing.set(false))
                        .subscribe(
                                renewed -> {
                                    if (Boolean.TRUE.equals(renewed)) {
                                        entry.nextRenewAtNanos = nanoTime.getAsLong() + entry.periodNanos;
                                    } else {
                                        entry.disposed = true;
                                        active.remove(holdId, entry);
                                    }
                                },
                                ex -> {
                                    log.warn("Cannot renew hold {}", holdId, ex);
                                    // Retry on the next heartbeat period, matching previous behavior.
                                    entry.nextRenewAtNanos = nanoTime.getAsLong() + entry.periodNanos;
                                }
                        );
            } catch (Throwable ex) {
                log.warn("Failed to initiate hold renewal for {}", holdId, ex);
                entry.nextRenewAtNanos = nanoTime.getAsLong() + entry.periodNanos;
                entry.renewing.set(false);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        active.clear();
    }
}
