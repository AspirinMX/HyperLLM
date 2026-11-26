package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.protocol.translate.StreamBridge;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Protocol-agnostic direct streaming bridge operator that subscribes to upstream
 * {@code Flux<DataBuffer>} chunks with a single lean {@link CoreSubscriber}, replacing the
 * 5-operator Reactor chain ({@code Flux.handle} + {@code concatWith} + {@code doOnDiscard} +
 * {@code switchOnFirst} + {@code doOnNext}).
 *
 * <p>All protocols encode straight into pooled Netty direct {@link ByteBuf}s via
 * {@link NettyByteBufSink}, whether downstream SSE frame coalescing is disabled or enabled.
 */
final class DirectStreamBridgeOperator extends Flux<Void> {

    private final Publisher<DataBuffer> upstreamSource;
    private final StreamBridge bridge;
    private final ByteBufAllocator directAllocator;
    private final NettyDataBufferFactory nettyDataBufferFactory;
    private final Supplier<Mono<Void>> firstFrameGate;
    private final Runnable onFirstFrameEmitted;
    private final Consumer<DataBuffer> frameConsumer;
    private final Supplier<Mono<Void>> onCleanComplete;
    private final java.util.function.Function<Throwable, Mono<Void>> onFailure;

    DirectStreamBridgeOperator(
            Publisher<DataBuffer> upstreamSource,
            StreamBridge bridge,
            ByteBufAllocator directAllocator,
            NettyDataBufferFactory nettyDataBufferFactory,
            Supplier<Mono<Void>> firstFrameGate,
            Runnable onFirstFrameEmitted,
            Consumer<DataBuffer> frameConsumer,
            Supplier<Mono<Void>> onCleanComplete,
            java.util.function.Function<Throwable, Mono<Void>> onFailure
    ) {
        this.upstreamSource = upstreamSource;
        this.bridge = bridge;
        this.directAllocator = directAllocator;
        this.nettyDataBufferFactory = nettyDataBufferFactory;
        this.firstFrameGate = firstFrameGate;
        this.onFirstFrameEmitted = onFirstFrameEmitted;
        this.frameConsumer = frameConsumer;
        this.onCleanComplete = onCleanComplete;
        this.onFailure = onFailure;
    }

    @Override
    public void subscribe(CoreSubscriber<? super Void> actual) {
        BridgeSubscriber subscriber = new BridgeSubscriber(actual);
        upstreamSource.subscribe(subscriber);
    }

    private final class BridgeSubscriber implements CoreSubscriber<DataBuffer>, Subscription {

        private final CoreSubscriber<? super Void> actual;
        private final Object gateLock = new Object();
        private final AtomicReference<Disposable> activeAsync = new AtomicReference<>();

        private Subscription upstream;
        private byte[] readScratch;

        /** Flips to true once frame 1's firstFrameGate finishes; frames 2..N run lock-free. */
        private volatile boolean gateOpen;
        private volatile boolean cancelled;

        // Guarded by gateLock only during the one-time frame-1 gate transition and terminal cleanup.
        private boolean markingFirstFrame;
        private boolean firstFrameSeen;
        private DataBuffer heldFirstFrame;
        private boolean pendingComplete;
        private Throwable pendingError;
        private boolean finalized;

        BridgeSubscriber(CoreSubscriber<? super Void> actual) {
            this.actual = actual;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (Operators.validate(this.upstream, s)) {
                this.upstream = s;
                actual.onSubscribe(this);
                // Demand 1 chunk until the first translated frame triggers firstFrameGate, ensuring
                // frame 2 cannot arrive before frame 1 finishes marking served.
                s.request(1L);
            }
        }

        @Override
        public void request(long n) {
            // Upstream demand is managed internally (1 until gateOpen, then Long.MAX_VALUE).
        }

        @Override
        public void cancel() {
            cancelled = true;
            DataBuffer toRelease;
            synchronized (gateLock) {
                toRelease = heldFirstFrame;
                heldFirstFrame = null;
                finalized = true;
            }
            if (toRelease != null) {
                DataBufferUtils.release(toRelease);
            }
            Disposable d = activeAsync.getAndSet(null);
            if (d != null) {
                d.dispose();
            }
            Subscription s = this.upstream;
            if (s != null) {
                s.cancel();
            }
        }

        @Override
        public void onNext(DataBuffer dataBuffer) {
            if (cancelled) {
                DataBufferUtils.release(dataBuffer);
                return;
            }
            // Fast path for frames 2..N: zero synchronization, zero ContextPropagation, zero operators.
            if (gateOpen) {
                DataBuffer translated;
                try {
                    translated = translateChunk(dataBuffer);
                } catch (Throwable ex) {
                    upstream.cancel();
                    onError(ex);
                    return;
                }
                if (translated != null) {
                    frameConsumer.accept(translated);
                }
                return;
            }

            // Pre-gate path (only frame 1, plus any leading empty/comment SSE chunks).
            DataBuffer translated;
            try {
                translated = translateChunk(dataBuffer);
            } catch (Throwable ex) {
                upstream.cancel();
                onError(ex);
                return;
            }
            if (translated == null) {
                if (!cancelled) {
                    upstream.request(1L);
                }
                return;
            }
            beginFirstFrameGate(translated);
        }

        @Override
        public void onComplete() {
            synchronized (gateLock) {
                if (finalized || cancelled) {
                    return;
                }
                if (markingFirstFrame) {
                    pendingComplete = true;
                    return;
                }
                finalized = true;
            }
            finishCleanStream();
        }

