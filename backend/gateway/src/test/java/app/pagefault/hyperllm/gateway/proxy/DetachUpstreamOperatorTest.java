package app.pagefault.hyperllm.gateway.proxy;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link DetachUpstreamOperator} severs the upward {@code Subscription} back-pointer
 * so a long-lived downstream {@code Mono.flatMap} inner (such as Spring WebFlux's
 * {@code DispatcherHandler.handleResultMono}) cannot pin pre-TTFT request graphs in Old Gen.
 */
class DetachUpstreamOperatorTest {

    @Test
    void detachesUpstreamGraphWhileDownstreamFlatMapRemainsSubscribed() throws Exception {
        Sinks.One<String> responseHeadSink = Sinks.one();
        Sinks.One<Void> longLivedBodySink = Sinks.one();

        WeakReference<byte[]> capturedPayloadRef = subscribeWithPinnedUpstream(responseHeadSink, longLivedBodySink);

        // Emit response head (TTFT): the downstream flatMap transitions into longLivedBodySink,
        // which stays subscribed for the entire stream duration.
        responseHeadSink.tryEmitValue("200-OK");

        boolean collected = false;
        for (int i = 0; i < 30; i++) {
            System.gc();
            if (capturedPayloadRef.get() == null) {
                collected = true;
                break;
            }
            Thread.sleep(20);
        }
        assertThat(collected)
                .as("Upstream captured payload must become GC-eligible while downstream flatMap inner stays active")
                .isTrue();

        longLivedBodySink.tryEmitEmpty();
    }

    private WeakReference<byte[]> subscribeWithPinnedUpstream(
            Sinks.One<String> responseHeadSink,
            Sinks.One<Void> longLivedBodySink
    ) {
        byte[] largePayload = new byte[256 * 1024];
        WeakReference<byte[]> ref = new WeakReference<>(largePayload);

        Mono<String> preTtftChain = Mono.just("caller")
                .flatMap(c -> Mono.defer(() -> {
                    // Simulate a heavy request graph captured by upstream flatMap/defer closures
                    int ignored = largePayload.length;
                    return responseHeadSink.asMono().map(v -> v + ":" + ignored);
                }));

        // Simulate DispatcherHandler.handleResultMono: flatMap into a long-lived body write Mono
        DetachUpstreamOperator.detach(preTtftChain)
                .flatMap(head -> longLivedBodySink.asMono())
                .subscribe();
        return ref;
    }

    @Test
    void detachesDownstreamGraphWhenLongLivedUpstreamRetainsSubscriberReference() throws Exception {
        // Simulates Netty's HttpServerOperations -> FluxReceive, which holds a strong reference to
        // its subscribed CoreSubscriber for the entire lifetime of the HTTP connection.
        java.util.concurrent.atomic.AtomicReference<org.reactivestreams.Subscriber<?>> pinnedReceiver =
                new java.util.concurrent.atomic.AtomicReference<>();
        Mono<String> fluxReceiveLike = new Mono<>() {
            @Override
            public void subscribe(reactor.core.CoreSubscriber<? super String> actual) {
                pinnedReceiver.set(actual);
                actual.onSubscribe(reactor.core.publisher.Operators.emptySubscription());
                actual.onNext("body");
                actual.onComplete();
            }
        };

        WeakReference<byte[]> downstreamPayloadRef = subscribeWithPinnedDownstream(fluxReceiveLike);
        assertThat(pinnedReceiver.get()).isNotNull();

        boolean collected = false;
        for (int i = 0; i < 30; i++) {
            System.gc();
            if (downstreamPayloadRef.get() == null) {
                collected = true;
                break;
            }
            Thread.sleep(20);
        }
        assertThat(collected)
                .as("Downstream flatMap payload must become GC-eligible even when upstream publisher pins its subscriber")
                .isTrue();
    }

    private WeakReference<byte[]> subscribeWithPinnedDownstream(Mono<String> fluxReceiveLike) {
        byte[] arenaBytes = new byte[256 * 1024];
        WeakReference<byte[]> ref = new WeakReference<>(arenaBytes);
        DetachUpstreamOperator.detach(fluxReceiveLike)
                .flatMap(ignored -> Mono.fromCallable(() -> arenaBytes.length))
                .subscribe();
        return ref;
    }

    @Test
    void propagatesCancellationToUpstreamBeforeEmission() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Mono<String> upstream = Mono.<String>never().doOnCancel(() -> cancelled.set(true));

        var sub = DetachUpstreamOperator.detach(upstream).subscribe();
        assertThat(cancelled.get()).isFalse();
        sub.dispose();
        assertThat(cancelled.get()).isTrue();
    }
}
