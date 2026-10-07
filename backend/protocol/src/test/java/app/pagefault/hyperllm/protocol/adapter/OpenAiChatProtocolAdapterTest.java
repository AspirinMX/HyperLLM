package app.pagefault.hyperllm.protocol.adapter;

import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamRequest;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatProtocolAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiChatProtocolAdapter adapter = new OpenAiChatProtocolAdapter(mapper, new TokenEstimator());

    @Test
    void injectsStreamIncludeUsageAndPreservesExistingOptions() throws Exception {
        byte[] body = """
                {
                  "model": "gpt-5",
                  "stream": true,
                  "temperature": 0.2,
                  "messages": [
                    {"role": "system", "content": "You are helpful."},
                    {"role": "user", "content": "Hello"}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        PreparedUpstreamRequest prepared = adapter.prepareUpstreamRequest(body, "gpt-4o-mini");

        assertThat(prepared.canonicalRequest().clientRequestedUsage()).isFalse();
        assertThat(prepared.canonicalRequest().stream()).isTrue();
        JsonNode upstream = mapper.readTree(prepared.upstreamBody());
        assertThat(upstream.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(upstream.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(upstream.path("temperature").asDouble()).isEqualTo(0.2);
    }

    @Test
    void stripsInjectedUsageChunkAcrossFragmentedUtf8Stream() {
        byte[] req = """
                {"model":"gpt-5","stream":true,"messages":[{"role":"user","content":"Say hi in Chinese"}]}
                """.getBytes(StandardCharsets.UTF_8);
        PreparedUpstreamRequest prepared = adapter.prepareUpstreamRequest(req, "gpt-5");
        StreamUsageTracker tracker = adapter.newStreamTracker(prepared.canonicalRequest());

        String ssePayload = """
                data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":"你好"}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{"content":"世界"},"finish_reason":"stop"}]}

                data: {"id":"1","choices":[],"usage":{"prompt_tokens":14,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":6},"completion_tokens_details":{"reasoning_tokens":1}}}

                data: [DONE]

                """;
        byte[] allBytes = ssePayload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream downstream = new ByteArrayOutputStream();
        for (int i = 0; i < allBytes.length; i += 7) {
            byte[] slice = Arrays.copyOfRange(allBytes, i, Math.min(allBytes.length, i + 7));
            for (byte[] frame : tracker.feed(slice)) {
                downstream.writeBytes(frame);
            }
        }
        for (byte[] frame : tracker.complete()) {
            downstream.writeBytes(frame);
        }

        String emitted = downstream.toString(StandardCharsets.UTF_8);
        assertThat(emitted).contains("你好").contains("世界").contains("[DONE]");
        assertThat(emitted).doesNotContain("\"usage\"");

        TokenUsage usage = tracker.finalizeUsage(true);
        assertThat(usage).isEqualTo(new TokenUsage(14, 6, 0, 4, 1, false));
    }

    @Test
    void keepsUsageFrameWhenClientExplicitlyRequestedUsage() {
        byte[] req = """
                {"model":"gpt-5","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"Hi"}]}
                """.getBytes(StandardCharsets.UTF_8);
        PreparedUpstreamRequest prepared = adapter.prepareUpstreamRequest(req, "gpt-5");
        assertThat(prepared.canonicalRequest().clientRequestedUsage()).isTrue();

        StreamUsageTracker tracker = adapter.newStreamTracker(prepared.canonicalRequest());
        String sse = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n";
        ByteArrayOutputStream downstream = new ByteArrayOutputStream();
        for (byte[] frame : tracker.feed(sse.getBytes(StandardCharsets.UTF_8))) {
            downstream.writeBytes(frame);
        }
        assertThat(downstream.toString(StandardCharsets.UTF_8)).contains("\"usage\"");
    }

    @Test
    void fallsBackToJtokkitWhenStreamDisconnectsBeforeUsageFrame() {
        byte[] req = """
                {"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"Write a short greeting about Tokyo."}]}
                """.getBytes(StandardCharsets.UTF_8);
        PreparedUpstreamRequest prepared = adapter.prepareUpstreamRequest(req, "gpt-4o");
        StreamUsageTracker tracker = adapter.newStreamTracker(prepared.canonicalRequest());

        String partialSse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello from Tokyo!\"}}]}\n\n";
        tracker.feed(partialSse.getBytes(StandardCharsets.UTF_8));

        TokenUsage fallback = tracker.finalizeUsage(false);
        assertThat(fallback.estimated()).isTrue();
        assertThat(fallback.promptTokens()).isGreaterThan(5);
        assertThat(fallback.completionTokens()).isGreaterThan(2);
    }

    @Test
    void extractsFiveDimensionalNonStreamingUsage() {
        byte[] response = """
                {
                  "id": "chatcmpl-1",
                  "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 45,
                    "prompt_tokens_details": {"cached_tokens": 40, "cache_write_tokens": 10},
                    "completion_tokens_details": {"reasoning_tokens": 15}
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(adapter.extractNonStreamingUsage(response))
                .contains(new TokenUsage(120, 40, 10, 45, 15, false));
    }
}