        @Override
        public void onError(Throwable t) {
            DataBuffer toRelease = null;
            synchronized (gateLock) {
                if (finalized || cancelled) {
                    return;
                }
                if (markingFirstFrame) {
                    pendingError = t;
                    return;
                }
                toRelease = heldFirstFrame;
                heldFirstFrame = null;
                finalized = true;
            }
            if (toRelease != null) {
                DataBufferUtils.release(toRelease);
            }
            finishFailedStream(t);
        }

        private void beginFirstFrameGate(DataBuffer firstFrame) {
            synchronized (gateLock) {
                if (cancelled || finalized) {
                    DataBufferUtils.release(firstFrame);
                    return;
                }
                firstFrameSeen = true;
                markingFirstFrame = true;
                heldFirstFrame = firstFrame;
            }

            Mono<Void> gate;
            try {
                gate = firstFrameGate.get();
            } catch (Throwable ex) {
                gate = Mono.empty();
            }
            if (gate == null) {
                gate = Mono.empty();
            }

            Disposable d = gate.subscribe(
                    ignored -> {},
                    err -> completeFirstFrameGate(),
                    this::completeFirstFrameGate
            );
            activeAsync.set(d);
            if (cancelled) {
                Disposable prev = activeAsync.getAndSet(null);
                if (prev != null) {
                    prev.dispose();
                }
            }
        }

        private void completeFirstFrameGate() {
            DataBuffer firstFrame;
            boolean doComplete = false;
            Throwable errToHandle = null;
            synchronized (gateLock) {
                markingFirstFrame = false;
                firstFrame = heldFirstFrame;
                heldFirstFrame = null;
                if (cancelled || finalized) {
                    if (firstFrame != null) {
                        DataBufferUtils.release(firstFrame);
                    }
                    return;
                }
                gateOpen = true;
                if (pendingError != null) {
                    errToHandle = pendingError;
                    pendingError = null;
                    finalized = true;
                } else if (pendingComplete) {
                    doComplete = true;
                    pendingComplete = false;
                    finalized = true;
                }
            }

            if (firstFrame != null) {
                onFirstFrameEmitted.run();
                frameConsumer.accept(firstFrame);
            }

            if (errToHandle != null) {
                finishFailedStream(errToHandle);
            } else if (doComplete) {
                finishCleanStream();
            } else if (!cancelled) {
                upstream.request(Long.MAX_VALUE);
            }
        }

        private void finishCleanStream() {
            DataBuffer trailing;
            try {
                trailing = translateComplete();
            } catch (Throwable ex) {
                finishFailedStream(ex);
                return;
            }
            if (trailing != null) {
                if (!firstFrameSeen) {
                    firstFrameSeen = true;
                    onFirstFrameEmitted.run();
                }
                frameConsumer.accept(trailing);
            }
            Mono<Void> settle = onCleanComplete.get();
            Disposable d = settle.subscribe(
                    ignored -> {},
                    actual::onError,
                    actual::onComplete
            );
            activeAsync.set(d);
            if (cancelled) {
                Disposable prev = activeAsync.getAndSet(null);
                if (prev != null) {
                    prev.dispose();
                }
            }
        }

        private void finishFailedStream(Throwable ex) {
            Mono<Void> failFlow = onFailure.apply(ex);
            Disposable d = failFlow.subscribe(
                    ignored -> {},
                    actual::onError,
                    actual::onComplete
            );
            activeAsync.set(d);
            if (cancelled) {
                Disposable prev = activeAsync.getAndSet(null);
                if (prev != null) {
                    prev.dispose();
                }
            }
        }

        private DataBuffer translateChunk(DataBuffer dataBuffer) {
            try {
                int readable = dataBuffer.readableByteCount();
                if (readable <= 0) {
                    return null;
                }
                byte[] src;
                int off;
                if (dataBuffer instanceof NettyDataBuffer ndb && ndb.getNativeBuffer().hasArray()) {
                    ByteBuf nativeBuf = ndb.getNativeBuffer();
                    src = nativeBuf.array();
                    off = nativeBuf.arrayOffset() + nativeBuf.readerIndex();
                } else {
                    byte[] scratch = readScratch;
                    if (scratch == null || scratch.length < readable) {
                        scratch = new byte[Math.max(1024, readable)];
                        readScratch = scratch;
                    }
                    dataBuffer.read(scratch, 0, readable);
                    src = scratch;
                    off = 0;
                }
                NettyByteBufSink outSink = new NettyByteBufSink(
                        directAllocator, Math.max(256, readable + 128));
                try {
                    bridge.feed(src, off, readable, outSink);
                    if (outSink.writtenBytes() > 0) {
                        return nettyDataBufferFactory.wrap(outSink.finishAndTransfer());
                    }
                    outSink.release();
                    return null;
                } catch (Throwable ex) {
                    outSink.release();
                    throw ex;
                }
            } finally {
                DataBufferUtils.release(dataBuffer);
            }
        }

        private DataBuffer translateComplete() {
            NettyByteBufSink outSink = new NettyByteBufSink(directAllocator, 512);
            try {
                bridge.complete(outSink);
                if (outSink.writtenBytes() > 0) {
                    return nettyDataBufferFactory.wrap(outSink.finishAndTransfer());
                }
                outSink.release();
                return null;
            } catch (Throwable ex) {
                outSink.release();
                throw ex;
            }
        }
    }
}
