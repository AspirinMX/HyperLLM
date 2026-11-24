import { ApiError, isRecord, parseApiError } from "./error";

export type PlaygroundProtocol =
  | "OPENAI_CHAT"
  | "OPENAI_RESPONSES"
  | "ANTHROPIC_MESSAGES"
  | "GEMINI_GENERATE_CONTENT";

export type ThinkingEffort = "off" | "low" | "medium" | "high";

export interface PlaygroundImageAttachment {
  id: string;
  name: string;
  mimeType: string;
  dataUrl: string;
  base64: string;
}

export interface PlaygroundChatMessage {
  role: "user" | "assistant";
  content: string;
  images?: PlaygroundImageAttachment[];
}

/**
 * Strictly disjoint 5-bucket token usage matching backend `TokenUsage`:
 * - promptTokens: uncached input tokens only
 * - cachedReadTokens: prompt-cache read hit tokens
 * - cachedWriteTokens: prompt-cache creation tokens
 * - completionTokens: non-reasoning output tokens only
 * - reasoningTokens: thinking / reasoning output tokens
 */
export interface StreamUsageStats {
  promptTokens: number;
  cachedReadTokens: number;
  cachedWriteTokens: number;
  completionTokens: number;
  reasoningTokens: number;
}

export interface StreamCompletionOptions {
  protocol: PlaygroundProtocol;
  sandboxToken: string;
  model: string;
  systemPrompt: string;
  messages: PlaygroundChatMessage[];
  temperature: number;
  maxTokens: number;
  thinkingEffort?: ThinkingEffort;
  stream: boolean;
  signal?: AbortSignal;
  onTextDelta: (chunk: string) => void;
  onThinkingDelta?: (chunk: string) => void;
  onTtft?: (ttftMs: number) => void;
  onUsage?: (usage: StreamUsageStats) => void;
}

export interface StreamCompletionResult {
  content: string;
  thinking: string;
  ttftMs: number | null;
  totalMs: number;
  usage: StreamUsageStats;
}

export const PLAYGROUND_PROTOCOL_META: Record<
  PlaygroundProtocol,
  { shortLabel: string; label: string; endpoint: string }
> = {
  OPENAI_CHAT: {
    shortLabel: "OpenAI Chat",
    label: "OpenAI Chat Completions",
    endpoint: "/v1/chat/completions",
  },
  OPENAI_RESPONSES: {
    shortLabel: "Responses",
    label: "OpenAI Responses API",
    endpoint: "/v1/responses",
  },
  ANTHROPIC_MESSAGES: {
    shortLabel: "Anthropic",
    label: "Anthropic Messages",
    endpoint: "/v1/messages",
  },
  GEMINI_GENERATE_CONTENT: {
    shortLabel: "Gemini",
    label: "Google Gemini generateContent",
    endpoint: "/v1beta/models/{model}:generateContent",
  },
};

const THINKING_BUDGET_TOKENS: Record<Exclude<ThinkingEffort, "off">, number> = {
  low: 1024,
  medium: 4096,
  high: 8192,
};

export function emptyUsage(): StreamUsageStats {
  return {
    promptTokens: 0,
    cachedReadTokens: 0,
    cachedWriteTokens: 0,
    completionTokens: 0,
    reasoningTokens: 0,
  };
}

export function parseOpenAiChatUsage(raw: unknown): Partial<StreamUsageStats> | null {
  if (!isRecord(raw)) return null;
  const inclusivePrompt =
    typeof raw.prompt_tokens === "number" ? raw.prompt_tokens : undefined;
  const inclusiveCompletion =
    typeof raw.completion_tokens === "number"
      ? raw.completion_tokens
      : undefined;

  let cachedReadTokens: number | undefined;
  if (
    isRecord(raw.prompt_tokens_details) &&
    typeof raw.prompt_tokens_details.cached_tokens === "number"
  ) {
    cachedReadTokens = Math.max(0, raw.prompt_tokens_details.cached_tokens);
  }

  let reasoningTokens: number | undefined;
  if (
    isRecord(raw.completion_tokens_details) &&
    typeof raw.completion_tokens_details.reasoning_tokens === "number"
  ) {
    reasoningTokens = Math.max(0, raw.completion_tokens_details.reasoning_tokens);
  }

  const promptTokens =
    inclusivePrompt !== undefined
      ? Math.max(0, inclusivePrompt - (cachedReadTokens ?? 0))
      : undefined;
  const completionTokens =
    inclusiveCompletion !== undefined
      ? Math.max(0, inclusiveCompletion - (reasoningTokens ?? 0))
      : undefined;

  return {
    promptTokens,
    cachedReadTokens,
    completionTokens,
    reasoningTokens,
  };
}

