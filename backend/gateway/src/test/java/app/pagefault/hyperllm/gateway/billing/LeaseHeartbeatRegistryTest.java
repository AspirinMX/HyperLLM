package app.pagefault.hyperllm.gateway.billing;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaseHeartbeatRegistryTest {

    @Test
    void shortStreamNeverFiresRenewAndCleansUpOnDispose() {
        PreHoldService preHoldService = Mockito.mock(PreHoldService.class);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        LeaseHeartbeatRegistry registry = new LeaseHeartbeatRegistry(preHoldService, 1000L, clock::get, false);

        PreHoldService.Lease lease = new PreHoldService.Lease(UUID.randomUUID(), List.of(), "hold-short", 100L);
        Disposable handle = registry.register(lease, 15L, 300L);
        assertThat(registry.activeCount()).isEqualTo(1);

        // Advance 14s and tick repeatedly — still below 15s heartbeat period.
        clock.addAndGet(TimeUnit.SECONDS.toNanos(14));
        registry.tickOnce();
        assertThat(registry.activeCount()).isEqualTo(1);

        // Stream finishes and disposes.
        handle.dispose();
        assertThat(handle.isDisposed()).isTrue();
        assertThat(registry.activeCount()).isZero();

        // Advance well past period and tick — no renew ever fired.
        clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
        registry.tickOnce();
        verify(preHoldService, never()).renew(any(), anyLong());
    }

    @Test
    void longStreamRenewsPeriodicallyAndEvictsWhenLeaseEnds() {
        PreHoldService preHoldService = Mockito.mock(PreHoldService.class);
        AtomicLong clock = new AtomicLong(0L);
        LeaseHeartbeatRegistry registry = new LeaseHeartbeatRegistry(preHoldService, 1000L, clock::get, false);

        PreHoldService.Lease lease = new PreHoldService.Lease(UUID.randomUUID(), List.of(), "hold-long", 500L);
        when(preHoldService.renew(eq(lease), eq(300L)))
                .thenReturn(Mono.just(true))
                .thenReturn(Mono.just(false));

        registry.register(lease, 15L, 300L);

        // First period expires at 15s -> renews (returns true), schedules next at 30s.
        clock.addAndGet(TimeUnit.SECONDS.toNanos(15));
        registry.tickOnce();
        verify(preHoldService, times(1)).renew(lease, 300L);
        assertThat(registry.activeCount()).isEqualTo(1);

        // At 25s -> not due yet.
        clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
        registry.tickOnce();
        verify(preHoldService, times(1)).renew(lease, 300L);

        // At 30s -> renews (returns false), automatically evicts lease.
        clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
        registry.tickOnce();
        verify(preHoldService, times(2)).renew(lease, 300L);
        assertThat(registry.activeCount()).isZero();

        // Further ticks do nothing.
        clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
        registry.tickOnce();
        verify(preHoldService, times(2)).renew(lease, 300L);
    }

    @Test
    void transientRenewErrorRetriesOnNextPeriod() {
        PreHoldService preHoldService = Mockito.mock(PreHoldService.class);
        AtomicLong clock = new AtomicLong(0L);
        LeaseHeartbeatRegistry registry = new LeaseHeartbeatRegistry(preHoldService, 1000L, clock::get, false);

        PreHoldService.Lease lease = new PreHoldService.Lease(UUID.randomUUID(), List.of(), "hold-retry", 200L);
        when(preHoldService.renew(eq(lease), eq(300L)))
                .thenReturn(Mono.error(new RuntimeException("redis blip")))
                .thenReturn(Mono.just(true));

        Disposable handle = registry.register(lease, 10L, 300L);

        clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
        registry.tickOnce();
        assertThat(registry.activeCount()).isEqualTo(1);

        clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
        registry.tickOnce();
        verify(preHoldService, times(2)).renew(lease, 300L);
        assertThat(registry.activeCount()).isEqualTo(1);

        handle.dispose();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void concurrentRegisterTickAndDisposeLeavesNoLeakedEntries() throws Exception {
        PreHoldService preHoldService = Mockito.mock(PreHoldService.class);
        AtomicInteger renewCount = new AtomicInteger();
        when(preHoldService.renew(any(), anyLong())).thenAnswer(inv -> {
            renewCount.incrementAndGet();
            return Mono.just(true);
        });
        AtomicLong clock = new AtomicLong(0L);
        LeaseHeartbeatRegistry registry = new LeaseHeartbeatRegistry(preHoldService, 1000L, clock::get, false);

        int threads = 8;
        int leasesPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        UUID orgId = UUID.randomUUID();
        try {
            for (int t = 0; t < threads; t++) {
                final int worker = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < leasesPerThread; i++) {
                        PreHoldService.Lease lease = new PreHoldService.Lease(
                                orgId, List.of(), "hold-" + worker + "-" + i, 64L);
                        Disposable d = registry.register(lease, 1L, 60L);
                        if ((i & 7) == 0) {
                            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
                        }
                        d.dispose();
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    registry.tickOnce();
                }
                return null;
            }));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            registry.shutdown();
        }
        assertThat(registry.activeCount()).isZero();
    }
}
