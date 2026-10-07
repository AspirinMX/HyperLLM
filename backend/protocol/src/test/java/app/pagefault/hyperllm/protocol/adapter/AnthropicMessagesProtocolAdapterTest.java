package app.pagefault.hyperllm.protocol.adapter;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamRequest;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicMessagesProtocolAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicMessagesProtocolAdapter adapter =
            new AnthropicMessagesProtocolAdapter(mapper, new TokenEstimator());

    @Test
    void requiresPositiveMaxTokensAndRewritesModel() throws Exception {
        byte[] missingMax = """
                {"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"hi"}]}
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> adapter.parseRequest(missingMax))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);

        byte[] valid = """
                {
                  "model": "claude-sonnet-4-5",
                  "max_tokens": 1024,
                  "stream": true,
                  "system": "Be concise.",
                  "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello Anthropic"}]}]
                }
                """.getBytes(StandardCharsets.UTF_8);
        PreparedUpstreamRequest prepared = adapter.prepareUpstreamRequest(valid, "claude-3-7-sonnet-20250219");
        JsonNode json = mapper.readTree(prepared.upstreamBody());
        assertThat(json.path("model").asText()).isEqualTo("claude-3-7-sonnet-20250219");
        assertThat(prepared.canonicalRequest().maxTokens()).isEqualTo(1024);
        assertThat(prepared.canonicalRequest().rawPromptText()).contains("Be concise.").contains("Hello Anthropic");
    }

    @Test
    void passesSseVerbatimAndCombinesStartAndDeltaUsage() {
        byte[] valid = """
                {
                  "model": "claude-sonnet-4-5",
                  "max_tokens": 512,
                  "stream": true,
                  "messages": [{"role": "user", "content": "Count to 2"}]
                }
                """.getBytes(StandardCharsets.UTF_8);
        PreparedUpstreamRequest prepared = adapter.prepareUpstreamRequest(valid, "claude-sonnet-4-5");
        StreamUsageTracker tracker = adapter.newStreamTracker(prepared.canonicalRequest());

        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":22,"cache_read_input_tokens":8,"cache_creation_input_tokens":4,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"One, two."}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
        for (byte[] frame : tracker.feed(sse.getBytes(StandardCharsets.UTF_8))) {
            forwarded.writeBytes(frame);
        }
        assertThat(forwarded.toString(StandardCharsets.UTF_8)).isEqualTo(sse);

        TokenUsage usage = tracker.finalizeUsage(true);
        assertThat(usage).isEqualTo(new TokenUsage(22, 8, 4, 9, 0, false));
    }

    @Test
    void estimatesOutputViaJtokkitIfAbortedAfterMessageStart() {
        byte[] valid = """
                {"model":"claude-sonnet-4-5","max_tokens":512,"stream":true,"messages":[{"role":"user","content":"Hello"}]}
                """.getBytes(StandardCharsets.UTF_8);
        PreparedUpstreamRequest prepared = adapter.prepareUpstreamRequest(valid, "claude-sonnet-4-5");
        StreamUsageTracker tracker = adapter.newStreamTracker(prepared.canonicalRequest());

        String partial = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":18,"cache_read_input_tokens":5,"cache_creation_input_tokens":0}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Partial answer streaming now"}}

                """;
        tracker.feed(partial.getBytes(StandardCharsets.UTF_8));
        TokenUsage usage = tracker.finalizeUsage(false);
        assertThat(usage.estimated()).isTrue();
        assertThat(usage.promptTokens()).isEqualTo(18);
        assertThat(usage.cachedReadTokens()).isEqualTo(5);
        assertThat(usage.completionTokens()).isGreaterThan(0);
    }
}