export function parseOpenAiResponsesUsage(
  raw: unknown,
): Partial<StreamUsageStats> | null {
  if (!isRecord(raw)) return null;
  const inclusiveInput =
    typeof raw.input_tokens === "number" ? raw.input_tokens : undefined;
  const inclusiveOutput =
    typeof raw.output_tokens === "number" ? raw.output_tokens : undefined;

  let cachedReadTokens: number | undefined;
  if (
    isRecord(raw.input_tokens_details) &&
    typeof raw.input_tokens_details.cached_tokens === "number"
  ) {
    cachedReadTokens = Math.max(0, raw.input_tokens_details.cached_tokens);
  }

  let reasoningTokens: number | undefined;
  if (
    isRecord(raw.output_tokens_details) &&
    typeof raw.output_tokens_details.reasoning_tokens === "number"
  ) {
    reasoningTokens = Math.max(0, raw.output_tokens_details.reasoning_tokens);
  }

  return {
    promptTokens:
      inclusiveInput !== undefined
        ? Math.max(0, inclusiveInput - (cachedReadTokens ?? 0))
        : undefined,
    cachedReadTokens,
    completionTokens:
      inclusiveOutput !== undefined
        ? Math.max(0, inclusiveOutput - (reasoningTokens ?? 0))
        : undefined,
    reasoningTokens,
  };
}

export function parseAnthropicUsage(raw: unknown): Partial<StreamUsageStats> | null {
  if (!isRecord(raw)) return null;
  return {
    promptTokens:
      typeof raw.input_tokens === "number"
        ? Math.max(0, raw.input_tokens)
        : undefined,
    cachedReadTokens:
      typeof raw.cache_read_input_tokens === "number"
        ? Math.max(0, raw.cache_read_input_tokens)
        : undefined,
    cachedWriteTokens:
      typeof raw.cache_creation_input_tokens === "number"
        ? Math.max(0, raw.cache_creation_input_tokens)
        : undefined,
    completionTokens:
      typeof raw.output_tokens === "number"
        ? Math.max(0, raw.output_tokens)
        : undefined,
  };
}

export function parseGeminiUsage(raw: unknown): Partial<StreamUsageStats> | null {
  if (!isRecord(raw)) return null;
  const inclusivePrompt =
    typeof raw.promptTokenCount === "number" ? raw.promptTokenCount : undefined;
  const cachedReadTokens =
    typeof raw.cachedContentTokenCount === "number"
      ? Math.max(0, raw.cachedContentTokenCount)
      : undefined;
  const completionTokens =
    typeof raw.candidatesTokenCount === "number"
      ? Math.max(0, raw.candidatesTokenCount)
      : undefined;
  const reasoningTokens =
    typeof raw.thoughtsTokenCount === "number"
      ? Math.max(0, raw.thoughtsTokenCount)
      : undefined;

  return {
    promptTokens:
      inclusivePrompt !== undefined
        ? Math.max(0, inclusivePrompt - (cachedReadTokens ?? 0))
        : undefined,
    cachedReadTokens,
    completionTokens,
    reasoningTokens,
  };
}

export function mergeUsage(
  base: StreamUsageStats,
  patch: Partial<StreamUsageStats> | null,
): StreamUsageStats {
  if (!patch) return base;
  return {
    promptTokens: patch.promptTokens ?? base.promptTokens,
    cachedReadTokens: patch.cachedReadTokens ?? base.cachedReadTokens,
    cachedWriteTokens: patch.cachedWriteTokens ?? base.cachedWriteTokens,
    completionTokens: patch.completionTokens ?? base.completionTokens,
    reasoningTokens: patch.reasoningTokens ?? base.reasoningTokens,
  };
}

