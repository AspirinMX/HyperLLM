import { ApiError, isRecord, parseApiError } from "./error";

export type PlaygroundProtocol = "OPENAI" | "ANTHROPIC";

export interface PlaygroundChatMessage {
  role: "user" | "assistant";
  content: string;
}

export interface StreamUsageStats {
  promptTokens: number;
  completionTokens: number;
  cachedReadTokens: number;
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

function parseOpenAiUsage(raw: unknown): Partial<StreamUsageStats> | null {
  if (!isRecord(raw)) return null;
  const promptTokens =
    typeof raw.prompt_tokens === "number" ? raw.prompt_tokens : undefined;
  const completionTokens =
    typeof raw.completion_tokens === "number"
      ? raw.completion_tokens
      : undefined;

  let cachedReadTokens: number | undefined;
  if (
    isRecord(raw.prompt_tokens_details) &&
    typeof raw.prompt_tokens_details.cached_tokens === "number"
  ) {
    cachedReadTokens = raw.prompt_tokens_details.cached_tokens;
  }

  let reasoningTokens: number | undefined;
  if (
    isRecord(raw.completion_tokens_details) &&
    typeof raw.completion_tokens_details.reasoning_tokens === "number"
  ) {
    reasoningTokens = raw.completion_tokens_details.reasoning_tokens;
  }

  return {
    promptTokens,
    completionTokens,
    cachedReadTokens,
    reasoningTokens,
  };
}

function parseAnthropicUsage(raw: unknown): Partial<StreamUsageStats> | null {
  if (!isRecord(raw)) return null;
  return {
    promptTokens:
      typeof raw.input_tokens === "number" ? raw.input_tokens : undefined,
    completionTokens:
      typeof raw.output_tokens === "number" ? raw.output_tokens : undefined,
    cachedReadTokens:
      typeof raw.cache_read_input_tokens === "number"
        ? raw.cache_read_input_tokens
        : undefined,
  };
}

function mergeUsage(
  base: StreamUsageStats,
  patch: Partial<StreamUsageStats> | null,
): StreamUsageStats {
  if (!patch) return base;
  return {
    promptTokens: patch.promptTokens ?? base.promptTokens,
    completionTokens: patch.completionTokens ?? base.completionTokens,
    cachedReadTokens: patch.cachedReadTokens ?? base.cachedReadTokens,
    reasoningTokens: patch.reasoningTokens ?? base.reasoningTokens,
  };
}

export async function runPlaygroundCompletion(
  options: StreamCompletionOptions,
): Promise<StreamCompletionResult> {
  const startedAt = performance.now();
  let ttftMs: number | null = null;
  let content = "";
  let thinking = "";
  let usage: StreamUsageStats = {
    promptTokens: 0,
    completionTokens: 0,
    cachedReadTokens: 0,
    reasoningTokens: 0,
  };

  const markFirstToken = () => {
    if (ttftMs === null) {
      ttftMs = Math.round(performance.now() - startedAt);
      options.onTtft?.(ttftMs);
    }
  };

  const isOpenAi = options.protocol === "OPENAI";
  const endpoint = isOpenAi
    ? "/api/gateway/v1/chat/completions"
    : "/api/gateway/v1/messages";

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: options.stream ? "text/event-stream" : "application/json",
  };

  let payload: Record<string, unknown>;
  if (isOpenAi) {
    headers.Authorization = `Bearer ${options.sandboxToken}`;
    const messages = [
      ...(options.systemPrompt.trim()
        ? [{ role: "system", content: options.systemPrompt.trim() }]
        : []),
      ...options.messages.map((m) => ({ role: m.role, content: m.content })),
    ];
    payload = {
      model: options.model,
      messages,
      temperature: options.temperature,
      max_tokens: options.maxTokens,
      stream: options.stream,
      ...(options.stream ? { stream_options: { include_usage: true } } : {}),
    };
  } else {
    headers["x-api-key"] = options.sandboxToken;
    headers["anthropic-version"] = "2023-06-01";
    payload = {
      model: options.model,
      messages: options.messages.map((m) => ({
        role: m.role,
        content: m.content,
      })),
      ...(options.systemPrompt.trim()
        ? { system: options.systemPrompt.trim() }
        : {}),
      temperature: options.temperature,
      max_tokens: options.maxTokens,
      stream: options.stream,
    };
  }

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
      if (isOpenAi) {
        const choices = Array.isArray(raw.choices) ? raw.choices : [];
        const first = choices[0];
        if (isRecord(first) && isRecord(first.message)) {
          if (typeof first.message.reasoning_content === "string") {
            thinking = first.message.reasoning_content;
            options.onThinkingDelta?.(thinking);
          }
          if (typeof first.message.content === "string") {
            content = first.message.content;
            markFirstToken();
            options.onTextDelta(content);
          }
        }
        usage = mergeUsage(usage, parseOpenAiUsage(raw.usage));
      } else {
        const blocks = Array.isArray(raw.content) ? raw.content : [];
        for (const block of blocks) {
          if (!isRecord(block)) continue;
          if (block.type === "thinking" && typeof block.thinking === "string") {
            thinking += block.thinking;
            options.onThinkingDelta?.(block.thinking);
          } else if (block.type === "text" && typeof block.text === "string") {
            content += block.text;
            markFirstToken();
            options.onTextDelta(block.text);
          }
        }
        usage = mergeUsage(usage, parseAnthropicUsage(raw.usage));
      }
      options.onUsage?.(usage);
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

    if (isOpenAi) {
      if (parsed.usage) {
        usage = mergeUsage(usage, parseOpenAiUsage(parsed.usage));
        options.onUsage?.(usage);
      }
      const choices = Array.isArray(parsed.choices) ? parsed.choices : [];
      const first = choices[0];
      if (isRecord(first) && isRecord(first.delta)) {
        const delta = first.delta;
        if (typeof delta.reasoning_content === "string" && delta.reasoning_content) {
          markFirstToken();
          thinking += delta.reasoning_content;
          options.onThinkingDelta?.(delta.reasoning_content);
        }
        if (typeof delta.content === "string" && delta.content) {
          markFirstToken();
          content += delta.content;
          options.onTextDelta(delta.content);
        }
      }
      return;
    }

    // Anthropic native SSE events
    const eventType = parsed.type;
    if (eventType === "message_start" && isRecord(parsed.message)) {
      usage = mergeUsage(usage, parseAnthropicUsage(parsed.message.usage));
      options.onUsage?.(usage);
    } else if (eventType === "content_block_delta" && isRecord(parsed.delta)) {
      const delta = parsed.delta;
      if (delta.type === "thinking_delta" && typeof delta.thinking === "string") {
        markFirstToken();
        thinking += delta.thinking;
        options.onThinkingDelta?.(delta.thinking);
      } else if (delta.type === "text_delta" && typeof delta.text === "string") {
        markFirstToken();
        content += delta.text;
        options.onTextDelta(delta.text);
      }
    } else if (eventType === "message_delta") {
      usage = mergeUsage(usage, parseAnthropicUsage(parsed.usage));
      options.onUsage?.(usage);
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
