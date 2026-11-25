package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.codec.SseEventSplitter;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An upstream read frequently carries more than one SSE event. The bridge collapses everything one
 * chunk translates to into a single byte array so the gateway issues one socket write per read - the
 * aggregate framing ({@code data: ...\n\n} repeated) is unchanged.
 */
class StreamBridgeChunkMergeTest {

    private static final String FIRST = "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},"
            + "\"finish_reason\":null}]}\n\n";
    private static final String SECOND = "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b\"},"
            + "\"finish_reason\":null}]}\n\n";

    private static StreamBridge openAiBridge() {
        ProtocolTranslator translator = new ProtocolTranslator(new ObjectMapper());
        return translator.newStreamBridge(
                new StreamRequestMetadata(ProtocolKind.OPENAI_CHAT, "m", false, false, 10),
                ProtocolKind.OPENAI_CHAT);
    }

    @Test
    void twoEventsInOneChunkBecomeASingleWrite() {
        StreamBridge bridge = openAiBridge();
        byte[] downstream = bridge.feed((FIRST + SECOND).getBytes(StandardCharsets.UTF_8));
        assertThat(downstream).isNotEmpty();

        SseEventSplitter splitter = new SseEventSplitter();
        List<String> payloads = new ArrayList<>();
        splitter.push(downstream, event -> payloads.add(event.dataString()));
        // first frame carries the assistant role chunk, then one frame per content delta - all in one array
        assertThat(payloads).hasSize(3);
        assertThat(payloads.get(0)).contains("\"role\":\"assistant\"");
        assertThat(payloads.get(1)).contains("\"content\":\"a\"");
        assertThat(payloads.get(2)).contains("\"content\":\"b\"");
    }

    @Test
    void eventSplitAcrossChunksStaysBufferedInsideTheBridge() {
        StreamBridge bridge = openAiBridge();
        int cut = FIRST.length() / 2;
        byte[] head = FIRST.substring(0, cut).getBytes(StandardCharsets.UTF_8);
        byte[] tail = FIRST.substring(cut).getBytes(StandardCharsets.UTF_8);

        byte[] firstWrite = bridge.feed(head);
        assertThat(firstWrite).isEmpty();

        byte[] secondWrite = bridge.feed(tail);
        SseEventSplitter splitter = new SseEventSplitter();
        List<String> payloads = new ArrayList<>();
        splitter.push(secondWrite, event -> payloads.add(event.dataString()));
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).contains("\"role\":\"assistant\"");
        assertThat(payloads.get(1)).contains("\"content\":\"a\"");
    }

    @Test
    void chunkWithoutCompleteEventWritesNothing() {
        StreamBridge bridge = openAiBridge();
        assertThat(bridge.feed("data: {\"partial\":".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
}