function buildGatewayRequest(options: StreamCompletionOptions): {
  endpoint: string;
  headers: Record<string, string>;
  payload: Record<string, unknown>;
} {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: options.stream ? "text/event-stream" : "application/json",
  };
  const systemTrimmed = options.systemPrompt.trim();
  const effort = options.thinkingEffort ?? "off";

  if (options.protocol === "OPENAI_CHAT") {
    headers.Authorization = `Bearer ${options.sandboxToken}`;
    const messages: Record<string, unknown>[] = [];
    if (systemTrimmed) {
      messages.push({ role: "system", content: systemTrimmed });
    }
    for (const m of options.messages) {
      if (m.role === "user" && m.images && m.images.length > 0) {
        const parts: Record<string, unknown>[] = [];
        if (m.content) {
          parts.push({ type: "text", text: m.content });
        }
        for (const img of m.images) {
          parts.push({ type: "image_url", image_url: { url: img.dataUrl } });
        }
        messages.push({ role: "user", content: parts });
      } else {
        messages.push({ role: m.role, content: m.content });
      }
    }
    return {
      endpoint: "/api/gateway/v1/chat/completions",
      headers,
      payload: {
        model: options.model,
        messages,
        temperature: options.temperature,
        max_tokens: options.maxTokens,
        stream: options.stream,
        ...(effort !== "off" ? { reasoning_effort: effort } : {}),
        ...(options.stream ? { stream_options: { include_usage: true } } : {}),
      },
    };
  }

  if (options.protocol === "OPENAI_RESPONSES") {
    headers.Authorization = `Bearer ${options.sandboxToken}`;
    const input = options.messages.map((m) => {
      if (m.role === "user") {
        const parts: Record<string, unknown>[] = [];
        if (m.content) {
          parts.push({ type: "input_text", text: m.content });
        }
        for (const img of m.images ?? []) {
          parts.push({ type: "input_image", image_url: img.dataUrl });
        }
        return {
          type: "message",
          role: "user",
          content: parts.length > 0 ? parts : [{ type: "input_text", text: "" }],
        };
      }
      return {
        type: "message",
        role: "assistant",
        content: [{ type: "output_text", text: m.content }],
      };
    });

    return {
      endpoint: "/api/gateway/v1/responses",
      headers,
      payload: {
        model: options.model,
        ...(systemTrimmed ? { instructions: systemTrimmed } : {}),
        input,
        temperature: options.temperature,
        max_output_tokens: options.maxTokens,
        stream: options.stream,
        ...(effort !== "off" ? { reasoning: { effort } } : {}),
      },
    };
  }

  if (options.protocol === "ANTHROPIC_MESSAGES") {
    headers["x-api-key"] = options.sandboxToken;
    headers["anthropic-version"] = "2023-06-01";

    const messages = options.messages.map((m) => {
      if (m.role === "user" && m.images && m.images.length > 0) {
        const blocks: Record<string, unknown>[] = [];
        for (const img of m.images) {
          blocks.push({
            type: "image",
            source: {
              type: "base64",
              media_type: img.mimeType,
              data: img.base64,
            },
          });
        }
        if (m.content) {
          blocks.push({ type: "text", text: m.content });
        }
        return { role: "user", content: blocks };
      }
      return { role: m.role, content: m.content };
    });

    const thinkingBudget =
      effort !== "off" ? THINKING_BUDGET_TOKENS[effort] : null;
    const effectiveMaxTokens =
      thinkingBudget !== null
        ? Math.max(options.maxTokens, thinkingBudget + 1024)
        : options.maxTokens;

    return {
      endpoint: "/api/gateway/v1/messages",
      headers,
      payload: {
        model: options.model,
        ...(systemTrimmed ? { system: systemTrimmed } : {}),
        messages,
        max_tokens: effectiveMaxTokens,
        ...(thinkingBudget === null ? { temperature: options.temperature } : {}),
        ...(thinkingBudget !== null
          ? { thinking: { type: "enabled", budget_tokens: thinkingBudget } }
          : {}),
        stream: options.stream,
      },
    };
  }

  // GEMINI_GENERATE_CONTENT
  headers["x-goog-api-key"] = options.sandboxToken;
  const encodedModel = encodeURIComponent(options.model);
  const endpoint = options.stream
    ? `/api/gateway/v1beta/models/${encodedModel}:streamGenerateContent?alt=sse`
    : `/api/gateway/v1beta/models/${encodedModel}:generateContent`;

  const contents = options.messages.map((m) => {
    const parts: Record<string, unknown>[] = [];
    if (m.content) {
      parts.push({ text: m.content });
    }
    if (m.role === "user") {
      for (const img of m.images ?? []) {
        parts.push({
          inlineData: {
            mimeType: img.mimeType,
            data: img.base64,
          },
        });
      }
    }
    return {
      role: m.role === "assistant" ? "model" : "user",
      parts: parts.length > 0 ? parts : [{ text: "" }],
    };
  });

  const thinkingBudget =
    effort !== "off" ? THINKING_BUDGET_TOKENS[effort] : null;
  const generationConfig: Record<string, unknown> = {
    temperature: options.temperature,
    maxOutputTokens: options.maxTokens,
    ...(thinkingBudget !== null
      ? {
          thinkingConfig: {
            includeThoughts: true,
            thinkingBudget,
          },
        }
      : {}),
  };

  return {
    endpoint,
    headers,
    payload: {
      ...(systemTrimmed
        ? { systemInstruction: { parts: [{ text: systemTrimmed }] } }
        : {}),
      contents,
      generationConfig,
    },
  };
}

