package app.pagefault.hyperllm.gateway.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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
}
