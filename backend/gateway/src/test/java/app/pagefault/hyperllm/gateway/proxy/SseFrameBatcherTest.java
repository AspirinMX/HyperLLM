package app.pagefault.hyperllm.gateway.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SseFrameBatcherTest {

    private static byte[] frame(String body) {
        return ("data: " + body + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void firstFrameIsNeverDelayed() {
        SseFrameBatcher batcher = new SseFrameBatcher(10_000_000L, 8192, 64);
        byte[] first = frame("a");
        assertThat(batcher.offer(first, 0L)).isSameAs(first);
        assertThat(batcher.hasPending()).isFalse();
    }

    @Test
    void laterFramesWaitForTheWindowAndFlushTogether() {
        SseFrameBatcher batcher = new SseFrameBatcher(1_000_000L, 8192, 64);
        batcher.offer(frame("a"), 0L);
        assertThat(batcher.offer(frame("b"), 10L)).isNull();
        assertThat(batcher.offer(frame("c"), 20L)).isNull();
        assertThat(batcher.hasPending()).isTrue();
        assertThat(batcher.poll(1_000_009L)).isNull();
        String flushed = new String(batcher.poll(1_000_010L), StandardCharsets.UTF_8);
        assertThat(flushed).isEqualTo("data: b\n\ndata: c\n\n");
        assertThat(batcher.hasPending()).isFalse();
    }

    @Test
    void oversizedBatchFlushesEarlyInsteadOfGrowing() {
        SseFrameBatcher batcher = new SseFrameBatcher(1_000_000L, 24, 64);
        batcher.offer(frame("a"), 0L);
        assertThat(batcher.offer(frame("b"), 1L)).isNull();
        byte[] flushed = batcher.offer(frame("0123456789"), 2L);
        assertThat(flushed).isNotNull();
        assertThat(new String(flushed, StandardCharsets.UTF_8)).isEqualTo("data: b\n\n");
    }

    @Test
    void oversizedSingleFramePreservesPreviouslyBufferedBytes() {
        SseFrameBatcher batcher = new SseFrameBatcher(1_000_000L, 16, 64);
        batcher.offer(frame("a"), 0L);
        assertThat(batcher.offer(frame("b"), 1L)).isNull();
        byte[] huge = frame("01234567890123456789");
        byte[] out = batcher.offer(huge, 2L);
        assertThat(new String(out, StandardCharsets.UTF_8))
                .isEqualTo("data: b\n\ndata: 01234567890123456789\n\n");
        assertThat(batcher.hasPending()).isFalse();
    }

    @Test
    void frameLimitFlushesEvenWhenBytesFit() {
        SseFrameBatcher batcher = new SseFrameBatcher(1_000_000L, 1_000, 1);
        batcher.offer(frame("a"), 0L);
        batcher.offer(frame("b"), 1L);
        byte[] flushed = batcher.offer(frame("c"), 2L);
        assertThat(flushed).isNotNull();
        assertThat(new String(flushed, StandardCharsets.UTF_8)).isEqualTo("data: b\n\n");
    }

    @Test
    void drainReturnsEveryBufferedFrame() {
        SseFrameBatcher batcher = new SseFrameBatcher(1_000_000L, 8192, 64);
        batcher.offer(frame("a"), 0L);
        batcher.offer(frame("b"), 1L);
        batcher.offer(frame("c"), 2L);
        assertThat(new String(batcher.drain(), StandardCharsets.UTF_8))
                .isEqualTo("data: b\n\ndata: c\n\n");
        assertThat(batcher.drain()).isNull();
    }

    @Test
    void coalescerDeduplicatesEntriesAndIsThreadSafeUnderConcurrentOffersAndTicks() throws Exception {
        try (SseFrameCoalescer coalescer = new SseFrameCoalescer(2_000_000L, 1L, 64 * 1024, 256)) {
            int streams = 16;
            int framesPerStream = 200;
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch done = new CountDownLatch(streams);
            ByteArrayOutputStream[] outputs = new ByteArrayOutputStream[streams];

            for (int s = 0; s < streams; s++) {
                final int idx = s;
                outputs[idx] = new ByteArrayOutputStream();
                pool.submit(() -> {
                    try {
                        SseFrameBatcher batcher = coalescer.newBatcher();
                        SseFrameCoalescer.Sink sink = batch -> {
                            synchronized (outputs[idx]) {
                                outputs[idx].writeBytes(batch);
                            }
                        };
                        for (int i = 0; i < framesPerStream; i++) {
                            coalescer.offer(batcher, sink, frame("s" + idx + "-" + i));
                            // Queue depth must never grow unboundedly via duplicate entries
                            assertThat(coalescer.pendingQueueSize()).isLessThanOrEqualTo(streams * 2);
                        }
                        coalescer.drain(batcher, sink);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            for (int s = 0; s < streams; s++) {
                String expected = buildExpected(s, framesPerStream);
                assertThat(outputs[s].toString(StandardCharsets.UTF_8)).isEqualTo(expected);
            }
        }
    }

    private static String buildExpected(int streamIdx, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("data: s").append(streamIdx).append('-').append(i).append("\n\n");
        }
        return sb.toString();
    }
}