export async function runPlaygroundCompletion(
  options: StreamCompletionOptions,
): Promise<StreamCompletionResult> {
  const startedAt = performance.now();
  let ttftMs: number | null = null;
  let content = "";
  let thinking = "";
  let usage = emptyUsage();

  const markFirstToken = () => {
    if (ttftMs === null) {
      ttftMs = Math.round(performance.now() - startedAt);
      options.onTtft?.(ttftMs);
    }
  };

  const emitText = (chunk: string) => {
    if (!chunk) return;
    markFirstToken();
    content += chunk;
    options.onTextDelta(chunk);
  };

  const emitThinking = (chunk: string) => {
    if (!chunk) return;
    markFirstToken();
    thinking += chunk;
    options.onThinkingDelta?.(chunk);
  };

  const applyUsage = (patch: Partial<StreamUsageStats> | null) => {
    if (!patch) return;
    usage = mergeUsage(usage, patch);
    options.onUsage?.(usage);
  };

  const { endpoint, headers, payload } = buildGatewayRequest(options);

  let response: Response;
  try {
    response = await fetch(endpoint, {
      method: "POST",
      headers,
      body: JSON.stringify(payload),
      signal: options.signal,
      cache: "no-store",
    });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      throw err;
    }
    throw new ApiError({
      status: 0,
      code: "GATEWAY_UNREACHABLE",
      message: "Gateway unreachable",
    });
  }

  if (!response.ok) {
    throw await parseApiError(response);
  }

  if (!options.stream || !response.body) {
    const raw: unknown = await response.json();
    if (isRecord(raw)) {
      if (options.protocol === "OPENAI_CHAT") {
        const choices = Array.isArray(raw.choices) ? raw.choices : [];
        const first = choices[0];
        if (isRecord(first) && isRecord(first.message)) {
          if (typeof first.message.reasoning_content === "string") {
            emitThinking(first.message.reasoning_content);
          }
          if (typeof first.message.content === "string") {
            emitText(first.message.content);
          }
        }
        applyUsage(parseOpenAiChatUsage(raw.usage));
      } else if (options.protocol === "OPENAI_RESPONSES") {
        const output = Array.isArray(raw.output) ? raw.output : [];
        for (const item of output) {
          if (!isRecord(item)) continue;
          if (item.type === "reasoning" && Array.isArray(item.summary)) {
            for (const s of item.summary) {
              if (isRecord(s) && typeof s.text === "string") {
                emitThinking(s.text);
              }
            }
          } else if (item.type === "message" && Array.isArray(item.content)) {
            for (const part of item.content) {
              if (isRecord(part) && typeof part.text === "string") {
                emitText(part.text);
              }
            }
          }
        }
        applyUsage(parseOpenAiResponsesUsage(raw.usage));
      } else if (options.protocol === "ANTHROPIC_MESSAGES") {
        const blocks = Array.isArray(raw.content) ? raw.content : [];
        for (const block of blocks) {
          if (!isRecord(block)) continue;
          if (block.type === "thinking" && typeof block.thinking === "string") {
            emitThinking(block.thinking);
          } else if (block.type === "text" && typeof block.text === "string") {
            emitText(block.text);
          }
        }
        applyUsage(parseAnthropicUsage(raw.usage));
      } else {
        // GEMINI_GENERATE_CONTENT
        const candidates = Array.isArray(raw.candidates) ? raw.candidates : [];
        const first = candidates[0];
        if (isRecord(first) && isRecord(first.content)) {
          const parts = Array.isArray(first.content.parts)
            ? first.content.parts
            : [];
          for (const part of parts) {
            if (!isRecord(part) || typeof part.text !== "string") continue;
            if (part.thought === true) {
              emitThinking(part.text);
            } else {
              emitText(part.text);
            }
          }
        }
        applyUsage(parseGeminiUsage(raw.usageMetadata));
      }
    }

    return {
      content,
      thinking,
      ttftMs,
      totalMs: Math.round(performance.now() - startedAt),
      usage,
    };
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  const processEventData = (rawData: string) => {
    const trimmed = rawData.trim();
    if (!trimmed || trimmed === "[DONE]") return;

    let parsed: unknown;
    try {
      parsed = JSON.parse(trimmed);
    } catch {
      return;
    }
    if (!isRecord(parsed)) return;

    if (options.protocol === "OPENAI_CHAT") {
      if (parsed.usage) {
        applyUsage(parseOpenAiChatUsage(parsed.usage));
      }
      const choices = Array.isArray(parsed.choices) ? parsed.choices : [];
      const first = choices[0];
      if (isRecord(first) && isRecord(first.delta)) {
        const delta = first.delta;
        if (typeof delta.reasoning_content === "string") {
          emitThinking(delta.reasoning_content);
        }
        if (typeof delta.content === "string") {
          emitText(delta.content);
        }
      }
      return;
    }

    if (options.protocol === "OPENAI_RESPONSES") {
      const type = parsed.type;
      if (
        type === "response.output_text.delta" &&
        typeof parsed.delta === "string"
      ) {
        emitText(parsed.delta);
      } else if (
        (type === "response.reasoning_summary_text.delta" ||
          type === "response.reasoning_text.delta") &&
        typeof parsed.delta === "string"
      ) {
        emitThinking(parsed.delta);
      } else if (
        (type === "response.completed" || type === "response.incomplete") &&
        isRecord(parsed.response)
      ) {
        applyUsage(parseOpenAiResponsesUsage(parsed.response.usage));
      }
      return;
    }

    if (options.protocol === "ANTHROPIC_MESSAGES") {
      const eventType = parsed.type;
      if (eventType === "message_start" && isRecord(parsed.message)) {
        applyUsage(parseAnthropicUsage(parsed.message.usage));
      } else if (eventType === "content_block_delta" && isRecord(parsed.delta)) {
        const delta = parsed.delta;
        if (
          delta.type === "thinking_delta" &&
          typeof delta.thinking === "string"
        ) {
          emitThinking(delta.thinking);
        } else if (
          delta.type === "text_delta" &&
          typeof delta.text === "string"
        ) {
          emitText(delta.text);
        }
      } else if (eventType === "message_delta") {
        applyUsage(parseAnthropicUsage(parsed.usage));
      }
      return;
    }

    // GEMINI_GENERATE_CONTENT SSE frame
    const candidates = Array.isArray(parsed.candidates) ? parsed.candidates : [];
    const first = candidates[0];
    if (isRecord(first) && isRecord(first.content)) {
      const parts = Array.isArray(first.content.parts)
        ? first.content.parts
        : [];
      for (const part of parts) {
        if (!isRecord(part) || typeof part.text !== "string") continue;
        if (part.thought === true) {
          emitThinking(part.text);
        } else {
          emitText(part.text);
        }
      }
    }
    if (parsed.usageMetadata) {
      applyUsage(parseGeminiUsage(parsed.usageMetadata));
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const events = buffer.split(/\r?\n\r?\n/);
    buffer = events.pop() ?? "";

    for (const rawEvent of events) {
      const dataLines: string[] = [];
      for (const line of rawEvent.split(/\r?\n/)) {
        if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).trimStart());
        }
      }
      if (dataLines.length > 0) {
        processEventData(dataLines.join("\n"));
      }
    }
  }

  return {
    content,
    thinking,
    ttftMs,
    totalMs: Math.round(performance.now() - startedAt),
    usage,
  };
}

