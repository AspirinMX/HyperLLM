package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.translate.StreamBridge;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DirectStreamBridgeOperatorTest {

    private static final PooledByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT;
    private static final NettyDataBufferFactory NETTY_FACTORY = new NettyDataBufferFactory(ALLOC);

    private static StreamBridge echoBridge(String suffix) {
        return new StreamBridge() {
            @Override
            public String contentType() {
                return "text/event-stream";
            }

            @Override
            public void feed(byte[] upstreamChunk, int offset, int length, ByteSink out) {
                if (length == 1 && upstreamChunk[offset] == ':') {
                    return; // simulate SSE comment chunk producing zero downstream bytes
                }
                out.writeBytes(upstreamChunk, offset, length);
            }

            @Override
            public void complete(ByteSink out) {
                if (!suffix.isEmpty()) {
                    byte[] b = suffix.getBytes(StandardCharsets.UTF_8);
                    out.writeBytes(b, 0, b.length);
                }
            }

            @Override
            public byte[] feed(byte[] upstreamChunk) {
                return upstreamChunk;
            }

            @Override
            public List<byte[]> complete() {
                return List.of();
            }

            @Override
            public TokenUsage finalizeUsage(boolean cleanCompletion) {
                return TokenUsage.empty();
            }
        };
    }

    private static DataBuffer buf(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ByteBuf bb = ALLOC.directBuffer(b.length);
        bb.writeBytes(b);
        return NETTY_FACTORY.wrap(bb);
    }

    private static String readAndRelease(DataBuffer db) {
        try {
            byte[] b = new byte[db.readableByteCount()];
            db.read(b);
            return new String(b, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(db);
        }
    }

    @Test
    void gatesOnlyFirstFrameSkipsLeadingEmptyChunksAndCompletesCleanly() {
        List<String> events = new ArrayList<>();
        Sinks.One<Void> gate = Sinks.one();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicBoolean settled = new AtomicBoolean();

        Flux<DataBuffer> upstream = Flux.just(buf(":"), buf("frame1|"), buf("frame2|"), buf("frame3|"));
        DirectStreamBridgeOperator op = new DirectStreamBridgeOperator(
                upstream,
                echoBridge("DONE"),
                ALLOC,
                NETTY_FACTORY,
                () -> {
                    gateCalls.incrementAndGet();
                    events.add("gate-start");
                    return gate.asMono().doOnSuccess(v -> events.add("gate-done"));
                },
                () -> events.add("first-emitted"),
                db -> events.add("out:" + readAndRelease(db)),
                () -> Mono.fromRunnable(() -> {
                    settled.set(true);
                    events.add("settled");
                }),
                ex -> Mono.fromRunnable(() -> events.add("err:" + ex.getMessage()))
        );

        AtomicBoolean completed = new AtomicBoolean();
        op.subscribe(v -> {}, ex -> {}, () -> completed.set(true));

        // Gate has been invoked once on frame1, and frame1 is held until gate completes.
        assertThat(gateCalls.get()).isEqualTo(1);
        assertThat(events).containsExactly("gate-start");

        gate.tryEmitEmpty();

        assertThat(completed.get()).isTrue();
        assertThat(settled.get()).isTrue();
        assertThat(gateCalls.get()).isEqualTo(1);
        assertThat(events).containsExactly(
                "gate-start", "gate-done", "first-emitted",
                "out:frame1|", "out:frame2|", "out:frame3|", "out:DONE", "settled"
        );
    }

    @Test
    void handlesCompleteArrivingWhileFirstFrameGateIsInFlight() {
        List<String> emitted = new ArrayList<>();
        Sinks.One<Void> gate = Sinks.one();
        AtomicBoolean settled = new AtomicBoolean();

        // Single-chunk stream completes immediately while markServed gate is still pending.
        DirectStreamBridgeOperator op = new DirectStreamBridgeOperator(
                Flux.just(buf("only-chunk|")),
                echoBridge("END"),
                ALLOC,
                NETTY_FACTORY,
                gate::asMono,
                () -> {},
                db -> emitted.add(readAndRelease(db)),
                () -> Mono.fromRunnable(() -> settled.set(true)),
                ex -> Mono.empty()
        );

        AtomicBoolean done = new AtomicBoolean();
        op.subscribe(v -> {}, ex -> {}, () -> done.set(true));
        assertThat(emitted).isEmpty();
        assertThat(settled.get()).isFalse();

        gate.tryEmitEmpty();

        assertThat(done.get()).isTrue();
        assertThat(settled.get()).isTrue();
        assertThat(emitted).containsExactly("only-chunk|", "END");
    }

    @Test
    void releasesHeldFirstFrameDirectBufferWhenCancelledDuringGate() {
        Sinks.One<Void> gate = Sinks.one();
        Sinks.Many<DataBuffer> upstream = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        List<NettyDataBuffer> seen = new ArrayList<>();

        DirectStreamBridgeOperator op = new DirectStreamBridgeOperator(
                upstream.asFlux().doOnCancel(() -> upstreamCancelled.set(true)),
                echoBridge(""),
                ALLOC,
                NETTY_FACTORY,
                gate::asMono,
                () -> {},
                db -> {
                    seen.add((NettyDataBuffer) db);
                    DataBufferUtils.release(db);
                },
                Mono::empty,
                ex -> Mono.empty()
        );

        Disposable sub = op.subscribe();
        DataBuffer first = buf("hello");
        ByteBuf nativeIn = ((NettyDataBuffer) first).getNativeBuffer();
        upstream.tryEmitNext(first);
        // Input direct buffer was released immediately after bridge.feed().
        assertThat(nativeIn.refCnt()).isEqualTo(0);

        sub.dispose();
        assertThat(upstreamCancelled.get()).isTrue();
        gate.tryEmitEmpty();
        assertThat(seen).isEmpty();
    }

    @Test
    void routesUpstreamErrorToFailureHandler() {
        AtomicBoolean failed = new AtomicBoolean();
        DirectStreamBridgeOperator op = new DirectStreamBridgeOperator(
                Flux.concat(Flux.just(buf("chunk1")), Flux.error(new IllegalStateException("boom"))),
                echoBridge(""),
                ALLOC,
                NETTY_FACTORY,
                Mono::empty,
                () -> {},
                DataBufferUtils::release,
                Mono::empty,
                ex -> Mono.fromRunnable(() -> failed.set("boom".equals(ex.getMessage())))
        );

        op.blockLast(Duration.ofSeconds(5));
        assertThat(failed.get()).isTrue();
    }
}
