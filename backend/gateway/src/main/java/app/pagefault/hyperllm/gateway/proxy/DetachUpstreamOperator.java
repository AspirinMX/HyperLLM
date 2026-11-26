package app.pagefault.hyperllm.gateway.proxy;

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Severs the upward {@link Subscription} reference as soon as a {@link Mono} terminates (emits its
 * value, completes, errors, or is cancelled).
 *
 * <p>Spring WebFlux's {@code DispatcherHandler} chains {@code handlerResultMono.flatMap(result ->
 * handleResult(exchange, result))}. In Reactor, {@code MonoFlatMap.FlatMapInner} holds a final
 * back-pointer to {@code FlatMapMain}, whose {@code Subscription s} field is never cleared on
 * completion. While a streaming {@code ResponseEntity<Flux<DataBuffer>>} writes frames for tens of
 * seconds, that back-pointer otherwise pins the entire upstream pre-TTFT operator chain (closures,
 * retry state, and decoded request graphs) in Old Gen. Wrapping the gateway proxy {@link Mono} in
 * this operator breaks that chain the microsecond response headers emit.
 */
final class DetachUpstreamOperator {

    private DetachUpstreamOperator() {
    }

    static <T> Mono<T> detach(Mono<T> source) {
        Objects.requireNonNull(source, "source");
        return new DetachingMono<>(source);
    }

    private static final class DetachingMono<T> extends Mono<T> {
        private final Mono<T> source;

        private DetachingMono(Mono<T> source) {
            this.source = source;
        }

        @Override
        public void subscribe(CoreSubscriber<? super T> actual) {
            source.subscribe(new DetachingSubscriber<>(actual));
        }
    }

    private static final class DetachingSubscriber<T> implements CoreSubscriber<T>, Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<DetachingSubscriber, Subscription> UPSTREAM =
                AtomicReferenceFieldUpdater.newUpdater(DetachingSubscriber.class, Subscription.class, "upstream");

        private final CoreSubscriber<? super T> actual;
        private volatile Subscription upstream;
        private volatile boolean cancelled;
        private boolean done;

        private DetachingSubscriber(CoreSubscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (Operators.setOnce(UPSTREAM, this, s)) {
                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T value) {
            if (done) {
                Operators.onNextDropped(value, actual.currentContext());
                return;
            }
            done = true;
            // Clear the upward subscription pointer BEFORE forwarding downstream so any long-lived
            // downstream flatMap inner cannot reach the upstream subscriber chain.
            UPSTREAM.lazySet(this, null);
            actual.onNext(value);
            if (!cancelled) {
                actual.onComplete();
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                Operators.onErrorDropped(t, actual.currentContext());
                return;
            }
            done = true;
            UPSTREAM.lazySet(this, null);
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            UPSTREAM.lazySet(this, null);
            actual.onComplete();
        }

        @Override
        public void request(long n) {
            if (!Operators.validate(n)) {
                return;
            }
            Subscription s = upstream;
            if (s != null) {
                s.request(n);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            Subscription s = UPSTREAM.getAndSet(this, null);
            if (s != null && s != Operators.cancelledSubscription()) {
                s.cancel();
            }
        }
    }
}