export type CodeSnippetLanguage = "curl" | "python" | "typescript";

export function buildProtocolCodeSnippet(params: {
  baseUrl: string;
  protocol: PlaygroundProtocol;
  language: CodeSnippetLanguage;
  model: string;
  systemPrompt: string;
  messages: { role: "user" | "assistant"; content: string }[];
  temperature: number;
  maxTokens: number;
  stream: boolean;
}): string {
  const base = params.baseUrl.replace(/\/+$/, "");
  const model = params.model || "gpt-4o";
  const sys = params.systemPrompt.trim();
  const sampleMsgs =
    params.messages.length > 0
      ? params.messages
      : [{ role: "user" as const, content: "你好，请介绍一下 HyperLLM" }];

  if (params.protocol === "OPENAI_CHAT") {
    const msgs = [
      ...(sys ? [{ role: "system", content: sys }] : []),
      ...sampleMsgs,
    ];
    if (params.language === "python") {
      return `from openai import OpenAI

client = OpenAI(
    base_url="${base}/v1",
    api_key="sk-hl-...",
)

response = client.chat.completions.create(
    model=${JSON.stringify(model)},
    messages=${JSON.stringify(msgs, null, 4)},
    temperature=${params.temperature},
    max_tokens=${params.maxTokens},
    stream=${params.stream ? "True" : "False"},
)`;
    }
    if (params.language === "typescript") {
      return `import OpenAI from "openai";

const client = new OpenAI({
  baseURL: "${base}/v1",
  apiKey: process.env.HYPERLLM_API_KEY,
});

const response = await client.chat.completions.create({
  model: ${JSON.stringify(model)},
  messages: ${JSON.stringify(msgs, null, 2)},
  temperature: ${params.temperature},
  max_tokens: ${params.maxTokens},
  stream: ${params.stream},
});`;
    }
    return `curl ${base}/v1/chat/completions \\
  -H "Authorization: Bearer $HYPERLLM_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify(
    {
      model,
      messages: msgs,
      temperature: params.temperature,
      max_tokens: params.maxTokens,
      stream: params.stream,
    },
    null,
    2,
  )}'`;
  }

  if (params.protocol === "OPENAI_RESPONSES") {
    const input = sampleMsgs.map((m) => ({
      role: m.role,
      content: m.content,
    }));
    if (params.language === "python") {
      return `from openai import OpenAI

client = OpenAI(
    base_url="${base}/v1",
    api_key="sk-hl-...",
)

response = client.responses.create(
    model=${JSON.stringify(model)},${sys ? `\n    instructions=${JSON.stringify(sys)},` : ""}
    input=${JSON.stringify(input, null, 4)},
    max_output_tokens=${params.maxTokens},
    stream=${params.stream ? "True" : "False"},
)`;
    }
    if (params.language === "typescript") {
      return `import OpenAI from "openai";

const client = new OpenAI({
  baseURL: "${base}/v1",
  apiKey: process.env.HYPERLLM_API_KEY,
});

const response = await client.responses.create({
  model: ${JSON.stringify(model)},${sys ? `\n  instructions: ${JSON.stringify(sys)},` : ""}
  input: ${JSON.stringify(input, null, 2)},
  max_output_tokens: ${params.maxTokens},
  stream: ${params.stream},
});`;
    }
    return `curl ${base}/v1/responses \\
  -H "Authorization: Bearer $HYPERLLM_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify(
    {
      model,
      ...(sys ? { instructions: sys } : {}),
      input,
      temperature: params.temperature,
      max_output_tokens: params.maxTokens,
      stream: params.stream,
    },
    null,
    2,
  )}'`;
  }

  if (params.protocol === "ANTHROPIC_MESSAGES") {
    if (params.language === "python") {
      return `import anthropic

client = anthropic.Anthropic(
    base_url="${base}",
    api_key="sk-hl-...",
)

message = client.messages.create(
    model=${JSON.stringify(model)},${sys ? `\n    system=${JSON.stringify(sys)},` : ""}
    max_tokens=${params.maxTokens},
    messages=${JSON.stringify(sampleMsgs, null, 4)},
    stream=${params.stream ? "True" : "False"},
)`;
    }
    if (params.language === "typescript") {
      return `import Anthropic from "@anthropic-ai/sdk";

const client = new Anthropic({
  baseURL: "${base}",
  apiKey: process.env.HYPERLLM_API_KEY,
});

const message = await client.messages.create({
  model: ${JSON.stringify(model)},${sys ? `\n  system: ${JSON.stringify(sys)},` : ""}
  max_tokens: ${params.maxTokens},
  messages: ${JSON.stringify(sampleMsgs, null, 2)},
  stream: ${params.stream},
});`;
    }
    return `curl ${base}/v1/messages \\
  -H "x-api-key: $HYPERLLM_API_KEY" \\
  -H "anthropic-version: 2023-06-01" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify(
    {
      model,
      ...(sys ? { system: sys } : {}),
      messages: sampleMsgs,
      temperature: params.temperature,
      max_tokens: params.maxTokens,
      stream: params.stream,
    },
    null,
    2,
  )}'`;
  }

  // GEMINI_GENERATE_CONTENT
  const geminiContents = sampleMsgs.map((m) => ({
    role: m.role === "assistant" ? "model" : "user",
    parts: [{ text: m.content }],
  }));
  const action = params.stream
    ? "streamGenerateContent?alt=sse"
    : "generateContent";

  if (params.language === "python") {
    return `import requests

resp = requests.post(
    "${base}/v1beta/models/${model}:${action}",
    headers={"x-goog-api-key": "sk-hl-..."},
    json=${JSON.stringify(
      {
        ...(sys ? { systemInstruction: { parts: [{ text: sys }] } } : {}),
        contents: geminiContents,
        generationConfig: {
          temperature: params.temperature,
          maxOutputTokens: params.maxTokens,
        },
      },
      null,
      4,
    )},
    stream=${params.stream ? "True" : "False"},
)`;
  }
  if (params.language === "typescript") {
    return `const response = await fetch(
  "${base}/v1beta/models/${model}:${action}",
  {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-goog-api-key": process.env.HYPERLLM_API_KEY!,
    },
    body: JSON.stringify(${JSON.stringify(
      {
        ...(sys ? { systemInstruction: { parts: [{ text: sys }] } } : {}),
        contents: geminiContents,
        generationConfig: {
          temperature: params.temperature,
          maxOutputTokens: params.maxTokens,
        },
      },
      null,
      2,
    )}),
  },
);`;
  }
  return `curl "${base}/v1beta/models/${model}:${action}" \\
  -H "x-goog-api-key: $HYPERLLM_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify(
    {
      ...(sys ? { systemInstruction: { parts: [{ text: sys }] } } : {}),
      contents: geminiContents,
      generationConfig: {
        temperature: params.temperature,
        maxOutputTokens: params.maxTokens,
      },
    },
    null,
    2,
  )}'`;
}
