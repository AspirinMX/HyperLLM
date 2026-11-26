package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.codec.SseEventSplitter;
import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Commit 4 push-based zero-allocation {@link StreamBridge#feed(byte[], int, int, ByteSink)}
 * across all 4x4 streaming protocol pairs, including Responses &amp; Gemini fast-path deltas and
 * split multi-byte UTF-8 chunks.
 */
class PushStreamingBridgeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolTranslator translator = new ProtocolTranslator(mapper);

    @Test
    void pushStreamingWorksAcrossAllSixteenProtocolPairsAndArbitraryChunkSplits() throws Exception {
        String openAiStream = ""
                + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-5\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-5\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello \\\"世界\\\" 🚀\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-5\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\\nDone!\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-5\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"gpt-5\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}}\n\n"
                + "data: [DONE]\n\n";

        String anthropicStream = ""
                + "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\",\"content\":[],\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \\\"世界\\\" 🚀\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"\\nDone!\"}}\n\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

        String responsesStream = ""
                + "event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":1700000000,\"status\":\"in_progress\",\"model\":\"gpt-5\",\"output\":[]}}\n\n"
                + "event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"sequence_number\":1,\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"in_progress\",\"content\":[]}}\n\n"
                + "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"sequence_number\":2,\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hello \\\"世界\\\" 🚀\"}\n\n"
                + "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"sequence_number\":3,\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"\\nDone!\"}\n\n"
                + "event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"sequence_number\":4,\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\"}}\n\n"
                + "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":5,\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":12,\"output_tokens\":5,\"total_tokens\":17}}}\n\n";

        String geminiStream = ""
                + "data: {\"responseId\":\"gem_1\",\"modelVersion\":\"gemini-2.5-pro\",\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello \\\"世界\\\" 🚀\"}]}}]}\n\n"
                + "data: {\"responseId\":\"gem_1\",\"modelVersion\":\"gemini-2.5-pro\",\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"\\nDone!\"}]}}]}\n\n"
                + "data: {\"responseId\":\"gem_1\",\"modelVersion\":\"gemini-2.5-pro\",\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":5,\"totalTokenCount\":17}}\n\n";

        List<UpstreamFixture> upstreams = List.of(
                new UpstreamFixture(ProtocolKind.OPENAI_CHAT, openAiStream),
                new UpstreamFixture(ProtocolKind.ANTHROPIC_MESSAGES, anthropicStream),
                new UpstreamFixture(ProtocolKind.OPENAI_RESPONSES, responsesStream),
                new UpstreamFixture(ProtocolKind.GEMINI_GENERATE_CONTENT, geminiStream)
        );

        for (UpstreamFixture up : upstreams) {
            byte[] raw = up.sse().getBytes(StandardCharsets.UTF_8);
            for (ProtocolKind inbound : ProtocolKind.values()) {
                StreamRequestMetadata meta = new StreamRequestMetadata(inbound, "virtual-model", true, true, 12);
                StreamBridge bridge = translator.newStreamBridge(meta, up.kind());
                ByteSink.HeapByteSink out = new ByteSink.HeapByteSink(512);

                // Feed in tiny 19-byte slices (splitting UTF-8 sequences and SSE boundaries) with non-zero offsets!
                byte[] padded = new byte[raw.length + 10];
                System.arraycopy(raw, 0, padded, 5, raw.length);
                int pos = 5;
                int end = 5 + raw.length;
                while (pos < end) {
                    int step = Math.min(19, end - pos);
                    bridge.feed(padded, pos, step, out);
                    pos += step;
                }
                bridge.complete(out);

                String emittedText = extractEmittedText(inbound, out.toExactByteArray());
                assertThat(emittedText)
                        .as("Text round-trip for upstream %s -> downstream %s", up.kind(), inbound)
                        .isEqualTo("Hello \"世界\" 🚀\nDone!");

                TokenUsage usage = bridge.finalizeUsage(true);
                assertThat(usage.totalInputTokens()).isEqualTo(12);
                assertThat(usage.totalOutputTokens()).isEqualTo(5);
            }
        }
    }

    private String extractEmittedText(ProtocolKind downstream, byte[] sseBytes) throws Exception {
        SseEventSplitter splitter = new SseEventSplitter();
        List<String> payloads = new ArrayList<>();
        splitter.push(sseBytes, ev -> {
            if (ev.hasData() && !SseEventSplitter.isDoneMarker(ev)) {
                payloads.add(ev.dataString());
            }
        });
        StringBuilder sb = new StringBuilder();
        for (String payload : payloads) {
            JsonNode root = mapper.readTree(payload);
            switch (downstream) {
                case OPENAI_CHAT -> {
                    JsonNode choices = root.get("choices");
                    if (choices != null && choices.isArray() && !choices.isEmpty()) {
                        JsonNode content = choices.get(0).path("delta").get("content");
                        if (content != null && content.isTextual()) {
                            sb.append(content.asText());
                        }
                    }
                }
                case ANTHROPIC_MESSAGES -> {
                    if ("content_block_delta".equals(root.path("type").asText())
                            && "text_delta".equals(root.path("delta").path("type").asText())) {
                        sb.append(root.path("delta").path("text").asText());
                    }
                }
                case OPENAI_RESPONSES -> {
                    if ("response.output_text.delta".equals(root.path("type").asText())) {
                        sb.append(root.path("delta").asText());
                    }
                }
                case GEMINI_GENERATE_CONTENT -> {
                    JsonNode cands = root.get("candidates");
                    if (cands != null && cands.isArray() && !cands.isEmpty()) {
                        JsonNode parts = cands.get(0).path("content").get("parts");
                        if (parts != null && parts.isArray()) {
                            for (JsonNode p : parts) {
                                if (p.hasNonNull("text") && !p.path("thought").asBoolean(false)) {
                                    sb.append(p.get("text").asText());
                                }
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private record UpstreamFixture(ProtocolKind kind, String sse) {
    }
}
