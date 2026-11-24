import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  buildProtocolCodeSnippet,
  emptyUsage,
  mergeUsage,
  parseAnthropicUsage,
  parseGeminiUsage,
  parseOpenAiChatUsage,
  parseOpenAiResponsesUsage,
  type CodeSnippetLanguage,
  type PlaygroundProtocol,
} from "../src/lib/api/sse";

describe("playground 4-protocol usage parsers", () => {
  it("normalizes OpenAI Chat inclusive usage into disjoint 5-bucket stats", () => {
    const parsed = parseOpenAiChatUsage({
      prompt_tokens: 1000,
      completion_tokens: 400,
      prompt_tokens_details: { cached_tokens: 250 },
      completion_tokens_details: { reasoning_tokens: 120 },
    });
    assert.deepEqual(parsed, {
      promptTokens: 750,
      cachedReadTokens: 250,
      completionTokens: 280,
      reasoningTokens: 120,
    });
  });

  it("normalizes OpenAI Responses inclusive usage into disjoint 5-bucket stats", () => {
    const parsed = parseOpenAiResponsesUsage({
      input_tokens: 800,
      output_tokens: 300,
      input_tokens_details: { cached_tokens: 300 },
      output_tokens_details: { reasoning_tokens: 100 },
    });
    assert.deepEqual(parsed, {
      promptTokens: 500,
      cachedReadTokens: 300,
      completionTokens: 200,
      reasoningTokens: 100,
    });
  });

  it("preserves already-disjoint Anthropic usage buckets without double subtraction", () => {
    const parsed = parseAnthropicUsage({
      input_tokens: 120,
      cache_read_input_tokens: 600,
      cache_creation_input_tokens: 200,
      output_tokens: 350,
    });
    assert.deepEqual(parsed, {
      promptTokens: 120,
      cachedReadTokens: 600,
      cachedWriteTokens: 200,
      completionTokens: 350,
    });
  });

  it("normalizes Gemini usageMetadata with disjoint prompt/cached/output/thoughts", () => {
    const parsed = parseGeminiUsage({
      promptTokenCount: 900,
      cachedContentTokenCount: 400,
      candidatesTokenCount: 220,
      thoughtsTokenCount: 180,
    });
    assert.deepEqual(parsed, {
      promptTokens: 500,
      cachedReadTokens: 400,
      completionTokens: 220,
      reasoningTokens: 180,
    });
  });

  it("merges partial usage patches without clobbering existing buckets", () => {
    const step1 = mergeUsage(emptyUsage(), {
      promptTokens: 100,
      cachedWriteTokens: 50,
    });
    const step2 = mergeUsage(step1, {
      completionTokens: 240,
      reasoningTokens: 60,
    });
    assert.deepEqual(step2, {
      promptTokens: 100,
      cachedReadTokens: 0,
      cachedWriteTokens: 50,
      completionTokens: 240,
      reasoningTokens: 60,
    });
  });
});

describe("playground 4-protocol code snippet generator", () => {
  const protocols: PlaygroundProtocol[] = [
    "OPENAI_CHAT",
    "OPENAI_RESPONSES",
    "ANTHROPIC_MESSAGES",
    "GEMINI_GENERATE_CONTENT",
  ];
  const languages: CodeSnippetLanguage[] = ["curl", "python", "typescript"];

  it("generates valid endpoint and auth header snippets for all 4 protocols x 3 languages", () => {
    for (const protocol of protocols) {
      for (const language of languages) {
        const snippet = buildProtocolCodeSnippet({
          baseUrl: "https://api.hyperllm.example///",
          protocol,
          language,
          model: "gpt-4o",
          systemPrompt: "Be concise.",
          messages: [{ role: "user", content: "Hello" }],
          temperature: 0.7,
          maxTokens: 1024,
          stream: true,
        });

        assert.ok(snippet.includes("https://api.hyperllm.example"));
        assert.ok(!snippet.includes("example///"));
        assert.ok(snippet.includes("gpt-4o"));

        if (protocol === "OPENAI_CHAT") {
          if (language === "curl") {
            assert.ok(snippet.includes("/v1/chat/completions"));
            assert.ok(snippet.includes("Authorization: Bearer"));
          } else {
            assert.ok(snippet.includes("chat.completions.create"));
          }
        } else if (protocol === "OPENAI_RESPONSES") {
          if (language === "curl") {
            assert.ok(snippet.includes("/v1/responses"));
          } else {
            assert.ok(snippet.includes("responses.create"));
          }
        } else if (protocol === "ANTHROPIC_MESSAGES") {
          if (language === "curl") {
            assert.ok(snippet.includes("/v1/messages"));
            assert.ok(snippet.includes("x-api-key"));
          } else {
            assert.ok(snippet.includes("messages.create"));
          }
        } else {
          assert.ok(
            snippet.includes("/v1beta/models/gpt-4o:streamGenerateContent?alt=sse"),
          );
          assert.ok(snippet.includes("x-goog-api-key"));
        }
      }
    }
  });
});
