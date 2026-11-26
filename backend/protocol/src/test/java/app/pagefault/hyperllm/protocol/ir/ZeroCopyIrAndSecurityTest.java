package app.pagefault.hyperllm.protocol.ir;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.io.JsonUtf8Writer;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Commit 2 zero-copy UTF-8 leaf slices, zero-DOM validated JSON objects, strict RFC 3629/8259
 * security boundaries, fused {@link RequestMetrics} token parity, and {@link ByteSink} encoding across
 * all 4x4 protocol pairs.
 */
class ZeroCopyIrAndSecurityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolTranslator translator = new ProtocolTranslator(mapper);
    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void openAiChatUsesValidatedSliceForUtf8AndEscapesAndSupportsNonZeroOffset() throws Exception {
        String json = """
                {
                  "model": "gpt-5",
                  "stream": true,
                  "messages": [
                    {"role": "system", "content": "You are helpful. \\"Quote\\" \\nLine2 中文🚀"},
                    {"role": "user", "content": "Hello 世界 👋 \\t\\\\path/to\\/file"},
                    {"role": "assistant", "content": "Sure!", "tool_calls": [
                      {"id": "call_1", "type": "function", "function": {"name": "search", "arguments": "{\\"q\\":\\"中文\\"}"}}
                    ]},
                    {"role": "tool", "tool_call_id": "call_1", "content": "result: ok"}
                  ],
                  "tools": [
                    {
                      "type": "function",
                      "function": {
                        "name": "search",
                        "description": "Search docs",
                        "parameters": {"type": "object", "properties": {"q": {"type": "string"}}, "required": ["q"]}
                      }
                    }
                  ]
                }
                """;
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);

        // Wrap payload with garbage padding before & after to test non-zero offset decoding!
        byte[] padded = new byte[payload.length + 37];
        java.util.Arrays.fill(padded, (byte) 'X');
        System.arraycopy(payload, 0, padded, 17, payload.length);

        CanonicalRequest req = translator.parseRequest(
                ProtocolKind.OPENAI_CHAT, padded, 17, payload.length, InboundRequestContext.empty());

        assertThat(req.metrics().computed()).isTrue();
        assertThat(req.systemInstructions()).hasSize(1);
        assertThat(req.systemInstructions().getFirst().utf8Text())
                .isInstanceOf(JsonUtf8Text.ValidatedJsonSlice.class);
        assertThat(req.systemInstructions().getFirst().text())
                .isEqualTo("You are helpful. \"Quote\" \nLine2 中文🚀");

        CanonicalContentPart.TextPart userText = (CanonicalContentPart.TextPart) req.turns().get(0).parts().getFirst();
        assertThat(userText.utf8Text()).isInstanceOf(JsonUtf8Text.ValidatedJsonSlice.class);
        assertThat(userText.text()).isEqualTo("Hello 世界 👋 \t\\path/to/file");

        CanonicalContentPart.ToolCallPart tc = (CanonicalContentPart.ToolCallPart) req.turns().get(1).parts().get(1);
        assertThat(tc.rawArgumentsUtf8()).isInstanceOf(JsonUtf8Text.ValidatedJsonSlice.class);
        assertThat(tc.rawArgumentsJson()).isEqualTo("{\"q\":\"中文\"}");

        assertThat(req.tools()).hasSize(1);
        assertThat(req.tools().getFirst().validatedSchema()).isNotNull();
        assertThat(req.tools().getFirst().validatedSchema().isSliceBacked()).isTrue();
        assertThat(req.tools().getFirst().parametersSchema().path("properties").path("q").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    void unicodeEscapesFallBackToMaterializedStringWithExactDecoding() {
        String json = """
                {"model":"gpt-5","messages":[{"role":"user","content":"Hi \\u4e2d\\u6587 \\uD83D\\uDE00"}]}
                """;
        CanonicalRequest req = translator.parseRequest(
                ProtocolKind.OPENAI_CHAT, json.getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty());

        CanonicalContentPart.TextPart userText = (CanonicalContentPart.TextPart) req.turns().getFirst().parts().getFirst();
        assertThat(userText.utf8Text()).isInstanceOf(JsonUtf8Text.MaterializedString.class);
        assertThat(userText.text()).isEqualTo("Hi 中文 😀");
    }

    @Test
    void rejectsMalformedUtf8AndRawControlCharsInsideJsonStrings() {
        // 1. Raw unescaped newline byte (0x0A) inside JSON string
        byte[] rawNewline = "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\nworld\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> translator.parseRequest(ProtocolKind.OPENAI_CHAT, rawNewline, InboundRequestContext.empty()))
                .isInstanceOf(HyperLlmException.class)
                .extracting(e -> ((HyperLlmException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);

        // 2. Overlong UTF-8 sequence (0xC0 0xAF) must NEVER produce a raw ValidatedJsonSlice
        byte[] prefix = "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}]}".getBytes(StandardCharsets.UTF_8);
        byte[] overlong = concat(prefix, new byte[]{(byte) 0xC0, (byte) 0xAF}, suffix);
        assertThat(JsonUtf8Text.scanValidatedSlice(overlong, prefix.length, overlong.length)).isNull();
        // If Jackson permissively decodes overlong bytes, it must fall back to MaterializedString (never raw slice)
        try {
            CanonicalRequest req = translator.parseRequest(ProtocolKind.OPENAI_CHAT, overlong, InboundRequestContext.empty());
            CanonicalContentPart.TextPart tp = (CanonicalContentPart.TextPart) req.turns().getFirst().parts().getFirst();
            assertThat(tp.utf8Text()).isInstanceOf(JsonUtf8Text.MaterializedString.class);
        } catch (HyperLlmException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);
        }

        // 3. UTF-8 encoded surrogate U+D800 (0xED 0xA0 0x80) inside JSON string
        byte[] surrogate = concat(prefix, new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, suffix);
        assertThat(JsonUtf8Text.scanValidatedSlice(surrogate, prefix.length, surrogate.length)).isNull();

        // 4. Truncated 3-byte UTF-8 sequence inside JSON string
        byte[] truncated = concat(prefix, new byte[]{(byte) 0xE4, (byte) 0xB8}, suffix);
        assertThatThrownBy(() -> translator.parseRequest(ProtocolKind.OPENAI_CHAT, truncated, InboundRequestContext.empty()))
                .isInstanceOf(HyperLlmException.class)
                .extracting(e -> ((HyperLlmException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);

        // 5. Invalid escape \x inside JSON string
        byte[] badEscape = "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"bad\\x20esc\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> translator.parseRequest(ProtocolKind.OPENAI_CHAT, badEscape, InboundRequestContext.empty()))
                .isInstanceOf(HyperLlmException.class)
                .extracting(e -> ((HyperLlmException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);
    }

    @Test
    void fusedRequestMetricsMatchUnfusedTokenEstimationAcrossAllFourProtocols() {
        List<ProtocolFixture> fixtures = List.of(
                new ProtocolFixture(
                        ProtocolKind.OPENAI_CHAT,
                        InboundRequestContext.empty(),
                        """
                        {"model":"gpt-5","messages":[
                          {"role":"system","content":"System prompt 中文 🚀"},
                          {"role":"user","content":"Analyze \\"file.java\\"\\nwith details"},
                          {"role":"assistant","content":"Calling tool","tool_calls":[{"id":"c1","type":"function","function":{"name":"read","arguments":"{\\"path\\":\\"a.java\\"}"}}]},
                          {"role":"tool","tool_call_id":"c1","content":"class A {}"}
                        ],"tools":[{"type":"function","function":{"name":"read","description":"Read file","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}}]}
                        """
                ),
                new ProtocolFixture(
                        ProtocolKind.ANTHROPIC_MESSAGES,
                        InboundRequestContext.empty(),
                        """
                        {"model":"claude-sonnet-4-6","max_tokens":1024,"system":[{"type":"text","text":"Be concise 中文"}],
                         "messages":[
                           {"role":"user","content":[{"type":"text","text":"Check status 🚀"}]},
                           {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"status","input":{"env":"prod"}}]},
                           {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"ok"}]}
                         ],"tools":[{"name":"status","description":"Check","input_schema":{"type":"object","properties":{"env":{"type":"string"}}}}]}
                        """
                ),
                new ProtocolFixture(
                        ProtocolKind.OPENAI_RESPONSES,
                        InboundRequestContext.empty(),
                        """
                        {"model":"gpt-5","instructions":"Be accurate 中文",
                         "input":[
                           {"type":"message","role":"user","content":[{"type":"input_text","text":"Hello 🚀"}]},
                           {"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{\\"k\\":\\"v\\"}"},
                           {"type":"function_call_output","call_id":"call_1","output":"found"}
                         ],"tools":[{"type":"function","name":"lookup","description":"Lookup","parameters":{"type":"object","properties":{"k":{"type":"string"}}}}]}
                        """
                ),
                new ProtocolFixture(
                        ProtocolKind.GEMINI_GENERATE_CONTENT,
                        new InboundRequestContext("gemini-2.5-pro", false, true, null, null),
                        """
                        {"systemInstruction":{"parts":[{"text":"System 中文"}]},
                         "contents":[
                           {"role":"user","parts":[{"text":"Run check 🚀"}]},
                           {"role":"model","parts":[{"functionCall":{"id":"call_1","name":"check","args":{"target":"db"}}}]},
                           {"role":"user","parts":[{"functionResponse":{"id":"call_1","name":"check","response":{"output":"healthy"}}}]}
                         ],"tools":[{"functionDeclarations":[{"name":"check","description":"Check target","parametersJsonSchema":{"type":"object","properties":{"target":{"type":"string"}}}}]}]}
                        """
                )
        );

        for (ProtocolFixture f : fixtures) {
            byte[] bytes = f.json().getBytes(StandardCharsets.UTF_8);
            CanonicalRequest sliceReq = translator.parseRequest(f.kind(), bytes, f.ctx());
            CanonicalRequest streamReq = translator.parseRequest(f.kind(), new ByteArrayInputStream(bytes), f.ctx());

            assertThat(sliceReq.metrics().computed()).isTrue();
            assertThat(streamReq.metrics().computed()).isTrue();

            // Rebuild an unfused CanonicalRequest with materialized strings / DOM to verify exact parity.
            CanonicalRequest unfused = new CanonicalRequest(
                    sliceReq.inboundProtocol(),
                    sliceReq.model(),
                    sliceReq.stream(),
                    sliceReq.systemInstructions().stream()
                            .map(sp -> new CanonicalSystemPart(sp.text(), sp.developerPriority(), sp.cacheControl()))
                            .toList(),
                    sliceReq.turns(),
                    sliceReq.tools().stream()
                            .map(t -> new CanonicalToolDefinition(t.name(), t.description(), t.parametersSchema(), t.strict(), t.cacheControl()))
                            .toList(),
                    sliceReq.toolChoice(),
                    sliceReq.generation(),
                    sliceReq.responseFormat(),
                    sliceReq.thinking(),
                    sliceReq.cache(),
                    sliceReq.options(),
                    sliceReq.vendorOnlyFeatures()
            );

            assertThat(sliceReq.metrics().estimatedPromptTokens())
                    .as("Fused token parity for %s", f.kind())
                    .isEqualTo(estimator.estimatePromptTokens(unfused));
            assertThat(streamReq.metrics().estimatedPromptTokens())
                    .as("Stream decode token parity for %s", f.kind())
                    .isEqualTo(sliceReq.metrics().estimatedPromptTokens());

            // Encode to ALL 4 outbound protocols via ByteSink and verify valid JSON parity
            for (ProtocolKind outbound : ProtocolKind.values()) {
                UpstreamEncodingContext encCtx = new UpstreamEncodingContext(
                        "upstream-model", null, TranslationPolicy.BEST_EFFORT, null, null);
                ByteSink.HeapByteSink sink = new ByteSink.HeapByteSink(1024);
                translator.encodeUpstreamRequest(sliceReq, outbound, encCtx, sink);
                PreparedUpstreamPayload payload = translator.prepareUpstreamPayload(sliceReq, outbound, encCtx);

                JsonNode fromSink = normalizeGeneratedIds(readTree(sink.toExactByteArray()));
                JsonNode fromPayload = normalizeGeneratedIds(readTree(payload.toByteArray()));
                assertThat(fromSink)
                        .as("ByteSink vs payload parity %s -> %s", f.kind(), outbound)
                        .isEqualTo(fromPayload);
            }
        }
    }

    @Test
    void jsonUtf8WriterMatchesJacksonEscapingForAllEdgeCases() throws Exception {
        String tricky = "plain \"quotes\" \\backslash\\ \b\f\n\r\t \u0000\u0001\u001F café 中文 🚀😀";
        ByteSink.HeapByteSink sink = new ByteSink.HeapByteSink(64);
        JsonUtf8Writer.writeJsonString(tricky, sink);
        String actualJson = new String(sink.toExactByteArray(), StandardCharsets.UTF_8);

        assertThat(mapper.readTree(actualJson).asText()).isEqualTo(tricky);
        assertThat(actualJson).isEqualTo(mapper.writeValueAsString(tricky));
    }

    private static JsonNode normalizeGeneratedIds(JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            JsonNode id = obj.get("id");
            if (id != null && id.isTextual() && id.asText().startsWith("fc_")) {
                obj.put("id", "fc_NORMALIZED");
            }
            obj.elements().forEachRemaining(ZeroCopyIrAndSecurityTest::normalizeGeneratedIds);
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode arr) {
            arr.forEach(ZeroCopyIrAndSecurityTest::normalizeGeneratedIds);
        }
        return node;
    }

    private JsonNode readTree(byte[] bytes) {
        try {
            return mapper.readTree(bytes);
        } catch (Exception ex) {
            throw new AssertionError("Invalid JSON: " + new String(bytes, StandardCharsets.UTF_8), ex);
        }
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    private record ProtocolFixture(ProtocolKind kind, InboundRequestContext ctx, String json) {
    }
}
