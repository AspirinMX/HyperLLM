package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sweeps expired pre-holds in the background.
 *
 * <p>Admission already reclaims lazily, but only for the organisation that is calling: a tenant who
 * stops calling keeps a frozen reservation until the hold expires and nobody looks at it again. The
 * sweep is what makes the reservation self-healing for tenants that go quiet, and it walks the
 * expiry index so it never needs a per-organisation key.
 *
 * <p>It is deliberately a plain scheduled executor rather than a {@code @Scheduled} method: the
 * gateway has no other periodic work, and one thread is easier to reason about than a scheduler
 * context on the data plane.
 */
@Slf4j
@Component
public class HoldReclaimScheduler implements SmartLifecycle {

    private final PreHoldService preHoldService;
    private final GatewayProperties.Billing billing;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = Thread.ofPlatform().name("hold-reclaim").daemon(true).unstarted(runnable);
                return thread;
            });

    private volatile boolean running;

    public HoldReclaimScheduler(PreHoldService preHoldService, GatewayProperties properties) {
        this.preHoldService = preHoldService;
        this.billing = properties.getBilling();
    }

    @Override
    public void start() {
        long interval = Math.max(5L, billing.getReclaimSweepSeconds());
        running = true;
        executor.scheduleWithFixedDelay(this::sweep, interval, interval, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void sweep() {
        try {
            preHoldService.reclaimExpired(billing.getReclaimSweepBatch())
                    .subscribe(
                            released -> {
                                if (released != null && released > 0) {
                                    log.debug("reclaimed {} expired pre-holds", released);
                                }
                            },
                            ex -> log.warn("pre-hold sweep failed", ex));
        } catch (RuntimeException ex) {
            log.warn("pre-hold sweep failed", ex);
        }
    }
}
