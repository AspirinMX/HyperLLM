package app.pagefault.hyperllm.protocol.fast;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.ByteSlice;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamPayload;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictJsonRequestScannerAndPatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenEstimator estimator = new TokenEstimator();
    private final ProtocolTranslator translator = new ProtocolTranslator(mapper, estimator,
            new app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter(mapper));

    // ---- 1. Strict RFC 8259 differential validation (all malformed inputs rejected with 400) ----

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   \n\t  ",
            "[]",
            "\"hello\"",
            "123",
            "{",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},]}",
            "{model:\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
            "{\"model\":\"gpt-4o\" \"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"unterminated}]}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"bad\nnewline\"}]}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"bad\tctrl\"}]}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"bad\\qescape\"}]}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"bad\\u123G\"}]}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"temperature\":01}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"temperature\":1.}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"temperature\":1e+}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"temperature\":NaN}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":tru}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":fals}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"user\":nul}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]} trailing",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}" + "}",
            "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}}]}"
    })
    void rejectsMalformedJsonMatchingJackson(String malformed) {
        byte[] raw = malformed.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrictJsonRequestScanner.scan(ProtocolKind.OPENAI_CHAT, raw, InboundRequestContext.empty()))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);
    }

    @Test
    void rejectsInvalidUtf8ByteSequencesAndExcessNestingDepth() {
        byte[] prefix = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}]}".getBytes(StandardCharsets.UTF_8);

        byte[][] badUtf8 = new byte[][]{
                new byte[]{(byte) 0x80},                                      // lone continuation
                new byte[]{(byte) 0xC0, (byte) 0xAF},                         // overlong 2-byte '/'
                new byte[]{(byte) 0xE0, (byte) 0x80, (byte) 0xAF},            // overlong 3-byte
                new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80},            // UTF-16 high surrogate in UTF-8
                new byte[]{(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80} // > U+10FFFF
        };
        for (byte[] bad : badUtf8) {
            byte[] full = new byte[prefix.length + bad.length + suffix.length];
            System.arraycopy(prefix, 0, full, 0, prefix.length);
            System.arraycopy(bad, 0, full, prefix.length, bad.length);
            System.arraycopy(suffix, 0, full, prefix.length + bad.length, suffix.length);
            assertThatThrownBy(() -> StrictJsonRequestScanner.scan(ProtocolKind.OPENAI_CHAT, full, InboundRequestContext.empty()))
                    .isInstanceOf(HyperLlmException.class)
                    .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);
        }

        // Excess nesting depth (>256)
        StringBuilder deep = new StringBuilder("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}],\"extra\":");
        deep.append("[".repeat(300)).append("]".repeat(300)).append("}");
        assertThatThrownBy(() -> StrictJsonRequestScanner.scan(
                ProtocolKind.OPENAI_CHAT, deep.toString().getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty()))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);
    }

    // ---- 2. Duplicate-key smuggling protection & escape edge cases ----

    @Test
    void rejectsDuplicateTopLevelCriticalKeysIncludingUnicodeEscapes() {
        String dupModel = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"model\":\"gpt-4o\"}";
        String escapedDupModel = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"\\u006dodel\":\"gpt-4o\"}";
        String dupStream = "{\"model\":\"gpt-4o\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
        String dupIncludeUsage = "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":false,\"include_usage\":true},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        for (String payload : new String[]{dupModel, escapedDupModel, dupStream, dupIncludeUsage}) {
            assertThatThrownBy(() -> StrictJsonRequestScanner.scan(
                    ProtocolKind.OPENAI_CHAT, payload.getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty()))
                    .isInstanceOf(HyperLlmException.class)
                    .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST);
        }
    }

    @Test
    void handlesArbitraryKeyOrderNestedModelFieldAndBackslashParity() {
        // "model" appears AFTER messages, and messages contains nested "model" + even/odd backslash endings
        String json = """
                {
                  "messages": [
                    {"role": "user", "content": "path C:\\\\dir\\\\ and quote \\"{\\"model\\":\\"fake\\"}\\""},
                    {"role": "assistant", "content": "ok"}
                  ],
                  "stream": true,
                  "max_completion_tokens": 4096,
                  "model": "real-model-v1"
                }
                """;
        FastRequestProbe probe = StrictJsonRequestScanner.scan(
                ProtocolKind.OPENAI_CHAT, json.getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty());
        assertThat(probe.model()).isEqualTo("real-model-v1");
        assertThat(probe.stream()).isTrue();
        assertThat(probe.maxOutputTokens()).isEqualTo(4096);
        assertThat(probe.clientRequestedUsage()).isFalse();
        assertThat(probe.requiresSlowPath()).isFalse();
    }

    // ---- 3. Protocol-specific validation parity ----

    @Test
    void matchesProtocolValidationRulesForAnthropicResponsesAndGemini() {
        // Anthropic: max_tokens must be positive integer if non-null; null is allowed
        byte[] badMaxTokens = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":0,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrictJsonRequestScanner.scan(
                ProtocolKind.ANTHROPIC_MESSAGES, badMaxTokens, InboundRequestContext.empty()))
                .isInstanceOf(HyperLlmException.class);

        byte[] badRole = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":100,\"messages\":[{\"role\":\"tool\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrictJsonRequestScanner.scan(
                ProtocolKind.ANTHROPIC_MESSAGES, badRole, InboundRequestContext.empty()))
                .isInstanceOf(HyperLlmException.class);

        // Anthropic: role=system inside messages triggers slow-path normalization
        byte[] sysInMessages = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":100,\"messages\":[{\"role\":\"system\",\"content\":\"be concise\"},{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        FastRequestProbe antProbe = StrictJsonRequestScanner.scan(
                ProtocolKind.ANTHROPIC_MESSAGES, sysInMessages, InboundRequestContext.empty());
        assertThat(antProbe.requiresSlowPath()).isTrue();

        UpstreamEncodingContext antCtx = new UpstreamEncodingContext(
                "claude-sonnet-4-5", 8192, TranslationPolicy.BEST_EFFORT, null, "anthropic", true, null);
        AtomicReference<CanonicalRequest> lazy = new AtomicReference<>();
        PreparedUpstreamPayload normalized = translator.prepareUpstreamPayload(
                sysInMessages, antProbe, InboundRequestContext.empty(), ProtocolKind.ANTHROPIC_MESSAGES, antCtx, lazy);
        assertThat(lazy.get()).isNotNull();
        assertThat(new String(normalized.toByteArray(), StandardCharsets.UTF_8)).contains("\"system\"");

        // OpenAI Responses: null input rejected
        byte[] nullInput = "{\"model\":\"gpt-4.1\",\"input\":null}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrictJsonRequestScanner.scan(
                ProtocolKind.OPENAI_RESPONSES, nullInput, InboundRequestContext.empty()))
                .isInstanceOf(HyperLlmException.class);

        // Gemini: model & stream from URL path, body zero-copy passthrough
        byte[] geminiBody = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}],\"generationConfig\":{\"maxOutputTokens\":2048}}"
                .getBytes(StandardCharsets.UTF_8);
        InboundRequestContext gemCtx = InboundRequestContext.forGemini("gemini-2.5-pro", true, true);
        FastRequestProbe gemProbe = StrictJsonRequestScanner.scan(ProtocolKind.GEMINI_GENERATE_CONTENT, geminiBody, gemCtx);
        assertThat(gemProbe.model()).isEqualTo("gemini-2.5-pro");
        assertThat(gemProbe.stream()).isTrue();
        assertThat(gemProbe.maxOutputTokens()).isEqualTo(2048);

        PreparedUpstreamPayload gemPayload = PassthroughBytePatcher.patch(geminiBody, gemProbe,
                new UpstreamEncodingContext("gemini-2.5-pro-preview", 8192, TranslationPolicy.BEST_EFFORT, null, "google", true, null));
        assertThat(gemPayload.slices()).hasSize(1);
        assertThat(gemPayload.toByteArray()).isSameAs(geminiBody);
    }

    // ---- 4. Zero-copy byte patching matches Jackson DOM passthrough across all stream_options / model cases ----

    @Test
    void zeroCopyPatchingMatchesDomRewritingForAllOpenAiChatCases() throws Exception {
        String[] cases = new String[]{
                // 1. no stream_options, model changed
                "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                // 2. empty stream_options object
                "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{},\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                // 3. stream_options with another field
                "{\"stream_options\":{\"custom\":1},\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true,\"model\":\"gpt-4o\"}",
                // 4. stream_options with include_usage: false
                "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":false},\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                // 5. stream_options: null
                "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":null,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                // 6. stream_options already true + same model -> zero patches, exact same byte[]
                "{\"model\":\"gpt-4o-upstream\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                // 7. non-streaming + same model -> zero patches
                "{\"model\":\"gpt-4o-upstream\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"
        };

        UpstreamEncodingContext ctx = new UpstreamEncodingContext(
                "gpt-4o-upstream", 4096, TranslationPolicy.BEST_EFFORT, null, "openai", false, null);

        for (int i = 0; i < cases.length; i++) {
            byte[] raw = cases[i].getBytes(StandardCharsets.UTF_8);
            FastRequestProbe probe = translator.probeRequest(ProtocolKind.OPENAI_CHAT, raw, InboundRequestContext.empty());
            AtomicReference<CanonicalRequest> lazy = new AtomicReference<>();
            PreparedUpstreamPayload patched = translator.prepareUpstreamPayload(
                    raw, probe, InboundRequestContext.empty(), ProtocolKind.OPENAI_CHAT, ctx, lazy);

            // Fast path never decoded CanonicalRequest!
            assertThat(lazy.get()).as("case %d must stay on fast path", i).isNull();
            assertThat(patched.contentLength()).isEqualTo(patched.toByteArray().length);

            if (i >= 5) {
                assertThat(patched.slices()).hasSize(1);
                assertThat(patched.toByteArray()).isSameAs(raw);
            }

            // Semantic equivalence against legacy DOM passthrough
            CanonicalRequest decoded = translator.parseRequest(ProtocolKind.OPENAI_CHAT, raw, InboundRequestContext.empty());
            byte[] domBytes = translator.prepareUpstreamRequest(raw, decoded, ProtocolKind.OPENAI_CHAT, ctx).upstreamBody();
            JsonNode patchedTree = mapper.readTree(patched.toByteArray());
            JsonNode domTree = mapper.readTree(domBytes);
            assertThat(patchedTree).as("case %d JSON equality", i).isEqualTo(domTree);
        }
    }

    @Test
    void zeroCopyPatchingHandlesAnthropicMissingOrNullMaxTokens() throws Exception {
        UpstreamEncodingContext ctx = new UpstreamEncodingContext(
                "claude-sonnet-4-5-20250929", 6400, TranslationPolicy.BEST_EFFORT, null, "anthropic", true, null);

        String missingMax = "{\"model\":\"claude-sonnet\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String nullMax = "{\"model\":\"claude-sonnet\",\"max_tokens\":null,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        for (String src : new String[]{missingMax, nullMax}) {
            byte[] raw = src.getBytes(StandardCharsets.UTF_8);
            FastRequestProbe probe = translator.probeRequest(ProtocolKind.ANTHROPIC_MESSAGES, raw, InboundRequestContext.empty());
            AtomicReference<CanonicalRequest> lazy = new AtomicReference<>();
            PreparedUpstreamPayload patched = translator.prepareUpstreamPayload(
                    raw, probe, InboundRequestContext.empty(), ProtocolKind.ANTHROPIC_MESSAGES, ctx, lazy);

            assertThat(lazy.get()).isNull();
            JsonNode node = mapper.readTree(patched.toByteArray());
            assertThat(node.get("model").asText()).isEqualTo("claude-sonnet-4-5-20250929");
            assertThat(node.get("max_tokens").asInt()).isEqualTo(6400);
        }
    }

    // ---- 5. Base64 image screenshot protection (prevents 500K-token over-estimation!) ----

    @Test
    void excludesLargeBase64ScreenshotsFromTextTokenCount() {
        String pngBase64 = buildPaddedPngBase64(1024, 1024, 180_000); // ~240 KB base64 (~60K naive tokens, actually 765!)

        // OpenAI data URI image
        String openAiImg = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"Describe this screenshot\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + pngBase64 + "\"}}"
                + "]}]}";
        FastRequestProbe oaiProbe = StrictJsonRequestScanner.scan(
                ProtocolKind.OPENAI_CHAT, openAiImg.getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty());
        // 1024x1024 -> 2x2 tiles * 170 + 85 = 765 image tokens + small text overhead (< 1000 total, NOT 60,000!)
        assertThat(oaiProbe.estimatedPromptTokens()).isBetween(765, 1_000);

        // Anthropic base64 image block
        String antImg = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + pngBase64 + "\"}},"
                + "{\"type\":\"text\",\"text\":\"What is on screen?\"}"
                + "]}]}";
        FastRequestProbe antProbe = StrictJsonRequestScanner.scan(
                ProtocolKind.ANTHROPIC_MESSAGES, antImg.getBytes(StandardCharsets.UTF_8), InboundRequestContext.empty());
        assertThat(antProbe.estimatedPromptTokens()).isBetween(765, 1_000);
    }

    // ---- 6. 100K+ token (~480 KB) coding-agent payload zero-copy & token accuracy test ----

    @Test
    void handles100kTokenCodingAgentPayloadWithZeroCopySlicesAndAccurateEstimate() throws Exception {
        StringBuilder sb = new StringBuilder(520_000);
        sb.append("{\"model\":\"claude-code-virtual\",\"stream\":true,\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"You are an expert Rust and Java systems engineer.\"}");
        String codeBlock = "public static long computeMicroUsd(long tokens, long ratePerMillion) { "
                + "// 计费逻辑与高精度向上取整校验\\n return (tokens * ratePerMillion + 999_999L) / 1_000_000L; } ";
        for (int turn = 0; turn < 20; turn++) {
            sb.append(",{\"role\":\"").append(turn % 2 == 0 ? "user" : "assistant").append("\",\"content\":\"");
            for (int rep = 0; rep < 150; rep++) {
                sb.append(codeBlock);
            }
            sb.append("\"}");
        }
        sb.append("],\"tools\":[");
        for (int t = 0; t < 16; t++) {
            if (t > 0) sb.append(',');
            sb.append("{\"type\":\"function\",\"function\":{\"name\":\"tool_").append(t)
                    .append("\",\"description\":\"Inspect repository files and run diagnostics\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}}");
        }
        sb.append("]}");

        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(raw.length).isGreaterThan(450_000);

        FastRequestProbe probe = translator.probeRequest(ProtocolKind.OPENAI_CHAT, raw, InboundRequestContext.empty());
        CanonicalRequest canonical = translator.parseRequest(ProtocolKind.OPENAI_CHAT, raw, InboundRequestContext.empty());
        int legacyEstimate = estimator.estimatePromptTokens(canonical);

        // Fast estimate must stay within +/- 10% of the full CanonicalRequest TokenEstimator
        assertThat(probe.estimatedPromptTokens())
                .isBetween((int) (legacyEstimate * 0.90), (int) (legacyEstimate * 1.10));

        UpstreamEncodingContext ctx = new UpstreamEncodingContext(
                "gpt-4o-2025", 4096, TranslationPolicy.BEST_EFFORT, null, "openai", false, null);
        AtomicReference<CanonicalRequest> lazy = new AtomicReference<>();
        PreparedUpstreamPayload payload = translator.prepareUpstreamPayload(
                raw, probe, InboundRequestContext.empty(), ProtocolKind.OPENAI_CHAT, ctx, lazy);

        assertThat(lazy.get()).isNull();
        // Unchanged 99.9%+ bytes are referenced directly from raw without copying
        long zeroCopyBytesFromRaw = payload.slices().stream()
                .filter(s -> s.backing() == raw)
                .mapToLong(ByteSlice::length)
                .sum();
        assertThat(zeroCopyBytesFromRaw).isGreaterThan(raw.length - 40);

        JsonNode parsedOut = mapper.readTree(payload.toByteArray());
        assertThat(parsedOut.get("model").asText()).isEqualTo("gpt-4o-2025");
        assertThat(parsedOut.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(parsedOut.get("messages").size()).isEqualTo(21);
        assertThat(parsedOut.get("tools").size()).isEqualTo(16);
    }

    private static String buildPaddedPngBase64(int width, int height, int rawBytesLen) {
        byte[] png = new byte[Math.max(32, rawBytesLen)];
        byte[] sig = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, png, 0, sig.length);
        ByteBuffer.wrap(png, 16, 8).putInt(width).putInt(height);
        return Base64.getEncoder().encodeToString(png);
    }
}
