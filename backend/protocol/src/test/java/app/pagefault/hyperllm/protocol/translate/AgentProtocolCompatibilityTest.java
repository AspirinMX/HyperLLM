package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalResponse;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamRequest;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-world Coding Agent (Claude Code, OpenAI Codex CLI, OpenCode / Vercel AI SDK)
 * compatibility tests verifying capability-aware normalization across all 4 codecs.
 */
class AgentProtocolCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProtocolTranslator translator = new ProtocolTranslator(mapper);

    @Test
    void claudeCodeInlineSystemBuiltInToolsAndThinkingTranslateCleanlyToThirdPartyOpenAi() throws Exception {
        byte[] claudeCodeReq = """
                {
                  "model": "claude-sonnet-4-6",
                  "max_tokens": 4096,
                  "stream": true,
                  "temperature": 0.7,
                  "system": [{"type": "text", "text": "You are Claude Code.", "cache_control": {"type": "ephemeral"}}],
                  "thinking": {"type": "enabled", "budget_tokens": 2048},
                  "tools": [
                    {"type": "web_search_20250305", "name": "web_search"},
                    {
                      "name": "bash",
                      "description": "Run shell command",
                      "input_schema": {"type": "object", "properties": {"cmd": {"type": "string"}}, "required": ["cmd"]},
                      "strict": true
                    }
                  ],
                  "tool_choice": {"type": "auto"},
                  "messages": [
                    {"role": "system", "content": "Workspace root is /repo."},
                    {"role": "user", "content": "Check git status"},
                    {"role": "assistant", "content": [
                      {"type": "thinking", "thinking": "Run git status", "signature": "sig_ant_1"},
                      {"type": "tool_use", "id": "toolu_01ABC", "name": "bash", "input": {"cmd": "git status"}}
                    ]},
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "toolu_01ABC", "content": "clean"},
                      {"type": "tool_reference", "tool_name": "bash"}
                    ]},
                    {"role": "system", "content": "Reminder: stay concise."},
                    {"role": "user", "content": "Summarize."}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.ANTHROPIC_MESSAGES, claudeCodeReq, InboundRequestContext.forAnthropic("2023-06-01", null));

        // 1. Third-party OpenAI-compatible upstream (DeepSeek): uses role=system, max_tokens, drops reasoning_effort & strict & web_search
        PreparedUpstreamRequest toDeepSeek = translator.prepareUpstreamRequest(
                claudeCodeReq, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("deepseek-chat", 8192, TranslationPolicy.BEST_EFFORT, null, "DEEPSEEK"));
        JsonNode ds = mapper.readTree(toDeepSeek.upstreamBody());
        assertThat(ds.has("max_tokens")).isTrue();
        assertThat(ds.has("max_completion_tokens")).isFalse();
        assertThat(ds.has("reasoning_effort")).isFalse();
        assertThat(ds.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(ds.path("tools")).hasSize(1);
        assertThat(ds.path("tools").get(0).path("function").path("name").asText()).isEqualTo("bash");
        assertThat(ds.path("tools").get(0).path("function").has("strict")).isFalse();

        JsonNode msgs = ds.get("messages");
        assertThat(msgs.get(0).path("role").asText()).isEqualTo("system");
        assertThat(msgs.get(0).path("content").asText()).isEqualTo("You are Claude Code.");
        assertThat(msgs.get(1).path("role").asText()).isEqualTo("system");
        assertThat(msgs.get(1).path("content").asText()).isEqualTo("Workspace root is /repo.");
        // Mid-conversation system role preserved as system (never developer)
        boolean sawMidSystem = false;
        for (JsonNode m : msgs) {
            assertThat(m.path("role").asText()).isNotEqualTo("developer");
            if ("system".equals(m.path("role").asText()) && "Reminder: stay concise.".equals(m.path("content").asText())) {
                sawMidSystem = true;
            }
        }
        assertThat(sawMidSystem).isTrue();

        // 2. Native OpenAI thinking upstream (driven by providerCode + supportsThinking, not hardcoded model name)
        PreparedUpstreamRequest toOpenAiThinking = translator.prepareUpstreamRequest(
                claudeCodeReq, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("any-openai-reasoning-model", 8192, TranslationPolicy.BEST_EFFORT, null, "OPENAI", true, null));
        JsonNode oai = mapper.readTree(toOpenAiThinking.upstreamBody());
        assertThat(oai.has("max_completion_tokens")).isTrue();
        assertThat(oai.has("max_tokens")).isFalse();
        assertThat(oai.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(oai.has("temperature")).isFalse();
        assertThat(oai.path("tools").get(0).path("function").path("strict").asBoolean()).isTrue();
        // OpenAI native Chat API must not receive extra reasoning_content field on assistant messages
        assertThat(oai.path("messages").get(3).has("reasoning_content")).isFalse();

        // 3. STRICT mode still rejects degradable built-in server tools
        assertThatThrownBy(() -> translator.prepareUpstreamRequest(
                claudeCodeReq, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("deepseek-chat", 8192, TranslationPolicy.STRICT, null, "DEEPSEEK")))
                .isInstanceOf(HyperLlmException.class)
                .extracting(ex -> ((HyperLlmException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROTOCOL_UNSUPPORTED_FEATURE);

        // 4. Homomorphic Anthropic passthrough hoists leading role=system but keeps mid-conversation role=system
        PreparedUpstreamRequest antPass = translator.prepareUpstreamRequest(
                claudeCodeReq, decoded, ProtocolKind.ANTHROPIC_MESSAGES,
                UpstreamEncodingContext.forProvider("ANTHROPIC", "claude-sonnet-4-6"));
        JsonNode passRoot = mapper.readTree(antPass.upstreamBody());
        assertThat(passRoot.path("system")).hasSize(2);
        assertThat(passRoot.path("system").get(1).path("text").asText()).isEqualTo("Workspace root is /repo.");
        assertThat(passRoot.path("messages").get(0).path("role").asText()).isEqualTo("user");
        // The IR round trip keeps the mid-conversation system turn, but the encoder is free to place it
        // anywhere in messages[] (leading system is hoisted into system[]), so assert semantically.
        boolean midSystemKept = false;
        for (JsonNode msg : passRoot.path("messages")) {
            if ("system".equals(msg.path("role").asText())) {
                midSystemKept = true;
            }
        }
        assertThat(midSystemKept).isTrue();
    }

    @Test
    void codexResponsesRequestDegradesCleanlyToThirdPartyChatWithoutDeveloperRoleOrEncryptedContent() throws Exception {
        byte[] codexReq = """
                {
                  "model": "gpt-5-codex",
                  "store": false,
                  "instructions": "You are Codex CLI.",
                  "reasoning": {"effort": "high", "summary": "auto"},
                  "tools": [
                    {"type": "custom", "name": "apply_patch"},
                    {"type": "local_shell"},
                    {"type": "function", "name": "shell", "description": "Run command"}
                  ],
                  "tool_choice": "auto",
                  "input": [
                    {"type": "message", "role": "user", "content": [{"type": "input_text", "text": "Fix test"}]},
                    {"type": "reasoning", "summary": [{"type": "summary_text", "text": "Inspecting"}], "encrypted_content": "enc_secret_xyz"},
                    {"type": "compaction", "encrypted_content": "enc_compaction"},
                    {"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "shell", "arguments": "{\\"cmd\\":\\"ls\\"}"} ,
                    {"type": "function_call_output", "call_id": "call_1", "output": "pom.xml"}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.OPENAI_RESPONSES, codexReq, InboundRequestContext.empty());

        PreparedUpstreamRequest toVllm = translator.prepareUpstreamRequest(
                codexReq, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("qwen3-coder", 8192, TranslationPolicy.BEST_EFFORT, null, "VLLM"));
        String body = new String(toVllm.upstreamBody(), StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("enc_secret_xyz").doesNotContain("enc_compaction");

        JsonNode vllm = mapper.readTree(toVllm.upstreamBody());
        // instructions (developerPriority=true) maps to role=system for VLLM
        assertThat(vllm.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(vllm.path("messages").get(0).path("content").asText()).isEqualTo("You are Codex CLI.");
        // Missing parameters schema gets defaulted to empty JSON Schema object
        assertThat(vllm.path("tools")).hasSize(1);
        assertThat(vllm.path("tools").get(0).path("function").path("parameters").path("type").asText()).isEqualTo("object");
        assertThat(vllm.has("reasoning_effort")).isFalse();
    }

    @Test
    void emptyToolsNeverEmitToolChoiceAcrossAllFourProtocols() throws Exception {
        // Request whose only tool is an Anthropic server tool + tool_choice=auto -> tools becomes empty after normalization
        byte[] antBuiltInOnly = """
                {
                  "model": "omni",
                  "max_tokens": 512,
                  "tools": [{"type": "web_search_20250305", "name": "web_search"}],
                  "tool_choice": {"type": "tool", "name": "web_search"},
                  "messages": [{"role": "user", "content": "Latest news"}]
                }
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.ANTHROPIC_MESSAGES, antBuiltInOnly, InboundRequestContext.empty());

        for (ProtocolKind target : List.of(
                ProtocolKind.OPENAI_CHAT, ProtocolKind.OPENAI_RESPONSES, ProtocolKind.GEMINI_GENERATE_CONTENT)) {
            JsonNode out = mapper.readTree(translator.prepareUpstreamRequest(
                    antBuiltInOnly, decoded, target, UpstreamEncodingContext.simple("target-model")).upstreamBody());
            assertThat(out.has("tools")).as("target %s should omit empty tools", target).isFalse();
            assertThat(out.has("tool_choice")).as("target %s must not emit tool_choice without tools", target).isFalse();
            assertThat(out.has("toolConfig")).as("target %s must not emit toolConfig without tools", target).isFalse();
            assertThat(out.has("parallel_tool_calls")).isFalse();
        }

        // OpenAI Chat with tool_choice="none" and no tools -> Anthropic must omit tool_choice
        byte[] chatNoTools = """
                {
                  "model": "claude-sonnet-4-6",
                  "tool_choice": "none",
                  "messages": [{"role": "user", "content": "Hello"}]
                }
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decodedChat = translator.parseRequest(
                ProtocolKind.OPENAI_CHAT, chatNoTools, InboundRequestContext.empty());
        JsonNode antOut = mapper.readTree(translator.prepareUpstreamRequest(
                chatNoTools, decodedChat, ProtocolKind.ANTHROPIC_MESSAGES,
                UpstreamEncodingContext.simple("claude-sonnet-4-6")).upstreamBody());
        assertThat(antOut.has("tools")).isFalse();
        assertThat(antOut.has("tool_choice")).isFalse();
    }

    @Test
    void toolCallIdsAreBijectivelySanitizedForOpenAiLengthAndAnthropicRegex() throws Exception {
        String overlongId = "mcp.filesystem:server/read_file_0195f123456789abcdef_extra_long_suffix";
        byte[] chatReq = ("""
                {
                  "model": "claude-sonnet-4-6",
                  "messages": [
                    {"role": "user", "content": "Read file"},
                    {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [
                        {"id": "%s", "type": "function", "function": {"name": "read_file", "arguments": "{\\"path\\":\\"a.txt\\"}"}}
                      ]
                    },
                    {"role": "tool", "tool_call_id": "%s", "content": ""}
                  ]
                }
                """.formatted(overlongId, overlongId)).getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(ProtocolKind.OPENAI_CHAT, chatReq, InboundRequestContext.empty());

        // -> Anthropic: ID matches ^[a-zA-Z0-9_-]+$, <= 64 chars, identical on tool_use and tool_result, empty tool_result uses "" string
        JsonNode antOut = mapper.readTree(translator.prepareUpstreamRequest(
                chatReq, decoded, ProtocolKind.ANTHROPIC_MESSAGES,
                UpstreamEncodingContext.simple("claude-sonnet-4-6")).upstreamBody());
        String antUseId = antOut.path("messages").get(1).path("content").get(0).path("id").asText();
        String antResId = antOut.path("messages").get(2).path("content").get(0).path("tool_use_id").asText();
        assertThat(antUseId).matches("^[a-zA-Z0-9_-]{1,64}$");
        assertThat(antResId).isEqualTo(antUseId);
        // Empty tool result content is emitted as "" string rather than invalid empty text block array
        assertThat(antOut.path("messages").get(2).path("content").get(0).path("content").isTextual()).isTrue();
        assertThat(antOut.path("messages").get(2).path("content").get(0).path("content").asText()).isEmpty();

        // -> OpenAI Chat (from Anthropic/Responses): ID <= 40 chars and identical on call and result
        byte[] antOverlong = ("""
                {
                  "model": "gpt-4o",
                  "max_tokens": 256,
                  "messages": [
                    {"role": "user", "content": "Read file"},
                    {"role": "assistant", "content": [{"type": "tool_use", "id": "%s", "name": "read_file", "input": {}}]},
                    {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "%s", "content": "ok"}]}
                  ]
                }
                """.formatted(overlongId, overlongId)).getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decodedAnt = translator.parseRequest(
                ProtocolKind.ANTHROPIC_MESSAGES, antOverlong, InboundRequestContext.empty());
        JsonNode chatOut = mapper.readTree(translator.prepareUpstreamRequest(
                antOverlong, decodedAnt, ProtocolKind.OPENAI_CHAT,
                UpstreamEncodingContext.forProvider("OPENAI", "gpt-4o")).upstreamBody());
        String chatCallId = chatOut.path("messages").get(1).path("tool_calls").get(0).path("id").asText();
        String chatResId = chatOut.path("messages").get(2).path("tool_call_id").asText();
        assertThat(chatCallId.length()).isLessThanOrEqualTo(40);
        assertThat(chatResId).isEqualTo(chatCallId);
    }

    @Test
    void interruptedAgentToolCallsAreRepairedInBestEffort() throws Exception {
        byte[] interruptedChat = """
                {
                  "model": "claude-sonnet-4-6",
                  "messages": [
                    {"role": "user", "content": "Run two tools"},
                    {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [
                        {"id": "call_ok", "type": "function", "function": {"name": "read", "arguments": "{}"}},
                        {"id": "call_aborted", "type": "function", "function": {"name": "bash", "arguments": "{}"}}
                      ]
                    },
                    {"role": "tool", "tool_call_id": "call_ok", "content": "done"},
                    {"role": "user", "content": "Never mind, do something else"}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.OPENAI_CHAT, interruptedChat, InboundRequestContext.empty());
        JsonNode antOut = mapper.readTree(translator.prepareUpstreamRequest(
                interruptedChat, decoded, ProtocolKind.ANTHROPIC_MESSAGES,
                UpstreamEncodingContext.simple("claude-sonnet-4-6")).upstreamBody());

        // Merged user message immediately after assistant has BOTH tool_result blocks before the text block
        JsonNode userBlocks = antOut.path("messages").get(2).path("content");
        assertThat(userBlocks).hasSize(3);
        assertThat(userBlocks.get(0).path("type").asText()).isEqualTo("tool_result");
        assertThat(userBlocks.get(1).path("type").asText()).isEqualTo("tool_result");
        assertThat(userBlocks.get(2).path("type").asText()).isEqualTo("text");
        assertThat(List.of(
                userBlocks.get(0).path("tool_use_id").asText(),
                userBlocks.get(1).path("tool_use_id").asText()
        )).containsExactlyInAnyOrder("call_ok", "call_aborted");
    }

    @Test
    void anthropicEmptyUserTextBlocksAreStrippedWithoutBreakingAlternation() throws Exception {
        byte[] chatReq = """
                {
                  "model": "claude-sonnet-4-6",
                  "messages": [
                    {"role": "user", "content": [{"type": "text", "text": ""}, {"type": "text", "text": "Actual question"}]}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(ProtocolKind.OPENAI_CHAT, chatReq, InboundRequestContext.empty());
        JsonNode antOut = mapper.readTree(translator.prepareUpstreamRequest(
                chatReq, decoded, ProtocolKind.ANTHROPIC_MESSAGES,
                UpstreamEncodingContext.simple("claude-sonnet-4-6")).upstreamBody());
        JsonNode blocks = antOut.path("messages").get(0).path("content");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path("text").asText()).isEqualTo("Actual question");
    }

    @Test
    void openAiStreamDecoderHandlesMissingIndexParallelToolCallsAndReasoningField() {
        byte[] antReq = """
                {"model":"omni","max_tokens":512,"stream":true,"messages":[{"role":"user","content":"Check"}]}
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.ANTHROPIC_MESSAGES, antReq, InboundRequestContext.empty());
        StreamBridge bridge = translator.newStreamBridge(decoded, ProtocolKind.OPENAI_CHAT);

        // Third-party compat stream: uses "reasoning" field (not reasoning_content) AND omits/reuses index:0 on distinct tool_call IDs
        String compatSse = """
                data: {"id":"cmpl-1","choices":[{"index":0,"delta":{"role":"assistant","reasoning":"Thinking step"}}]}

                data: {"id":"cmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_first","type":"function","function":{"name":"read","arguments":"{\\"a\\":1}"}}]}}]}

                data: {"id":"cmpl-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_second","type":"function","function":{"name":"glob","arguments":"{\\"b\\":2}"}}]}}]}

                data: {"id":"cmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: {"id":"cmpl-1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":8}}

                data: [DONE]

                """;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : bridge.feed(compatSse.getBytes(StandardCharsets.UTF_8))) {
            out.writeBytes(f);
        }
        for (byte[] f : bridge.complete()) {
            out.writeBytes(f);
        }
        String emitted = out.toString(StandardCharsets.UTF_8);
        assertThat(emitted)
                .contains("\"type\":\"thinking_delta\",\"thinking\":\"Thinking step\"")
                .contains("\"id\":\"call_first\"")
                .contains("\"id\":\"call_second\"")
                .contains("{\\\"a\\\":1}")
                .contains("{\\\"b\\\":2}");
    }

    @Test
    void openAiDecodeResponseSupportsArrayContentAndReasoningFieldAndLegacyFunctionRole() throws Exception {
        byte[] arrayResp = """
                {
                  "id": "chatcmpl-arr",
                  "model": "qwen3",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "reasoning": "step 1",
                      "content": [{"type": "text", "text": "Hello"}, {"type": "text", "text": "world"}]
                    },
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5}
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalResponse resp = translator.codecFor(ProtocolKind.OPENAI_CHAT).decodeResponse(arrayResp, "qwen3");
        assertThat(resp.content()).hasSize(2);

        byte[] legacyChat = """
                {
                  "model": "claude-sonnet-4-6",
                  "messages": [
                    {"role": "user", "content": "Weather?"},
                    {"role": "assistant", "content": null, "function_call": {"name": "get_weather", "arguments": "{\\"city\\":\\"NY\\"}"}},
                    {"role": "function", "name": "get_weather", "content": "20C"}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decodedLegacy = translator.parseRequest(
                ProtocolKind.OPENAI_CHAT, legacyChat, InboundRequestContext.empty());
        JsonNode gemOut = mapper.readTree(translator.prepareUpstreamRequest(
                legacyChat, decodedLegacy, ProtocolKind.GEMINI_GENERATE_CONTENT,
                UpstreamEncodingContext.simple("gemini-2.5-flash")).upstreamBody());
        assertThat(gemOut.path("contents").get(2).path("parts").get(0)
                .path("functionResponse").path("name").asText()).isEqualTo("get_weather");
    }

    @Test
    void anthropicRequestWithoutMaxTokensDecodesForCountTokensEndpoint() {
        byte[] countTokensBody = """
                {
                  "model": "claude-sonnet-4-6",
                  "system": [{"type": "text", "text": "Be helpful"}],
                  "messages": [
                    {"role": "system", "content": "Extra context"},
                    {"role": "user", "content": "How many tokens is this?"}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);

        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.ANTHROPIC_MESSAGES, countTokensBody, InboundRequestContext.forAnthropic("2023-06-01", null));
        assertThat(decoded.maxOutputTokens()).isNull();
        assertThat(translator.tokenEstimator().estimatePromptTokens(decoded)).isGreaterThan(5);
    }

    @Test
    void arbitraryModelNameSupportsInterleavedReasoningAndMetadataOverridesWithoutHardcoding() throws Exception {
        byte[] antTurn2 = """
                {
                  "model": "acme-private-coder-v9",
                  "max_tokens": 2048,
                  "messages": [
                    {"role": "user", "content": "Inspect repo"},
                    {"role": "assistant", "content": [
                      {"type": "thinking", "thinking": "Let me list files first."},
                      {"type": "tool_use", "id": "toolu_1", "name": "ls", "input": {}}
                    ]},
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "toolu_1", "content": "README.md"}
                    ]}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.ANTHROPIC_MESSAGES, antTurn2, InboundRequestContext.empty());

        // Any custom model on any OpenAI-compat provider with supportsThinking=true gets reasoning_content echoed
        JsonNode defaultCompat = mapper.readTree(translator.prepareUpstreamRequest(
                antTurn2, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("acme-private-coder-v9", 8192, TranslationPolicy.BEST_EFFORT, null, "CUSTOM_COMPAT", true, null))
                .upstreamBody());
        assertThat(defaultCompat.path("messages").get(1).path("reasoning_content").asText())
                .isEqualTo("Let me list files first.");

        // Explicit metadata_json override switches field name to "reasoning"
        JsonNode customField = mapper.readTree(translator.prepareUpstreamRequest(
                antTurn2, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("acme-private-coder-v9", 8192, TranslationPolicy.BEST_EFFORT, null, "OPENROUTER", true,
                        "{\"interleavedReasoningField\":\"reasoning\"}"))
                .upstreamBody());
        assertThat(customField.path("messages").get(1).path("reasoning").asText())
                .isEqualTo("Let me list files first.");
        assertThat(customField.path("messages").get(1).has("reasoning_content")).isFalse();

        // Explicit metadata_json "none" disables echoing reasoning
        JsonNode disabled = mapper.readTree(translator.prepareUpstreamRequest(
                antTurn2, decoded, ProtocolKind.OPENAI_CHAT,
                new UpstreamEncodingContext("acme-private-coder-v9", 8192, TranslationPolicy.BEST_EFFORT, null, "CUSTOM_COMPAT", true,
                        "{\"interleavedReasoningField\":\"none\"}"))
                .upstreamBody());
        assertThat(disabled.path("messages").get(1).has("reasoning_content")).isFalse();

        // Decoding OpenRouter / compat reasoning_details array works without model-name checks
        byte[] detailsResp = """
                {
                  "id": "chatcmpl-rd",
                  "model": "acme-private-coder-v9",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "reasoning_details": [
                        {"type": "reasoning.text", "text": "Step 1"},
                        {"type": "reasoning.summary", "summary": "Step 2"}
                      ],
                      "content": "Done"
                    },
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 6}
                }
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalResponse resp = translator.codecFor(ProtocolKind.OPENAI_CHAT).decodeResponse(detailsResp, "acme-private-coder-v9");
        assertThat(resp.content()).hasSize(2);
        assertThat(resp.content().get(0)).isInstanceOf(app.pagefault.hyperllm.protocol.ir.CanonicalContentPart.ThinkingPart.class);
        assertThat(((app.pagefault.hyperllm.protocol.ir.CanonicalContentPart.ThinkingPart) resp.content().get(0)).text())
                .isEqualTo("Step 1\nStep 2");
    }

    @Test
    void geminiLeadingSystemContentHoistsToSystemInstruction() throws Exception {
        byte[] gemReq = """
                {
                  "contents": [
                    {"role": "system", "parts": [{"text": "Always reply in JSON"}]},
                    {"role": "user", "parts": [{"text": "Hi"}]}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
        CanonicalRequest decoded = translator.parseRequest(
                ProtocolKind.GEMINI_GENERATE_CONTENT, gemReq,
                InboundRequestContext.forGemini("gpt-4o", false, true));
        assertThat(decoded.systemInstructions()).hasSize(1);
        assertThat(decoded.systemInstructions().getFirst().text()).isEqualTo("Always reply in JSON");

        JsonNode chatOut = mapper.readTree(translator.prepareUpstreamRequest(
                gemReq, decoded, ProtocolKind.OPENAI_CHAT,
                UpstreamEncodingContext.forProvider("OPENAI", "gpt-4o")).upstreamBody());
        assertThat(chatOut.path("messages").get(0).path("role").asText()).isEqualTo("system");
    }
}
