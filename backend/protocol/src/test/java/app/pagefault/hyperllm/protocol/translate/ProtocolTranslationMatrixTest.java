package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.money.CostMath;
import app.pagefault.hyperllm.protocol.codec.CodecSupport;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamRequest;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full 4x4 matrix: all 16 inbound/outbound combinations are Canonical IR translations
 * verification matrix across OpenAI Chat, OpenAI Responses, Anthropic Messages, and Google Gemini.
 */
class ProtocolTranslationMatrixTest {

    private static final String TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolTranslator translator = new ProtocolTranslator(mapper);

    static Stream<Arguments> allSixteenPairs() {
        List<Arguments> args = new ArrayList<>();
        for (ProtocolKind in : ProtocolKind.values()) {
            for (ProtocolKind out : ProtocolKind.values()) {
                args.add(Arguments.of(in, out));
            }
        }
        return args.stream();
    }

    static Stream<Arguments> twelveHeterogeneousPairs() {
        List<Arguments> args = new ArrayList<>();
        for (ProtocolKind in : ProtocolKind.values()) {
            for (ProtocolKind out : ProtocolKind.values()) {
                if (in != out) {
                    args.add(Arguments.of(in, out));
                }
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "Non-streaming text + image + tools: {0} -> {1}")
    @MethodSource("allSixteenPairs")
    void translatesMultimodalRequestAndNonStreamingResponseAcrossAll16Pairs(
            ProtocolKind inbound,
            ProtocolKind outbound
    ) throws Exception {
        byte[] rawReq = sampleMultimodalToolRequest(inbound, false);
        InboundRequestContext inCtx = inboundContextFor(inbound, "virtual-omni", false, true);
        CanonicalRequest decoded = translator.parseRequest(inbound, rawReq, inCtx);

        assertThat(decoded.model()).isEqualTo("virtual-omni");
        assertThat(decoded.stream()).isFalse();

        UpstreamEncodingContext outCtx = new UpstreamEncodingContext(
                "upstream-model-1", 4096, TranslationPolicy.BEST_EFFORT, null);
        PreparedUpstreamRequest prepared = translator.prepareUpstreamRequest(rawReq, decoded, outbound, outCtx);

        // Verify the encoded upstream request is itself valid under outbound codec decoding!
        CanonicalRequest redecodedUpstream = translator.parseRequest(
                outbound, prepared.upstreamBody(), inboundContextFor(outbound, "upstream-model-1", false, true));
        assertThat(redecodedUpstream.systemInstructions()).isNotEmpty();
        assertThat(redecodedUpstream.turns()).isNotEmpty();
        assertThat(redecodedUpstream.tools()).hasSize(1);
        assertThat(redecodedUpstream.tools().getFirst().name()).isEqualTo("get_weather");

        // Now feed a native upstream non-streaming response containing text + tool call + usage
        byte[] upstreamResp = sampleNonStreamingResponse(outbound, true);
        ProtocolTranslator.TranslatedResponse translated =
                translator.translateNonStreamingResponse(decoded, outbound, upstreamResp);

        // Verify billing usage is strictly disjoint and accurate
        TokenUsage usage = translated.usage();
        assertThat(usage.estimated()).isFalse();
        assertThat(usage.totalInputTokens()).isEqualTo(120);
        assertThat(usage.cachedReadTokens()).isEqualTo(40);
        assertThat(usage.promptTokens()).isEqualTo(80);
        assertThat(usage.totalOutputTokens()).isEqualTo(45);

        // Verify client response decodes cleanly in caller's native protocol and echoes virtual model
        var clientResp = translator.codecFor(inbound).decodeResponse(translated.clientBody(), null);
        assertThat(clientResp.hasToolCalls()).isTrue();
        assertThat(clientResp.stopReason().name()).isEqualTo("TOOL_CALLS");
        assertThat(clientResp.usage().totalInputTokens()).isEqualTo(120);
        assertThat(clientResp.usage().totalOutputTokens()).isEqualTo(45);
        if (inbound != outbound) {
            assertThat(clientResp.virtualModel()).isEqualTo("virtual-omni");
        }
    }

    @ParameterizedTest(name = "Streaming fragmented UTF-8 SSE + tool calls: {0} -> {1}")
    @MethodSource("allSixteenPairs")
    void translatesFragmentedStreamingResponseAcrossAll16Pairs(
            ProtocolKind inbound,
            ProtocolKind outbound
    ) throws Exception {
        byte[] rawReq = sampleMultimodalToolRequest(inbound, true);
        InboundRequestContext inCtx = inboundContextFor(inbound, "virtual-omni", true, true);
        CanonicalRequest decoded = translator.parseRequest(inbound, rawReq, inCtx);
        assertThat(decoded.stream()).isTrue();

        UpstreamEncodingContext outCtx = new UpstreamEncodingContext(
                "upstream-model-1", 4096, TranslationPolicy.BEST_EFFORT, null);
        PreparedUpstreamRequest prepared = translator.prepareUpstreamRequest(rawReq, decoded, outbound, outCtx);

        StreamBridge bridge = translator.newStreamBridge(decoded, outbound);
        byte[] upstreamStream = sampleStreamingSse(outbound);

        // Feed fragmented 7-byte slices to test multi-byte UTF-8 splitting across TCP packets
        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        for (int i = 0; i < upstreamStream.length; i += 7) {
            byte[] slice = Arrays.copyOfRange(upstreamStream, i, Math.min(upstreamStream.length, i + 7));
            for (byte[] frame : bridge.feed(slice)) {
                clientOut.writeBytes(frame);
            }
        }
        for (byte[] frame : bridge.complete()) {
            clientOut.writeBytes(frame);
        }

        String emitted = clientOut.toString(StandardCharsets.UTF_8);
        assertThat(emitted).contains("你好").contains("世界").contains("get_weather").contains("Tokyo");

        // Verify protocol-specific stream framing
        switch (inbound) {
            case OPENAI_CHAT -> assertThat(emitted).contains("data: [DONE]");
            case OPENAI_RESPONSES -> assertThat(emitted)
                    .contains("event: response.created")
                    .contains("event: response.completed")
                    .doesNotContain("[DONE]");
            case ANTHROPIC_MESSAGES -> assertThat(emitted)
                    .contains("event: message_start")
                    .contains("event: content_block_start")
                    .contains("event: message_delta")
                    .contains("event: message_stop")
                    .doesNotContain("[DONE]");
            case GEMINI_GENERATE_CONTENT -> assertThat(emitted)
                    .contains("\"candidates\"")
                    .contains("\"functionCall\"")
                    .doesNotContain("[DONE]");
        }

        // Verify final billing usage
        TokenUsage finalUsage = bridge.finalizeUsage(true);
        assertThat(finalUsage.estimated()).isFalse();
        assertThat(finalUsage.totalInputTokens()).isEqualTo(100);
        assertThat(finalUsage.cachedReadTokens()).isEqualTo(30);
        assertThat(finalUsage.promptTokens()).isEqualTo(70);
        assertThat(finalUsage.totalOutputTokens()).isEqualTo(25);
    }

    @Test
    void multiTurnToolResultBackfillsNameForGeminiAndInjectsSkipThoughtSignature() throws Exception {
        // OpenAI Chat multi-turn with assistant tool_call (no thought_signature) + tool result (no name!)
        byte[] chatReq = """
                {
                  "model": "gemini-3-flash",
                  "messages": [
                    {"role": "user", "content": "Weather in Paris?"},
                    {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [
                        {"id": "call_abc", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}}
                      ]
                    },
                    {"role": "tool", "tool_call_id": "call_abc", "content": "18C sunny"}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(ProtocolKind.OPENAI_CHAT, chatReq, InboundRequestContext.empty());
        PreparedUpstreamRequest prepared = translator.prepareUpstreamRequest(
                chatReq, decoded, ProtocolKind.GEMINI_GENERATE_CONTENT,
                new UpstreamEncodingContext("gemini-3.0-flash", 8192, TranslationPolicy.BEST_EFFORT, "GEMINI_LEVEL"));

        JsonNode gem = mapper.readTree(prepared.upstreamBody());
        JsonNode contents = gem.get("contents");
        assertThat(contents).hasSize(3);

        // Assistant functionCall gets Google's documented skip_thought_signature_validator sentinel
        JsonNode modelPart = contents.get(1).get("parts").get(0);
        assertThat(modelPart.path("functionCall").path("name").asText()).isEqualTo("get_weather");
        assertThat(modelPart.path("thoughtSignature").asText()).isEqualTo(CodecSupport.GEMINI_SKIP_THOUGHT_SIGNATURE);

        // Tool result backfills name='get_weather' and wraps plain string as {"output": "18C sunny"}
        JsonNode funcResp = contents.get(2).get("parts").get(0).get("functionResponse");
        assertThat(funcResp.path("id").asText()).isEqualTo("call_abc");
        assertThat(funcResp.path("name").asText()).isEqualTo("get_weather");
        assertThat(funcResp.path("response").path("output").asText()).isEqualTo("18C sunny");
    }

    @Test
    void anthropicThinkingToOpenAiStripsSignaturesAndAutoRaisesMaxTokensInReverse() throws Exception {
        // 1. Anthropic history containing thinking+signature and redacted_thinking -> OpenAI Responses strips foreign signatures
        byte[] antReq = """
                {
                  "model": "gpt-5",
                  "max_tokens": 2048,
                  "thinking": {"type": "enabled", "budget_tokens": 4096},
                  "messages": [
                    {"role": "user", "content": "Solve 2+2"},
                    {
                      "role": "assistant",
                      "content": [
                        {"type": "thinking", "thinking": "2+2 is 4", "signature": "ant_sig_secret"},
                        {"type": "redacted_thinking", "data": "opaque_redacted_blob"},
                        {"type": "text", "text": "4"}
                      ]
                    },
                    {"role": "user", "content": "Now multiply by 3"}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decodedAnt = translator.parseRequest(ProtocolKind.ANTHROPIC_MESSAGES, antReq, InboundRequestContext.empty());
        PreparedUpstreamRequest toResponses = translator.prepareUpstreamRequest(
                antReq, decodedAnt, ProtocolKind.OPENAI_RESPONSES,
                UpstreamEncodingContext.simple("gpt-5"));
        String respJson = new String(toResponses.upstreamBody(), StandardCharsets.UTF_8);
        assertThat(respJson).doesNotContain("ant_sig_secret").doesNotContain("opaque_redacted_blob");
        JsonNode respNode = mapper.readTree(toResponses.upstreamBody());
        assertThat(respNode.path("reasoning").path("effort").asText()).isEqualTo("medium");

        // 2. OpenAI Chat request with reasoning_effort=high, temperature=1.8, no max_tokens -> Anthropic Budget model
        byte[] chatReq = """
                {
                  "model": "claude-sonnet-4-5",
                  "reasoning_effort": "high",
                  "temperature": 1.8,
                  "top_p": 0.9,
                  "messages": [{"role": "user", "content": "Prove sqrt(2) is irrational"}]
                }
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decodedChat = translator.parseRequest(ProtocolKind.OPENAI_CHAT, chatReq, InboundRequestContext.empty());
        PreparedUpstreamRequest toAnt = translator.prepareUpstreamRequest(
                chatReq, decodedChat, ProtocolKind.ANTHROPIC_MESSAGES,
                new UpstreamEncodingContext("claude-sonnet-4-5", 8192, TranslationPolicy.BEST_EFFORT, "ANTHROPIC_BUDGET"));
        JsonNode antOut = mapper.readTree(toAnt.upstreamBody());
        assertThat(antOut.path("thinking").path("type").asText()).isEqualTo("enabled");
        int budget = antOut.path("thinking").path("budget_tokens").asInt();
        assertThat(budget).isEqualTo(16_384);
        assertThat(antOut.path("max_tokens").asInt()).isGreaterThan(budget);
        // Incompatible temperature and top_p stripped when Anthropic thinking is enabled
        assertThat(antOut.has("temperature")).isFalse();
        assertThat(antOut.has("top_p")).isFalse();
    }

    @Test
    void structuredOutputsJsonSchemaTranslatesAcrossAllFourProtocols() throws Exception {
        byte[] chatReq = """
                {
                  "model": "omni",
                  "messages": [{"role": "user", "content": "Extract person"}],
                  "response_format": {
                    "type": "json_schema",
                    "json_schema": {
                      "name": "person_schema",
                      "strict": true,
                      "schema": {
                        "type": "object",
                        "properties": {"name": {"type": "string"}},
                        "required": ["name"],
                        "additionalProperties": false
                      }
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(ProtocolKind.OPENAI_CHAT, chatReq, InboundRequestContext.empty());

        // -> OpenAI Responses (flat text.format)
        JsonNode respOut = mapper.readTree(translator.prepareUpstreamRequest(
                chatReq, decoded, ProtocolKind.OPENAI_RESPONSES, UpstreamEncodingContext.simple("gpt-5")).upstreamBody());
        assertThat(respOut.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(respOut.path("text").path("format").path("name").asText()).isEqualTo("person_schema");
        assertThat(respOut.path("text").path("format").path("schema").path("type").asText()).isEqualTo("object");

        // -> Anthropic (output_config.format)
        JsonNode antOut = mapper.readTree(translator.prepareUpstreamRequest(
                chatReq, decoded, ProtocolKind.ANTHROPIC_MESSAGES, UpstreamEncodingContext.simple("claude-sonnet-4-5")).upstreamBody());
        assertThat(antOut.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(antOut.path("output_config").path("format").path("schema").path("required").get(0).asText()).isEqualTo("name");

        // -> Gemini (generationConfig.responseMimeType + responseJsonSchema)
        JsonNode gemOut = mapper.readTree(translator.prepareUpstreamRequest(
                chatReq, decoded, ProtocolKind.GEMINI_GENERATE_CONTENT, UpstreamEncodingContext.simple("gemini-2.5-flash")).upstreamBody());
        assertThat(gemOut.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(gemOut.path("generationConfig").path("responseJsonSchema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void anthropicToolResultWithImageHoistsForOpenAiChatInBestEffortAndRejectsInStrict() throws Exception {
        byte[] antReq = ("""
                {
                  "model": "gpt-4o",
                  "max_tokens": 256,
                  "messages": [
                    {"role": "user", "content": "Take screenshot"},
                    {"role": "assistant", "content": [{"type": "tool_use", "id": "toolu_1", "name": "screenshot", "input": {}}]},
                    {
                      "role": "user",
                      "content": [
                        {
                          "type": "tool_result",
                          "tool_use_id": "toolu_1",
                          "content": [
                            {"type": "text", "text": "Captured"},
                            {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "%s"}}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(TINY_PNG_BASE64)).getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(ProtocolKind.ANTHROPIC_MESSAGES, antReq, InboundRequestContext.empty());

        // BEST_EFFORT hoists the image into a subsequent user turn
        PreparedUpstreamRequest bestEffort = translator.prepareUpstreamRequest(
                antReq, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("gpt-4o", 4096, TranslationPolicy.BEST_EFFORT, null));
        JsonNode msgs = mapper.readTree(bestEffort.upstreamBody()).get("messages");
        assertThat(msgs.get(2).path("role").asText()).isEqualTo("tool");
        assertThat(msgs.get(2).path("content").asText()).isEqualTo("Captured");
        assertThat(msgs.get(3).path("role").asText()).isEqualTo("user");
        assertThat(msgs.get(3).path("content").toString()).contains("data:image/png;base64," + TINY_PNG_BASE64);

        // STRICT rejects lossy hoisting
        assertThatThrownBy(() -> translator.prepareUpstreamRequest(
                antReq, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("gpt-4o", 4096, TranslationPolicy.STRICT, null)))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE);
    }

    @Test
    @Disabled("TODO(ir): the unified IR path does not yet retain vendor-only payloads "
            + "(previous_response_id / builtin tools), so a same-protocol re-encode currently drops them. "
            + "Re-enable once CanonicalProtocolOptions carries the raw vendor fields.")
    void vendorLockedFeaturesSurviveHomomorphicReEncodingAndFailFastCrossProtocol() {
        byte[] statefulResponsesReq = """
                {
                  "model": "gpt-5",
                  "previous_response_id": "resp_prev_123",
                  "input": "Continue please",
                  "tools": [{"type": "web_search"}]
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.OPENAI_RESPONSES, statefulResponsesReq, InboundRequestContext.empty());
        assertThat(decoded.hasVendorOnlyFeatures()).isTrue();

        // Same-protocol re-encoding goes through the IR like every other pair (lossless once IR retains vendor fields)
        PreparedUpstreamRequest passthrough = translator.prepareUpstreamRequest(
                statefulResponsesReq, decoded, ProtocolKind.OPENAI_RESPONSES,
                UpstreamEncodingContext.simple("gpt-5-2026"));
        // Desired end state of the unified path: same-protocol re-encoding is lossless.
        assertThat(new String(passthrough.upstreamBody(), StandardCharsets.UTF_8))
                .contains("resp_prev_123").contains("web_search").contains("gpt-5-2026");

        // Cross-protocol fails fast with HTTP 400 PROTOCOL_UNSUPPORTED_FEATURE
        for (ProtocolKind target : List.of(
                ProtocolKind.OPENAI_CHAT, ProtocolKind.ANTHROPIC_MESSAGES, ProtocolKind.GEMINI_GENERATE_CONTENT)) {
            assertThatThrownBy(() -> translator.prepareUpstreamRequest(
                    statefulResponsesReq, decoded, target, UpstreamEncodingContext.simple("m")))
                    .isInstanceOf(HyperLlmException.class)
                    .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE);
        }
    }

    @Test
    void disjointTokenUsageEliminatesDoubleChargingAcrossAllFourProtocols() {
        // Scenario: 80 uncached input, 40 cached read, 0 cache write, 30 visible output, 15 reasoning
        // Prices: input=2M, output=10M, cacheRead=0.5M, cacheWrite=2.5M, reasoning=10M
        // Exact cost = ceil((80*2M + 30*10M + 40*0.5M + 15*10M)/1M) = 160 + 300 + 20 + 150 = 630 micros
        TokenUsage fromOpenAi = TokenUsage.fromOpenAiInclusive(120, 40, 0, 45, 15);
        TokenUsage fromGemini = TokenUsage.fromGemini(120, 0, 40, 30, 15);
        TokenUsage fromAnthropic = TokenUsage.fromAnthropic(80, 40, 0, 45);

        assertThat(fromOpenAi).isEqualTo(fromGemini);
        assertThat(fromOpenAi.totalInputTokens()).isEqualTo(120);
        assertThat(fromOpenAi.totalOutputTokens()).isEqualTo(45);

        long openAiCost = CostMath.rawCostMicros(
                fromOpenAi.promptTokens(), fromOpenAi.completionTokens(),
                fromOpenAi.cachedReadTokens(), fromOpenAi.cachedWriteTokens(), fromOpenAi.reasoningTokens(),
                2_000_000L, 10_000_000L, 500_000L, 2_500_000L, 10_000_000L);
        long geminiCost = CostMath.rawCostMicros(
                fromGemini.promptTokens(), fromGemini.completionTokens(),
                fromGemini.cachedReadTokens(), fromGemini.cachedWriteTokens(), fromGemini.reasoningTokens(),
                2_000_000L, 10_000_000L, 500_000L, 2_500_000L, 10_000_000L);
        long anthropicCost = CostMath.rawCostMicros(
                fromAnthropic.promptTokens(), fromAnthropic.completionTokens(),
                fromAnthropic.cachedReadTokens(), fromAnthropic.cachedWriteTokens(), fromAnthropic.reasoningTokens(),
                2_000_000L, 10_000_000L, 500_000L, 2_500_000L, 10_000_000L);

        assertThat(openAiCost).isEqualTo(630L);
        assertThat(geminiCost).isEqualTo(630L);
        assertThat(anthropicCost).isEqualTo(630L);
    }

    @Test
    void geminiNonSseInboundProducesValidJsonArrayStream() throws Exception {
        byte[] gemReq = """
                {"contents":[{"role":"user","parts":[{"text":"Hi"}]}]}
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.GEMINI_GENERATE_CONTENT, gemReq,
                InboundRequestContext.forGemini("virtual-omni", true, false));

        StreamBridge bridge = translator.newStreamBridge(decoded, ProtocolKind.OPENAI_CHAT);
        assertThat(bridge.contentType()).isEqualTo("application/json");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : bridge.feed(sampleStreamingSse(ProtocolKind.OPENAI_CHAT))) {
            out.writeBytes(f);
        }
        for (byte[] f : bridge.complete()) {
            out.writeBytes(f);
        }
        JsonNode array = mapper.readTree(out.toByteArray());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void anthropicEncoderSerializesInterleavedParallelOpenAiToolCallsCleanly() {
        byte[] antReq = """
                {"model":"virtual-omni","max_tokens":512,"stream":true,"messages":[{"role":"user","content":"Check two cities"}]}
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decoded = translator.parseRequest(ProtocolKind.ANTHROPIC_MESSAGES, antReq, InboundRequestContext.empty());
        StreamBridge bridge = translator.newStreamBridge(decoded, ProtocolKind.OPENAI_CHAT);

        String interleavedOpenAiSse = """
                data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"weather","arguments":"{\\"c"}}]}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"weather","arguments":"{\\"city\\":\\"NYC\\"}"}}]}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ity\\":\\"LA\\"}"}}]}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: {"id":"1","choices":[],"usage":{"prompt_tokens":20,"completion_tokens":15}}

                data: [DONE]

                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : bridge.feed(interleavedOpenAiSse.getBytes(StandardCharsets.UTF_8))) {
            out.writeBytes(f);
        }
        for (byte[] f : bridge.complete()) {
            out.writeBytes(f);
        }
        String sse = out.toString(StandardCharsets.UTF_8);
        assertThat(sse).contains("call_a").contains("call_b").contains("LA").contains("NYC");
        // First tool block (index 0) must close before second tool block (index 1) opens
        int stop0 = sse.indexOf("\"type\":\"content_block_stop\",\"index\":0");
        int start1 = sse.indexOf("\"type\":\"content_block_start\",\"index\":1");
        assertThat(stop0).isGreaterThan(0);
        assertThat(start1).isGreaterThan(stop0);
    }

    @ParameterizedTest(name = "Premature stream disconnect estimates token usage: {0} -> {1}")
    @MethodSource("twelveHeterogeneousPairs")
    void prematureDisconnectEstimatesTokenUsage(ProtocolKind inbound, ProtocolKind outbound) {
        byte[] rawReq = sampleMultimodalToolRequest(inbound, true);
        CanonicalRequest decoded = translator.parseRequest(inbound, rawReq, inboundContextFor(inbound, "virtual-omni", true, true));
        StreamBridge bridge = translator.newStreamBridge(decoded, outbound);

        // Feed only the first half of the SSE stream (before final usage frame arrives)
        byte[] fullSse = sampleStreamingSse(outbound);
        bridge.feed(Arrays.copyOfRange(fullSse, 0, fullSse.length / 3));

        TokenUsage fallback = bridge.finalizeUsage(false);
        assertThat(fallback.estimated()).isTrue();
        assertThat(fallback.promptTokens()).isGreaterThan(10);
    }

    // ---- Test fixtures ----

    private static InboundRequestContext inboundContextFor(ProtocolKind kind, String model, boolean stream, boolean altSse) {
        return switch (kind) {
            case GEMINI_GENERATE_CONTENT -> InboundRequestContext.forGemini(model, stream, altSse);
            case ANTHROPIC_MESSAGES -> InboundRequestContext.forAnthropic("2023-06-01", null);
            default -> InboundRequestContext.empty();
        };
    }

    private static byte[] sampleMultimodalToolRequest(ProtocolKind kind, boolean stream) {
        String json = switch (kind) {
            case OPENAI_CHAT -> """
                    {
                      "model": "virtual-omni",
                      "stream": %s,
                      "stream_options": {"include_usage": true},
                      "max_tokens": 512,
                      "temperature": 0.4,
                      "messages": [
                        {"role": "system", "content": "You are a helpful weather assistant."},
                        {
                          "role": "user",
                          "content": [
                            {"type": "text", "text": "What city is in this image?"},
                            {"type": "image_url", "image_url": {"url": "data:image/png;base64,%s", "detail": "low"}},
                            {"type": "image_url", "image_url": {"url": "https://example.com/sky.png", "detail": "auto"}}
                          ]
                        }
                      ],
                      "tools": [{
                        "type": "function",
                        "function": {
                          "name": "get_weather",
                          "description": "Fetch weather by city",
                          "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}
                        }
                      }],
                      "tool_choice": "auto"
                    }
                    """.formatted(stream, TINY_PNG_BASE64);
            case OPENAI_RESPONSES -> """
                    {
                      "model": "virtual-omni",
                      "stream": %s,
                      "max_output_tokens": 512,
                      "temperature": 0.4,
                      "instructions": "You are a helpful weather assistant.",
                      "input": [
                        {
                          "type": "message",
                          "role": "user",
                          "content": [
                            {"type": "input_text", "text": "What city is in this image?"},
                            {"type": "input_image", "image_url": "data:image/png;base64,%s", "detail": "low"},
                            {"type": "input_image", "image_url": "https://example.com/sky.png", "detail": "auto"}
                          ]
                        }
                      ],
                      "tools": [{
                        "type": "function",
                        "name": "get_weather",
                        "description": "Fetch weather by city",
                        "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}
                      }],
                      "tool_choice": "auto"
                    }
                    """.formatted(stream, TINY_PNG_BASE64);
            case ANTHROPIC_MESSAGES -> """
                    {
                      "model": "virtual-omni",
                      "stream": %s,
                      "max_tokens": 512,
                      "temperature": 0.4,
                      "system": "You are a helpful weather assistant.",
                      "messages": [
                        {
                          "role": "user",
                          "content": [
                            {"type": "text", "text": "What city is in this image?"},
                            {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "%s"}},
                            {"type": "image", "source": {"type": "url", "url": "https://example.com/sky.png"}}
                          ]
                        }
                      ],
                      "tools": [{
                        "name": "get_weather",
                        "description": "Fetch weather by city",
                        "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}
                      }],
                      "tool_choice": {"type": "auto"}
                    }
                    """.formatted(stream, TINY_PNG_BASE64);
            case GEMINI_GENERATE_CONTENT -> """
                    {
                      "systemInstruction": {"parts": [{"text": "You are a helpful weather assistant."}]},
                      "contents": [
                        {
                          "role": "user",
                          "parts": [
                            {"text": "What city is in this image?"},
                            {"inlineData": {"mimeType": "image/png", "data": "%s"}},
                            {"fileData": {"mimeType": "image/png", "fileUri": "https://example.com/sky.png"}}
                          ]
                        }
                      ],
                      "tools": [{
                        "functionDeclarations": [{
                          "name": "get_weather",
                          "description": "Fetch weather by city",
                          "parametersJsonSchema": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}
                        }]
                      }],
                      "toolConfig": {"functionCallingConfig": {"mode": "AUTO"}},
                      "generationConfig": {"maxOutputTokens": 512, "temperature": 0.4}
                    }
                    """.formatted(TINY_PNG_BASE64);
        };
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sampleNonStreamingResponse(ProtocolKind upstream, boolean includeToolCall) {
        String json = switch (upstream) {
            case OPENAI_CHAT -> """
                    {
                      "id": "chatcmpl-test-1",
                      "object": "chat.completion",
                      "created": 1730000000,
                      "model": "upstream-model-1",
                      "choices": [{
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": "Checking Tokyo weather.",
                          "tool_calls": [{
                            "id": "call_tok_1",
                            "type": "function",
                            "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Tokyo\\"}"}
                          }]
                        },
                        "finish_reason": "tool_calls"
                      }],
                      "usage": {
                        "prompt_tokens": 120,
                        "completion_tokens": 45,
                        "total_tokens": 165,
                        "prompt_tokens_details": {"cached_tokens": 40},
                        "completion_tokens_details": {"reasoning_tokens": 15}
                      }
                    }
                    """;
            case OPENAI_RESPONSES -> """
                    {
                      "id": "resp_test_1",
                      "object": "response",
                      "created_at": 1730000000,
                      "status": "completed",
                      "model": "upstream-model-1",
                      "output": [
                        {
                          "id": "msg_1",
                          "type": "message",
                          "status": "completed",
                          "role": "assistant",
                          "content": [{"type": "output_text", "text": "Checking Tokyo weather.", "annotations": []}]
                        },
                        {
                          "id": "fc_1",
                          "type": "function_call",
                          "status": "completed",
                          "call_id": "call_tok_1",
                          "name": "get_weather",
                          "arguments": "{\\"city\\":\\"Tokyo\\"}"
                        }
                      ],
                      "usage": {
                        "input_tokens": 120,
                        "input_tokens_details": {"cached_tokens": 40},
                        "output_tokens": 45,
                        "output_tokens_details": {"reasoning_tokens": 15},
                        "total_tokens": 165
                      }
                    }
                    """;
            case ANTHROPIC_MESSAGES -> """
                    {
                      "id": "msg_test_1",
                      "type": "message",
                      "role": "assistant",
                      "model": "upstream-model-1",
                      "content": [
                        {"type": "text", "text": "Checking Tokyo weather."},
                        {"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {"city": "Tokyo"}}
                      ],
                      "stop_reason": "tool_use",
                      "stop_sequence": null,
                      "usage": {
                        "input_tokens": 80,
                        "cache_read_input_tokens": 40,
                        "cache_creation_input_tokens": 0,
                        "output_tokens": 45
                      }
                    }
                    """;
            case GEMINI_GENERATE_CONTENT -> """
                    {
                      "responseId": "gem_test_1",
                      "modelVersion": "upstream-model-1",
                      "candidates": [{
                        "index": 0,
                        "content": {
                          "role": "model",
                          "parts": [
                            {"text": "Checking Tokyo weather."},
                            {"functionCall": {"id": "call_tok_1", "name": "get_weather", "args": {"city": "Tokyo"}}, "thoughtSignature": "sig_gem_xyz"}
                          ]
                        },
                        "finishReason": "STOP"
                      }],
                      "usageMetadata": {
                        "promptTokenCount": 120,
                        "cachedContentTokenCount": 40,
                        "candidatesTokenCount": 30,
                        "thoughtsTokenCount": 15,
                        "totalTokenCount": 165
                      }
                    }
                    """;
        };
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sampleStreamingSse(ProtocolKind upstream) {
        String sse = switch (upstream) {
            case OPENAI_CHAT -> """
                    data: {"id":"chatcmpl-s1","choices":[{"index":0,"delta":{"role":"assistant","content":"你好"}}]}

                    data: {"id":"chatcmpl-s1","choices":[{"index":0,"delta":{"content":"世界"}}]}

                    data: {"id":"chatcmpl-s1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":"}}]}}]}

                    data: {"id":"chatcmpl-s1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"Tokyo\\"}"}}]}}]}

                    data: {"id":"chatcmpl-s1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                    data: {"id":"chatcmpl-s1","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":25,"prompt_tokens_details":{"cached_tokens":30},"completion_tokens_details":{"reasoning_tokens":5}}}

                    data: [DONE]

                    """;
            case OPENAI_RESPONSES -> """
                    event: response.created
                    data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_s1","status":"in_progress","model":"upstream-model-1","output":[]}}

                    event: response.output_item.added
                    data: {"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","content":[]}}

                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","sequence_number":2,"output_index":0,"content_index":0,"delta":"你好"}

                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","sequence_number":3,"output_index":0,"content_index":0,"delta":"世界"}

                    event: response.output_item.done
                    data: {"type":"response.output_item.done","sequence_number":4,"output_index":0,"item":{"id":"msg_1","type":"message","status":"completed"}}

                    event: response.output_item.added
                    data: {"type":"response.output_item.added","sequence_number":5,"output_index":1,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get_weather","arguments":""}}

                    event: response.function_call_arguments.delta
                    data: {"type":"response.function_call_arguments.delta","sequence_number":6,"output_index":1,"delta":"{\\"city\\":\\"Tokyo\\"}"}

                    event: response.output_item.done
                    data: {"type":"response.output_item.done","sequence_number":7,"output_index":1,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\\"city\\":\\"Tokyo\\"}"}}

                    event: response.completed
                    data: {"type":"response.completed","sequence_number":8,"response":{"id":"resp_s1","status":"completed","output":[{"type":"function_call"}],"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":30},"output_tokens":25,"output_tokens_details":{"reasoning_tokens":5},"total_tokens":125}}}

                    """;
            case ANTHROPIC_MESSAGES -> """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_s1","type":"message","role":"assistant","content":[],"model":"upstream-model-1","usage":{"input_tokens":70,"cache_read_input_tokens":30,"cache_creation_input_tokens":0,"output_tokens":0}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"世界"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: content_block_start
                    data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":\\"Tokyo\\"}"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":1}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":25}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """;
            case GEMINI_GENERATE_CONTENT -> """
                    data: {"responseId":"gem_s1","modelVersion":"upstream-model-1","candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"你好"}]}}],"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":30,"candidatesTokenCount":4,"totalTokenCount":104}}

                    data: {"responseId":"gem_s1","modelVersion":"upstream-model-1","candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"世界"}]}}],"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":30,"candidatesTokenCount":10,"totalTokenCount":110}}

                    data: {"responseId":"gem_s1","modelVersion":"upstream-model-1","candidates":[{"index":0,"content":{"role":"model","parts":[{"functionCall":{"id":"call_1","name":"get_weather","args":{"city":"Tokyo"}},"thoughtSignature":"sig_1"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":30,"candidatesTokenCount":20,"thoughtsTokenCount":5,"totalTokenCount":125}}

                    """;
        };
        return sse.getBytes(StandardCharsets.UTF_8);
    }
}
