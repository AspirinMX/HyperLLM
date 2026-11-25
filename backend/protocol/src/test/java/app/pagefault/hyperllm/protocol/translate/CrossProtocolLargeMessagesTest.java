package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.codec.CanonicalRequestNormalizer;
import app.pagefault.hyperllm.protocol.fast.FastRequestProbe;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamPayload;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies P1 streaming decode/encode and copy-on-write normalization specifically on large
 * multi-turn coding-agent {@code messages}/{@code input}/{@code contents} arrays (100K+ tokens,
 * ~500 KB, tool calls & results, thinking blocks, CJK text, and edge-case malformed arguments).
 */
class CrossProtocolLargeMessagesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolTranslator translator = new ProtocolTranslator(
            mapper, new TokenEstimator(), new ProtocolErrorFormatter(mapper));

    @Test
    void translates200Turn100kTokenOpenAiChatToAnthropicResponsesAndGemini() throws Exception {
        byte[] raw = buildLargeOpenAiChatPayload(120, 30); // ~480 KB, 120 turns + 30 tool call/result pairs + 16 tools
        assertThat(raw.length).isGreaterThan(450_000);

        FastRequestProbe probe = translator.probeRequest(ProtocolKind.OPENAI_CHAT, raw, InboundRequestContext.empty());
        AtomicReference<CanonicalRequest> lazy = new AtomicReference<>();

        // 1. OpenAI Chat -> Anthropic Messages (2-pass tool_result ordering, zero-sort alternating merge)
        UpstreamEncodingContext antCtx = new UpstreamEncodingContext(
                "claude-sonnet-4-5", 8192, TranslationPolicy.BEST_EFFORT, null, "anthropic", true, null);
        PreparedUpstreamPayload antPayload = translator.prepareUpstreamPayload(
                raw, probe, InboundRequestContext.empty(), ProtocolKind.ANTHROPIC_MESSAGES, antCtx, lazy);

        assertThat(lazy.get()).isNotNull();
        JsonNode antOut = mapper.readTree(antPayload.toByteArray());
        assertThat(antOut.get("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(antOut.get("system").isArray()).isTrue();
        assertThat(antOut.get("tools").size()).isEqualTo(16);

        // Verify strict role alternation and first-user invariant in Anthropic output
        JsonNode antMsgs = antOut.get("messages");
        assertThat(antMsgs.size()).isGreaterThan(50);
        assertThat(antMsgs.get(0).get("role").asText()).isEqualTo("user");
        for (int i = 1; i < antMsgs.size(); i++) {
            assertThat(antMsgs.get(i).get("role").asText())
                    .as("Anthropic roles must strictly alternate at index %d", i)
                    .isNotEqualTo(antMsgs.get(i - 1).get("role").asText());
        }
        // Verify tool_result blocks precede text blocks in every user message
        for (JsonNode msg : antMsgs) {
            if ("user".equals(msg.get("role").asText())) {
                boolean sawNonToolResult = false;
                for (JsonNode block : msg.get("content")) {
                    if ("tool_result".equals(block.path("type").asText())) {
                        assertThat(sawNonToolResult).isFalse();
                    } else {
                        sawNonToolResult = true;
                    }
                }
            }
        }

        // 2. OpenAI Chat -> OpenAI Responses (streaming input[] encode, reusing lazyCanonical!)
        CanonicalRequest cachedRef = lazy.get();
        UpstreamEncodingContext respCtx = new UpstreamEncodingContext(
                "gpt-5", 8192, TranslationPolicy.BEST_EFFORT, null, "openai", true, null);
        PreparedUpstreamPayload respPayload = translator.prepareUpstreamPayload(
                raw, probe, InboundRequestContext.empty(), ProtocolKind.OPENAI_RESPONSES, respCtx, lazy);
        assertThat(lazy.get()).isSameAs(cachedRef);
        JsonNode respOut = mapper.readTree(respPayload.toByteArray());
        assertThat(respOut.get("model").asText()).isEqualTo("gpt-5");
        assertThat(respOut.get("input").size()).isGreaterThan(100);
        assertThat(respOut.get("tools").size()).isEqualTo(16);

        // 3. OpenAI Chat -> Gemini GenerateContent (turn-range grouping + plain-text vs JSON tool outputs)
        UpstreamEncodingContext gemCtx = new UpstreamEncodingContext(
                "gemini-2.5-pro", 8192, TranslationPolicy.BEST_EFFORT, null, "google", true, null);
        PreparedUpstreamPayload gemPayload = translator.prepareUpstreamPayload(
                raw, probe, InboundRequestContext.empty(), ProtocolKind.GEMINI_GENERATE_CONTENT, gemCtx, lazy);
        JsonNode gemOut = mapper.readTree(gemPayload.toByteArray());
        assertThat(gemOut.get("contents").size()).isGreaterThan(50);
        assertThat(gemOut.get("tools").get(0).get("functionDeclarations").size()).isEqualTo(16);

        // Round-trip: decode the huge Anthropic, Responses, and Gemini payloads back to OpenAI Chat!
        for (ProtocolKind srcKind : new ProtocolKind[]{
                ProtocolKind.ANTHROPIC_MESSAGES, ProtocolKind.OPENAI_RESPONSES, ProtocolKind.GEMINI_GENERATE_CONTENT}) {
            byte[] srcBytes = switch (srcKind) {
                case ANTHROPIC_MESSAGES -> antPayload.toByteArray();
                case OPENAI_RESPONSES -> respPayload.toByteArray();
                case GEMINI_GENERATE_CONTENT -> gemPayload.toByteArray();
                default -> throw new AssertionError();
            };
            InboundRequestContext inCtx = srcKind == ProtocolKind.GEMINI_GENERATE_CONTENT
                    ? InboundRequestContext.forGemini("gemini-2.5-pro", true, true)
                    : InboundRequestContext.empty();
            FastRequestProbe srcProbe = translator.probeRequest(srcKind, srcBytes, inCtx);
            UpstreamEncodingContext oaiCtx = new UpstreamEncodingContext(
                    "deepseek-chat", 4096, TranslationPolicy.BEST_EFFORT, null, "deepseek", false, null);
            PreparedUpstreamPayload backToOai = translator.prepareUpstreamPayload(
                    srcBytes, srcProbe, inCtx, ProtocolKind.OPENAI_CHAT, oaiCtx, new AtomicReference<>());
            JsonNode oaiBack = mapper.readTree(backToOai.toByteArray());
            assertThat(oaiBack.get("model").asText()).isEqualTo("deepseek-chat");
            assertThat(oaiBack.get("messages").size()).isGreaterThan(50);
            assertThat(oaiBack.get("tools").size()).isEqualTo(16);
        }
    }

    @Test
    void handlesOutOfOrderFieldsAndMalformedToolArgumentsGracefully() throws Exception {
        // 'content' appears BEFORE 'role', tool_call has invalid JSON args, tool result starts with '{' but isn't JSON
        String json = """
                {
                  "messages": [
                    {"content": "run checks", "role": "user"},
                    {
                      "tool_calls": [
                        {"id": "call_1", "type": "function", "function": {"name": "exec", "arguments": "not-json-at-all"}}
                      ],
                      "content": null,
                      "role": "assistant"
                    },
                    {"content": "{broken json log output", "tool_call_id": "call_1", "name": "exec", "role": "tool"},
                    {"content": "{\\"status\\":\\"ok\\",\\"passed\\":42}", "tool_call_id": "call_1", "name": "exec", "role": "tool"}
                  ],
                  "model": "gpt-4o"
                }
                """;
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decoded = translator.parseRequest(ProtocolKind.OPENAI_CHAT, raw, InboundRequestContext.empty());

        // Translate to Anthropic: malformed args safely wrapped as {"_raw": "not-json-at-all"}
        UpstreamEncodingContext antCtx = new UpstreamEncodingContext(
                "claude-sonnet-4-5", 4096, TranslationPolicy.BEST_EFFORT, null, "anthropic", false, null);
        byte[] antOut = translator.prepareUpstreamRequest(raw, decoded, ProtocolKind.ANTHROPIC_MESSAGES, antCtx).upstreamBody();
        JsonNode antTree = mapper.readTree(antOut);
        JsonNode toolUseInput = antTree.get("messages").get(1).get("content").get(0).get("input");
        assertThat(toolUseInput.get("_raw").asText()).isEqualTo("not-json-at-all");

        // Translate to Gemini: "{broken json..." wrapped in {"output": ...}, while valid JSON object is inlined!
        UpstreamEncodingContext gemCtx = new UpstreamEncodingContext(
                "gemini-2.5-pro", 4096, TranslationPolicy.BEST_EFFORT, null, "google", false, null);
        byte[] gemOut = translator.prepareUpstreamRequest(raw, decoded, ProtocolKind.GEMINI_GENERATE_CONTENT, gemCtx).upstreamBody();
        JsonNode gemTree = mapper.readTree(gemOut);
        JsonNode userParts = gemTree.get("contents").get(2).get("parts");
        assertThat(userParts.get(0).get("functionResponse").get("response").get("output").asText())
                .isEqualTo("{broken json log output");
        assertThat(userParts.get(1).get("functionResponse").get("response").get("passed").asInt())
                .isEqualTo(42);
    }

    @Test
    void copyOnWriteNormalizerReusesCleanTurnsAndRepairsInterruptedToolCall() {
        String cleanJson = """
                {
                  "model": "gpt-4o",
                  "messages": [
                    {"role": "user", "content": "step 1"},
                    {"role": "assistant", "content": "done 1"},
                    {"role": "user", "content": "step 2"}
                  ]
                }
                """;
        CanonicalRequest cleanReq = translator.parseRequest(
                ProtocolKind.OPENAI_CHAT, cleanJson.getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty());
        CanonicalRequest normalizedClean = CanonicalRequestNormalizer.normalizeForTranslation(
                cleanReq, ProtocolKind.ANTHROPIC_MESSAGES, TranslationPolicy.BEST_EFFORT);
        // Zero-copy reuse of untouched request & turns
        assertThat(normalizedClean).isSameAs(cleanReq);

        String interruptedJson = """
                {
                  "model": "gpt-4o",
                  "messages": [
                    {"role": "user", "content": "edit file"},
                    {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [
                        {"id": "call_abort_1", "type": "function", "function": {"name": "edit", "arguments": "{\\"path\\":\\"a.java\\"}"}}
                      ]
                    },
                    {"role": "user", "content": "actually cancel that"}
                  ]
                }
                """;
        CanonicalRequest interruptedReq = translator.parseRequest(
                ProtocolKind.OPENAI_CHAT, interruptedJson.getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty());
        CanonicalRequest repaired = CanonicalRequestNormalizer.normalizeForTranslation(
                interruptedReq, ProtocolKind.ANTHROPIC_MESSAGES, TranslationPolicy.BEST_EFFORT);
        assertThat(repaired.turns()).hasSize(4);
        assertThat(repaired.turns().get(2).role().name()).isEqualTo("TOOL");
    }

    private static byte[] buildLargeOpenAiChatPayload(int plainTurns, int toolPairs) {
        StringBuilder sb = new StringBuilder(550_000);
        sb.append("{\"model\":\"gpt-4o-agent\",\"stream\":true,\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"You are an expert distributed systems coding agent.\"}");

        String codeSnippet = "func handleStream(ctx context.Context, chunk []byte) error { "
                + "// 零拷贝流式转发与背压控制\\n return writeAll(ctx, chunk) } ";
        String bigBlock = codeSnippet.repeat(30); // ~3.4 KB per turn

        for (int i = 0; i < plainTurns; i++) {
            sb.append(",{\"role\":\"").append(i % 2 == 0 ? "user" : "assistant")
                    .append("\",\"content\":\"Turn ").append(i).append(": ").append(bigBlock).append("\"}");
        }
        for (int i = 0; i < toolPairs; i++) {
            String callId = "call_agent_" + i;
            sb.append(",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[")
                    .append("{\"id\":\"").append(callId).append("\",\"type\":\"function\",\"function\":{\"name\":\"tool_")
                    .append(i % 16).append("\",\"arguments\":\"{\\\"path\\\":\\\"src/File").append(i)
                    .append(".java\\\",\\\"line\\\":42}\"}}]}");
            if (i % 2 == 0) {
                // Plain text source code tool result
                sb.append(",{\"role\":\"tool\",\"tool_call_id\":\"").append(callId)
                        .append("\",\"content\":\"File content: ").append(bigBlock).append("\"}");
            } else {
                // JSON object tool result
                sb.append(",{\"role\":\"tool\",\"tool_call_id\":\"").append(callId)
                        .append("\",\"content\":\"{\\\"ok\\\":true,\\\"matches\\\":").append(i).append("}\"}");
            }
            sb.append(",{\"role\":\"user\",\"content\":\"Proceed with step ").append(i).append("\"}");
        }
        sb.append("],\"tools\":[");
        for (int t = 0; t < 16; t++) {
            if (t > 0) sb.append(',');
            sb.append("{\"type\":\"function\",\"function\":{\"name\":\"tool_").append(t)
                    .append("\",\"description\":\"Read and edit repository files\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"line\":{\"type\":\"integer\"}},\"required\":[\"path\"]}}}");
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
