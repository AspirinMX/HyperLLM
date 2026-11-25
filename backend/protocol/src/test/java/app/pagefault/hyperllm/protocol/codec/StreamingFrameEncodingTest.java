package app.pagefault.hyperllm.protocol.codec;

import app.pagefault.hyperllm.protocol.ir.BlockKind;
import app.pagefault.hyperllm.protocol.ir.CanonicalStreamEvent;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The encoders build token frames from constant templates plus a Jackson-rendered delta object. These
 * tests take the raw bytes they emit, split them back through the SSE splitter and assert that every
 * {@code data:} payload is still valid JSON of the expected shape - i.e. the optimization is invisible
 * on the wire.
 */
class StreamingFrameEncodingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "he\"llo\n\u4e16\u754c\\";

    private static List<String> payloads(List<byte[]> frames) {
        SseEventSplitter splitter = new SseEventSplitter();
        List<String> payloads = new ArrayList<>();
        for (byte[] frame : frames) {
            splitter.push(frame, event -> payloads.add(event.dataString()));
        }
        return payloads;
    }

    private static StreamRequestMetadata meta(ProtocolKind inbound, boolean usage) {
        return new StreamRequestMetadata(inbound, "virtual-model", usage, false, 10);
    }

    @Test
    void openAiChatDeltaFramesStayValidAndEscaped() throws Exception {
        OpenAiChatCodec codec = new OpenAiChatCodec(MAPPER);
        DownstreamStreamEncoder encoder = codec.newStreamEncoder(meta(ProtocolKind.OPENAI_CHAT, false));

        List<byte[]> frames = new ArrayList<>();
        frames.addAll(encoder.encode(new CanonicalStreamEvent.MessageStart("chatcmpl-1", "virtual-model", 1_700_000_000L)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.BlockStart(0, BlockKind.TEXT)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.TextDelta(0, TOKEN)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.ThinkingDelta(0, "think")));
        frames.addAll(encoder.finish());

        List<String> payloads = payloads(frames);
        assertThat(payloads).isNotEmpty();
        assertThat(payloads.get(payloads.size() - 1)).isEqualTo("[DONE]");

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        for (String payload : payloads) {
            if ("[DONE]".equals(payload)) {
                continue;
            }
            JsonNode node = MAPPER.readTree(payload);
            assertThat(node.path("object").asText()).isEqualTo("chat.completion.chunk");
            assertThat(node.path("model").asText()).isEqualTo("virtual-model");
            assertThat(node.path("created").asLong()).isEqualTo(1_700_000_000L);
            JsonNode choice = node.path("choices").get(0);
            assertThat(choice.path("index").asInt()).isZero();
            JsonNode delta = choice.path("delta");
            if (delta.hasNonNull("content")) {
                text.append(delta.get("content").asText());
            }
            if (delta.hasNonNull("reasoning_content")) {
                reasoning.append(delta.get("reasoning_content").asText());
            }
            assertThat(node.has("choices")).isTrue();
        }
        assertThat(text.toString()).isEqualTo(TOKEN);
        assertThat(reasoning.toString()).isEqualTo("think");
    }

    @Test
    void openAiChatFinishFrameCarriesStopReason() throws Exception {
        OpenAiChatCodec codec = new OpenAiChatCodec(MAPPER);
        DownstreamStreamEncoder encoder = codec.newStreamEncoder(meta(ProtocolKind.OPENAI_CHAT, false));
        List<byte[]> frames = new ArrayList<>();
        frames.addAll(encoder.encode(new CanonicalStreamEvent.MessageStart("chatcmpl-2", "virtual-model", 1L)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.TextDelta(0, "x")));
        frames.addAll(encoder.finish());

        JsonNode last = null;
        for (String payload : payloads(frames)) {
            if (!"[DONE]".equals(payload)) {
                last = MAPPER.readTree(payload);
            }
        }
        assertThat(last).isNotNull();
        assertThat(last.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
    }

    @Test
    void anthropicDeltaFramesStayValidAndEscaped() throws Exception {
        AnthropicMessagesCodec codec = new AnthropicMessagesCodec(MAPPER);
        DownstreamStreamEncoder encoder = codec.newStreamEncoder(meta(ProtocolKind.ANTHROPIC_MESSAGES, false));

        List<byte[]> frames = new ArrayList<>();
        frames.addAll(encoder.encode(new CanonicalStreamEvent.MessageStart("msg_1", "virtual-model", 1L)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.BlockStart(0, BlockKind.TEXT)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.TextDelta(0, TOKEN)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.BlockStop(0)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.BlockStart(1, BlockKind.THINKING)));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.ThinkingDelta(1, "deep")));
        frames.addAll(encoder.encode(new CanonicalStreamEvent.BlockStop(1)));
        frames.addAll(encoder.finish());

        List<String> payloads = payloads(frames);
        assertThat(payloads).isNotEmpty();

        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        boolean sawDeltaEvent = false;
        for (String payload : payloads) {
            JsonNode node = MAPPER.readTree(payload);
            if ("content_block_delta".equals(node.path("type").asText())) {
                sawDeltaEvent = true;
                JsonNode delta = node.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) {
                    text.append(delta.get("text").asText());
                }
                if ("thinking_delta".equals(delta.path("type").asText())) {
                    thinking.append(delta.get("thinking").asText());
                }
            }
        }
        assertThat(sawDeltaEvent).isTrue();
        assertThat(text.toString()).isEqualTo(TOKEN);
        assertThat(thinking.toString()).isEqualTo("deep");
    }

    @Test
    void reusedFrameBufferDoesNotLeakBetweenFrames() throws Exception {
        OpenAiChatCodec codec = new OpenAiChatCodec(MAPPER);
        DownstreamStreamEncoder encoder = codec.newStreamEncoder(meta(ProtocolKind.OPENAI_CHAT, false));
        List<byte[]> frames = new ArrayList<>();
        frames.addAll(encoder.encode(new CanonicalStreamEvent.MessageStart("chatcmpl-3", "virtual-model", 1L)));
        long[] tokens = {11L, 2222L, 333333L};
        for (long token : tokens) {
            frames.addAll(encoder.encode(new CanonicalStreamEvent.TextDelta(0, Long.toString(token))));
        }
        List<String> payloads = payloads(frames);
        List<String> contents = new ArrayList<>();
        for (String payload : payloads) {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode delta = node.path("choices").get(0).path("delta");
            if (delta.hasNonNull("content") && !delta.get("content").asText().isEmpty()) {
                contents.add(delta.get("content").asText());
            }
        }
        assertThat(contents).containsExactly("11", "2222", "333333");
    }
}
