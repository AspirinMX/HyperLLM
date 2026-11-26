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

        private volatile CoreSubscriber<? super T> actual;
        private volatile Subscription upstream;
        private volatile boolean cancelled;
        private boolean done;

        private DetachingSubscriber(CoreSubscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public Context currentContext() {
            CoreSubscriber<? super T> a = this.actual;
            return a != null ? a.currentContext() : Context.empty();
        }

        @Override
        public void onSubscribe(Subscription s) {
            CoreSubscriber<? super T> a = this.actual;
            if (a != null && Operators.setOnce(UPSTREAM, this, s)) {
                a.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T value) {
            CoreSubscriber<? super T> a = this.actual;
            if (done || a == null) {
                Operators.onNextDropped(value, a != null ? a.currentContext() : Context.empty());
                return;
            }
            done = true;
            // Clear BOTH upward and downward references BEFORE forwarding downstream:
            // - upstream = null prevents a long-lived downstream flatMap from pinning upstream state
            // - actual = null prevents a long-lived upstream publisher (like Netty's FluxReceive on
            //   HttpServerOperations) from pinning downstream flatMap closures across the response stream.
            UPSTREAM.lazySet(this, null);
            this.actual = null;
            a.onNext(value);
            if (!cancelled) {
                a.onComplete();
            }
        }

        @Override
        public void onError(Throwable t) {
            CoreSubscriber<? super T> a = this.actual;
            if (done || a == null) {
                Operators.onErrorDropped(t, a != null ? a.currentContext() : Context.empty());
                return;
            }
            done = true;
            UPSTREAM.lazySet(this, null);
            this.actual = null;
            a.onError(t);
        }

        @Override
        public void onComplete() {
            CoreSubscriber<? super T> a = this.actual;
            if (done || a == null) {
                return;
            }
            done = true;
            UPSTREAM.lazySet(this, null);
            this.actual = null;
            a.onComplete();
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
            this.actual = null;
            Subscription s = UPSTREAM.getAndSet(this, null);
            if (s != null && s != Operators.cancelledSubscription()) {
                s.cancel();
            }
        }
    }
}
