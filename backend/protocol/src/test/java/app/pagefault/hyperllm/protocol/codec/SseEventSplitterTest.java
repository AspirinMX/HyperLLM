package app.pagefault.hyperllm.protocol.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseEventSplitterTest {

    private static List<SseEventSplitter.SseEvent> pushAll(SseEventSplitter splitter, String text) {
        List<SseEventSplitter.SseEvent> events = new ArrayList<>();
        splitter.push(text.getBytes(StandardCharsets.UTF_8), events::add);
        return events;
    }

    private static List<String> payloads(SseEventSplitter splitter, String text) {
        return pushAll(splitter, text).stream().map(SseEventSplitter.SseEvent::dataString).toList();
    }

    @Test
    void splitsOnBlankLine() {
        SseEventSplitter splitter = new SseEventSplitter();
        assertThat(payloads(splitter, "data: a\n\ndata: b\n\n")).containsExactly("a", "b");
    }

    @Test
    void handlesCarriageReturnLineFeedDelimiter() {
        SseEventSplitter splitter = new SseEventSplitter();
        assertThat(payloads(splitter, "data: {\"x\":1}\r\n\r\n")).containsExactly("{\"x\":1}");
    }

    @Test
    void stripsExactlyOneLeadingSpaceAfterDataColon() {
        SseEventSplitter splitter = new SseEventSplitter();
        assertThat(payloads(splitter, "data:  padded\n\n")).containsExactly(" padded");
        assertThat(payloads(new SseEventSplitter(), "data:nospace\n\n")).containsExactly("nospace");
    }

    @Test
    void parsesEventName() {
        SseEventSplitter splitter = new SseEventSplitter();
        List<SseEventSplitter.SseEvent> events = pushAll(splitter, "event: content_block_delta\ndata: {}\n\n");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).hasEventName()).isTrue();
        assertThat(events.get(0).eventNameString()).isEqualTo("content_block_delta");
        assertThat(events.get(0).dataString()).isEqualTo("{}");
    }

    @Test
    void joinsMultipleDataLinesWithNewline() {
        SseEventSplitter splitter = new SseEventSplitter();
        assertThat(payloads(splitter, "data: line1\ndata: line2\ndata: line3\n\n"))
                .containsExactly("line1\nline2\nline3");
    }

    @Test
    void ignoresCommentAndUnknownFields() {
        SseEventSplitter splitter = new SseEventSplitter();
        assertThat(payloads(splitter, ": keepalive\nid: 7\ndata: body\n\n")).containsExactly("body");
    }

    @Test
    void detectsDoneMarkerWithoutDecoding() {
        SseEventSplitter splitter = new SseEventSplitter();
        List<SseEventSplitter.SseEvent> events = pushAll(splitter, "data: [DONE]\n\n");
        assertThat(events).hasSize(1);
        assertThat(SseEventSplitter.isDoneMarker(events.get(0))).isTrue();
        assertThat(events.get(0).dataEqualsAscii("[DONE]")).isTrue();
        assertThat(events.get(0).dataEqualsAscii("[DONEX]")).isFalse();
    }

    @Test
    void keepsMultiByteCharactersIntactWhenSplitAcrossChunks() {
        SseEventSplitter splitter = new SseEventSplitter();
        byte[] frame = "data: \u4f60\u597d\u4e16\u754c\n\n".getBytes(StandardCharsets.UTF_8);
        List<SseEventSplitter.SseEvent> events = new ArrayList<>();
        for (byte b : frame) {
            // one byte at a time: every UTF-8 sequence is cut in the middle at some point
            splitter.push(new byte[] {b}, events::add);
        }
        assertThat(events).hasSize(1);
        assertThat(events.get(0).dataString()).isEqualTo("\u4f60\u597d\u4e16\u754c");
    }

    @Test
    void emitsNothingUntilDelimiterArrives() {
        SseEventSplitter splitter = new SseEventSplitter();
        assertThat(pushAll(splitter, "data: partial")).isEmpty();
        assertThat(pushAll(splitter, " more\n")).isEmpty();
        assertThat(payloads(splitter, "\n")).containsExactly("partial more");
    }

    @Test
    void flushRemainderEmitsTrailingFrameWithoutBlankLine() {
        SseEventSplitter splitter = new SseEventSplitter();
        List<SseEventSplitter.SseEvent> events = new ArrayList<>();
        splitter.push("data: tail".getBytes(StandardCharsets.UTF_8), events::add);
        splitter.flushRemainder(events::add);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).dataString()).isEqualTo("tail");
    }

    @Test
    void flushRemainderSkipsEmptyTail() {
        SseEventSplitter splitter = new SseEventSplitter();
        List<SseEventSplitter.SseEvent> events = new ArrayList<>();
        splitter.flushRemainder(events::add);
        assertThat(events).isEmpty();
    }

    @Test
    void handlesLargeFrameDeliveredInSmallChunks() {
        SseEventSplitter splitter = new SseEventSplitter();
        String body = "x".repeat(200_000);
        byte[] frame = ("data: " + body + "\n\n").getBytes(StandardCharsets.UTF_8);
        List<SseEventSplitter.SseEvent> events = new ArrayList<>();
        int chunk = 997;
        for (int i = 0; i < frame.length; i += chunk) {
            int length = Math.min(chunk, frame.length - i);
            byte[] slice = new byte[length];
            System.arraycopy(frame, i, slice, 0, length);
            splitter.push(slice, events::add);
        }
        assertThat(events).hasSize(1);
        assertThat(events.get(0).dataLength()).isEqualTo(body.length());
        assertThat(events.get(0).dataString()).isEqualTo(body);
    }

    @Test
    void keepsFramesIndependentAcrossManyPushes() {
        SseEventSplitter splitter = new SseEventSplitter();
        List<String> collected = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            splitter.push(("data: f" + i + "\n\n").getBytes(StandardCharsets.UTF_8), e -> collected.add(e.dataString()));
        }
        assertThat(collected).hasSize(100);
        assertThat(collected.get(0)).isEqualTo("f0");
        assertThat(collected.get(99)).isEqualTo("f99");
    }
}
