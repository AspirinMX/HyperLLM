"use client";

import {
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";
import { useSearchParams } from "next/navigation";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  ArrowUp,
  Brain,
  ChevronDown,
  Code2,
  PanelRight,
  Square,
  Terminal,
  Trash2,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { CopyButton } from "@/components/common/copy-button";
import { ProportionBar } from "@/components/common/proportion-bar";
import { StatusDot } from "@/components/common/status-dot";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { catalogApi, playgroundApi, projectApi } from "@/lib/api/endpoints";
import {
  runPlaygroundCompletion,
  type PlaygroundProtocol,
  type StreamUsageStats,
} from "@/lib/api/sse";
import {
  ceilTokenCostMicros,
  formatCompactNumber,
  formatLatencyMs,
  formatMicroUsd,
} from "@/lib/format/micro-usd";
import { cn } from "@/lib/utils";

interface TurnMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  thinking?: string;
  streaming?: boolean;
  ttftMs?: number | null;
  totalMs?: number;
  usage?: StreamUsageStats;
  estimatedCostMicros?: number;
}

interface SandboxTokenCache {
  projectId: string;
  token: string;
  expiresAtMs: number;
}

function PlaygroundContent() {
  const searchParams = useSearchParams();
  const queryModel = searchParams.get("model") ?? "";
  const { currentOrg } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;

  const projectsState = useAsyncData(
    (signal) => projectApi.list(orgId!, 1, 50, signal),
    [orgId],
    Boolean(orgId),
  );

  const modelsState = useAsyncData(
    (signal) => catalogApi.listModels({ auth: true, signal }),
    [orgId],
    Boolean(orgId),
  );

  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [selectedModelCode, setSelectedModelCode] = useState(queryModel);
  const [protocol, setProtocol] = useState<PlaygroundProtocol>("OPENAI");
  const [systemPrompt, setSystemPrompt] = useState("");
  const [temperature, setTemperature] = useState(0.7);
  const [maxTokens, setMaxTokens] = useState(2048);
  const [streamEnabled, setStreamEnabled] = useState(true);
  const [showConfig, setShowConfig] = useState(true);
  const [codeDialogOpen, setCodeDialogOpen] = useState(false);

  const [messages, setMessages] = useState<TurnMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const tokenCacheRef = useRef<SandboxTokenCache | null>(null);
  const [tokenReady, setTokenReady] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const scrollEndRef = useRef<HTMLDivElement | null>(null);

  const activeProjectId = useMemo(() => {
    const list = projectsState.data?.items ?? [];
    if (selectedProjectId && list.some((p) => p.id === selectedProjectId)) {
      return selectedProjectId;
    }
    return list[0]?.id ?? "";
  }, [projectsState.data, selectedProjectId]);

  const activeModel = useMemo(() => {
    const list = modelsState.data ?? [];
    return (
      list.find((m) => m.modelCode === selectedModelCode) ??
      list.find((m) => m.modelCode === queryModel) ??
      list[0] ??
      null
    );
  }, [modelsState.data, selectedModelCode, queryModel]);

  const activeModelCode = activeModel?.modelCode ?? selectedModelCode;

  useEffect(() => {
    scrollEndRef.current?.scrollIntoView({ behavior: "auto" });
  }, [messages.length, isStreaming]);

  const ensureSandboxToken = useCallback(
    async (forceRefresh = false): Promise<string> => {
      if (!activeProjectId) {
        throw new Error("No active project selected");
      }
      const cached = tokenCacheRef.current;
      if (
        !forceRefresh &&
        cached &&
        cached.projectId === activeProjectId &&
        Date.now() < cached.expiresAtMs - 15_000
      ) {
        return cached.token;
      }

      const issued = await playgroundApi.issueEphemeralToken(activeProjectId);
      tokenCacheRef.current = {
        projectId: activeProjectId,
        token: issued.token,
        expiresAtMs: Date.now() + issued.ttlSeconds * 1000,
      };
      setTokenReady(true);
      return issued.token;
    },
    [activeProjectId],
  );

  const estimateCost = useCallback(
    (usage: StreamUsageStats): number => {
      const p = activeModel?.pricing;
      if (!p) return 0;
      const effectiveReasoningPrice =
        p.reasoningPriceMicros1m > 0
          ? p.reasoningPriceMicros1m
          : p.outputPriceMicros1m;
      return (
        ceilTokenCostMicros(usage.promptTokens, p.inputPriceMicros1m) +
        ceilTokenCostMicros(usage.cachedReadTokens, p.cacheReadPriceMicros1m) +
        ceilTokenCostMicros(usage.cachedWriteTokens, p.cacheWritePriceMicros1m) +
        ceilTokenCostMicros(usage.completionTokens, p.outputPriceMicros1m) +
        ceilTokenCostMicros(usage.reasoningTokens, effectiveReasoningPrice)
      );
    },
    [activeModel],
  );

  async function handleSend() {
    const trimmed = draft.trim();
    if (!trimmed || isStreaming) return;
    if (!activeProjectId || !activeModelCode) {
      toast.error("请先选择项目与模型");
      return;
    }

    setError(null);
    setDraft("");

    const userMsg: TurnMessage = {
      id: `u-${Date.now()}`,
      role: "user",
      content: trimmed,
    };
    const assistantId = `a-${Date.now() + 1}`;
    const placeholder: TurnMessage = {
      id: assistantId,
      role: "assistant",
      content: "",
      thinking: "",
      streaming: true,
    };

    const history = [...messages, userMsg].map((m) => ({
      role: m.role,
      content: m.content,
    }));

    setMessages((prev) => [...prev, userMsg, placeholder]);
    setIsStreaming(true);

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const sandboxToken = await ensureSandboxToken();
      const result = await runPlaygroundCompletion({
        protocol,
        sandboxToken,
        model: activeModelCode,
        systemPrompt,
        messages: history,
        temperature,
        maxTokens,
        stream: streamEnabled,
        signal: controller.signal,
        onTextDelta: (chunk) => {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId ? { ...m, content: m.content + chunk } : m,
            ),
          );
        },
        onThinkingDelta: (chunk) => {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId
                ? { ...m, thinking: (m.thinking ?? "") + chunk }
                : m,
            ),
          );
        },
        onTtft: (ttftMs) => {
          setMessages((prev) =>
            prev.map((m) => (m.id === assistantId ? { ...m, ttftMs } : m)),
          );
        },
        onUsage: (usage) => {
          const estimatedCostMicros = estimateCost(usage);
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId
                ? { ...m, usage, estimatedCostMicros }
                : m,
            ),
          );
        },
      });

      const estimatedCostMicros = estimateCost(result.usage);
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantId
            ? {
                ...m,
                content: result.content,
                thinking: result.thinking,
                streaming: false,
                ttftMs: result.ttftMs,
                totalMs: result.totalMs,
                usage: result.usage,
                estimatedCostMicros,
              }
            : m,
        ),
      );
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId ? { ...m, streaming: false } : m,
          ),
        );
      } else {
        setError(err);
        setMessages((prev) =>
          prev.filter((m) => m.id !== assistantId || m.content.length > 0),
        );
      }
    } finally {
      setIsStreaming(false);
      abortRef.current = null;
    }
  }

  function handleStop() {
    abortRef.current?.abort();
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void handleSend();
    }
  }

  const sessionTotals = useMemo(() => {
    return messages.reduce(
      (acc, m) => {
        if (m.role !== "assistant") return acc;
        acc.prompt += m.usage?.promptTokens ?? 0;
        acc.cached += m.usage?.cachedReadTokens ?? 0;
        acc.completion += m.usage?.completionTokens ?? 0;
        acc.reasoning += m.usage?.reasoningTokens ?? 0;
        acc.costMicros += m.estimatedCostMicros ?? 0;
        return acc;
      },
      { prompt: 0, cached: 0, completion: 0, reasoning: 0, costMicros: 0 },
    );
  }, [messages]);

  const exportedSnippet = useMemo(() => {
    const sampleMessages =
      messages.length > 0
        ? messages.map((m) => ({ role: m.role, content: m.content }))
        : [{ role: "user", content: "你好" }];
    if (protocol === "OPENAI") {
      return `curl https://api.hyperllm.dev/v1/chat/completions \\
  -H "Authorization: Bearer $HYPERLLM_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify(
    {
      model: activeModelCode || "gpt-4o",
      messages: [
        ...(systemPrompt.trim()
          ? [{ role: "system", content: systemPrompt.trim() }]
          : []),
        ...sampleMessages,
      ],
      temperature,
      max_tokens: maxTokens,
      stream: streamEnabled,
    },
    null,
    2,
  )}'`;
    }
    return `curl https://api.hyperllm.dev/v1/messages \\
  -H "x-api-key: $HYPERLLM_API_KEY" \\
  -H "anthropic-version: 2023-06-01" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify(
    {
      model: activeModelCode || "claude-sonnet-4-5",
      ...(systemPrompt.trim() ? { system: systemPrompt.trim() } : {}),
      messages: sampleMessages,
      temperature,
      max_tokens: maxTokens,
      stream: streamEnabled,
    },
    null,
    2,
  )}'`;
  }, [
    protocol,
    activeModelCode,
    systemPrompt,
    messages,
    temperature,
    maxTokens,
    streamEnabled,
  ]);

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
      {/* Top Playground Control Bar */}
      <div className="flex h-12 shrink-0 items-center justify-between gap-2 border-b border-border bg-background px-4">
        <div className="flex min-w-0 items-center gap-2">
          <Select
            value={activeProjectId}
            onValueChange={(v) => {
              setSelectedProjectId(v ?? "");
              tokenCacheRef.current = null;
              setTokenReady(false);
            }}
          >
            <SelectTrigger size="sm" className="w-36">
              <SelectValue placeholder="项目" />
            </SelectTrigger>
            <SelectContent>
              {(projectsState.data?.items ?? []).map((p) => (
                <SelectItem key={p.id} value={p.id}>
                  {p.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select
            value={activeModelCode}
            onValueChange={(v) => {
              const code = v ?? "";
              setSelectedModelCode(code);
              const target = (modelsState.data ?? []).find(
                (m) => m.modelCode === code,
              );
              if (target?.nativeProtocol === "ANTHROPIC") {
                setProtocol("ANTHROPIC");
              } else if (target?.nativeProtocol === "OPENAI") {
                setProtocol("OPENAI");
              }
            }}
          >
            <SelectTrigger size="sm" className="w-48 font-mono text-xs">
              <SelectValue placeholder="选择模型" />
            </SelectTrigger>
            <SelectContent>
              {(modelsState.data ?? []).map((m) => (
                <SelectItem key={m.id} value={m.modelCode}>
                  {m.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Tabs
            value={protocol}
            onValueChange={(v) => setProtocol(v as PlaygroundProtocol)}
            className="hidden sm:flex"
          >
            <TabsList className="h-7">
              <TabsTrigger value="OPENAI" className="px-2 text-xs">
                OpenAI
              </TabsTrigger>
              <TabsTrigger value="ANTHROPIC" className="px-2 text-xs">
                Anthropic
              </TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        <div className="flex items-center gap-1.5">
          <StatusDot
            tone={tokenReady ? "success" : "muted"}
            label={tokenReady ? "沙盒就绪" : "待签发"}
            className="mr-1 hidden md:inline-flex"
          />
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={() => setCodeDialogOpen(true)}
          >
            <Code2 className="size-3.5" />
            <span className="hidden sm:inline">导出代码</span>
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label="清空对话"
            disabled={messages.length === 0 || isStreaming}
            onClick={() => {
              setMessages([]);
              setError(null);
            }}
          >
            <Trash2 className="size-3.5" />
          </Button>
          <Button
            type="button"
            variant={showConfig ? "secondary" : "ghost"}
            size="icon-sm"
            aria-label="切换参数面板"
            onClick={() => setShowConfig((v) => !v)}
          >
            <PanelRight className="size-3.5" />
          </Button>
        </div>
      </div>

      {/* Fixed-height split workspace */}
      <ResizablePanelGroup
        orientation="horizontal"
        className="min-h-0 flex-1 overflow-hidden"
      >
        <ResizablePanel
          defaultSize={showConfig ? "72%" : "100%"}
          minSize="45%"
          style={{ overflow: "hidden", height: "100%" }}
        >
          <div className="relative flex h-full min-h-0 flex-col">
            <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-5 pb-36 sm:px-6">
              <div className="mx-auto flex max-w-3xl flex-col gap-5">
                {error ? <ApiErrorAlert error={error} /> : null}

                {messages.length === 0 ? (
                  <div className="flex flex-col items-center justify-center gap-3 py-24 text-center">
                    <div className="flex size-10 items-center justify-center rounded-xl bg-muted text-muted-foreground">
                      <Terminal className="size-4" />
                    </div>
                    <p className="text-sm font-medium text-muted-foreground">
                      {activeModel
                        ? `向 ${activeModel.displayName} 发起测试对话`
                        : "选择项目与模型开始调试"}
                    </p>
                  </div>
                ) : (
                  messages.map((msg) => {
                    const isUser = msg.role === "user";
                    const ttft = msg.ttftMs ?? 0;
                    const total = msg.totalMs ?? 0;
                    const genMs = Math.max(0, total - ttft);

                    return (
                      <div
                        key={msg.id}
                        className={cn(
                          "flex flex-col gap-2",
                          isUser ? "items-end" : "items-start",
                        )}
                      >
                        <div
                          className={cn(
                            "max-w-[88%] rounded-xl px-4 py-2.5 text-sm leading-relaxed",
                            isUser
                              ? "bg-primary text-primary-foreground"
                              : "w-full border border-border bg-card text-card-foreground",
                          )}
                        >
                          {msg.thinking ? (
                            <Collapsible className="mb-3 rounded-lg bg-muted/55 px-3 py-2">
                              <CollapsibleTrigger className="flex w-full items-center justify-between text-xs font-medium text-muted-foreground">
                                <span className="flex items-center gap-1.5">
                                  <Brain className="size-3.5" />
                                  思考过程
                                </span>
                                <ChevronDown className="size-3.5" />
                              </CollapsibleTrigger>
                              <CollapsibleContent className="mt-2 whitespace-pre-wrap text-xs leading-relaxed text-muted-foreground">
                                {msg.thinking}
                              </CollapsibleContent>
                            </Collapsible>
                          ) : null}

                          {isUser ? (
                            <p className="whitespace-pre-wrap">{msg.content}</p>
                          ) : (
                            <div className="prose prose-sm dark:prose-invert max-w-none break-words [&_pre]:overflow-x-auto [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_pre]:font-mono [&_pre]:text-xs">
                              {msg.content ? (
                                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                                  {msg.content}
                                </ReactMarkdown>
                              ) : msg.streaming ? (
                                <span className="text-xs text-muted-foreground">
                                  生成中…
                                </span>
                              ) : null}
                            </div>
                          )}
                        </div>

                        {/* Telemetry pill + latency micro-bar for assistant turns */}
                        {!isUser && !msg.streaming && (total > 0 || msg.usage) ? (
                          <div className="flex flex-wrap items-center gap-3 px-1 text-[11px] text-muted-foreground tabular-nums">
                            {total > 0 ? (
                              <div className="flex items-center gap-1.5">
                                <ProportionBar
                                  className="w-16"
                                  heightClass="h-1.5"
                                  total={total}
                                  segments={[
                                    {
                                      key: "ttft",
                                      label: "首字等待",
                                      value: ttft,
                                      displayValue: formatLatencyMs(ttft),
                                      colorClass: "bg-[var(--chart-3)]",
                                    },
                                    {
                                      key: "gen",
                                      label: "生成阶段",
                                      value: genMs,
                                      displayValue: formatLatencyMs(genMs),
                                      colorClass: "bg-[var(--chart-2)]",
                                    },
                                  ]}
                                />
                                <span>
                                  TTFT {formatLatencyMs(ttft)} · 总{" "}
                                  {formatLatencyMs(total)}
                                </span>
                              </div>
                            ) : null}
                            {msg.usage ? (
                              <span className="font-mono">
                                ↑{formatCompactNumber(msg.usage.promptTokens)}
                                {msg.usage.cachedReadTokens > 0
                                  ? ` ⚡${formatCompactNumber(msg.usage.cachedReadTokens)}`
                                  : ""}{" "}
                                ↓{formatCompactNumber(msg.usage.completionTokens)}
                              </span>
                            ) : null}
                            {msg.estimatedCostMicros !== undefined ? (
                              <Badge
                                variant="secondary"
                                className="h-4 px-1.5 font-mono text-[10px]"
                              >
                                ≈ {formatMicroUsd(msg.estimatedCostMicros)}
                              </Badge>
                            ) : null}
                          </div>
                        ) : null}
                      </div>
                    );
                  })
                )}
                <div ref={scrollEndRef} />
              </div>
            </div>

            {/* Floating Pill Composer */}
            <div className="pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-background via-background/95 to-transparent px-4 pt-6 pb-4 sm:px-6">
              <div className="squircle-lg pointer-events-auto mx-auto flex max-w-3xl items-end gap-2 rounded-2xl border border-border bg-card p-2 shadow-sm">
                <Textarea
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="输入消息…（Enter 发送，Shift+Enter 换行）"
                  className="max-h-36 min-h-9 flex-1 border-0 bg-transparent px-2 py-1.5 shadow-none focus-visible:ring-0 dark:bg-transparent"
                />
                {isStreaming ? (
                  <Button
                    type="button"
                    size="icon"
                    variant="destructive"
                    onClick={handleStop}
                    aria-label="停止生成"
                  >
                    <Square className="size-3.5" />
                  </Button>
                ) : (
                  <Button
                    type="button"
                    size="icon"
                    disabled={!draft.trim() || !activeProjectId || !activeModelCode}
                    onClick={handleSend}
                    aria-label="发送消息"
                  >
                    <ArrowUp className="size-4" />
                  </Button>
                )}
              </div>
            </div>
          </div>
        </ResizablePanel>

        {showConfig ? (
          <>
            <ResizableHandle withHandle />
            <ResizablePanel
              defaultSize="28%"
              minSize="20%"
              maxSize="42%"
              style={{ overflow: "hidden", height: "100%" }}
            >
              <aside className="flex h-full min-h-0 flex-col gap-5 overflow-y-auto bg-muted/20 p-4">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="pg-system">系统提示词</Label>
                  <Textarea
                    id="pg-system"
                    value={systemPrompt}
                    onChange={(e) => setSystemPrompt(e.target.value)}
                    placeholder="设定模型角色与输出约束…"
                    className="min-h-24 text-xs"
                  />
                </div>

                <Separator />

                <div className="flex flex-col gap-4">
                  <div className="flex flex-col gap-2">
                    <div className="flex items-center justify-between text-xs">
                      <Label>Temperature</Label>
                      <span className="font-mono tabular-nums">
                        {temperature.toFixed(2)}
                      </span>
                    </div>
                    <Slider
                      value={[temperature]}
                      min={0}
                      max={2}
                      step={0.05}
                      onValueChange={(v) =>
                        setTemperature(Array.isArray(v) ? v[0] : v)
                      }
                    />
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="pg-max-tokens">最大输出 Token</Label>
                    <Input
                      id="pg-max-tokens"
                      type="number"
                      min={1}
                      max={128000}
                      value={maxTokens}
                      onChange={(e) =>
                        setMaxTokens(Math.max(1, Number(e.target.value) || 1024))
                      }
                      className="font-mono tabular-nums"
                    />
                  </div>

                  <div className="flex items-center justify-between">
                    <Label htmlFor="pg-stream">流式输出 (SSE)</Label>
                    <Switch
                      id="pg-stream"
                      checked={streamEnabled}
                      onCheckedChange={setStreamEnabled}
                    />
                  </div>
                </div>

                <Separator />

                {/* Session Cumulative Telemetry */}
                <div className="flex flex-col gap-2.5 text-xs">
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">本次会话累计</span>
                    <span className="font-mono font-semibold tabular-nums">
                      {formatMicroUsd(sessionTotals.costMicros)}
                    </span>
                  </div>
                  <ProportionBar
                    heightClass="h-1.5"
                    segments={[
                      {
                        key: "prompt",
                        label: "常规输入",
                        value: Math.max(
                          0,
                          sessionTotals.prompt - sessionTotals.cached,
                        ),
                        displayValue: formatCompactNumber(
                          Math.max(
                            0,
                            sessionTotals.prompt - sessionTotals.cached,
                          ),
                        ),
                        colorClass: "bg-[var(--chart-1)]",
                      },
                      {
                        key: "cached",
                        label: "缓存读取",
                        value: sessionTotals.cached,
                        displayValue: formatCompactNumber(sessionTotals.cached),
                        colorClass: "bg-[var(--chart-2)]",
                      },
                      {
                        key: "completion",
                        label: "输出",
                        value: sessionTotals.completion,
                        displayValue: formatCompactNumber(
                          sessionTotals.completion,
                        ),
                        colorClass: "bg-[var(--chart-3)]",
                      },
                      {
                        key: "reasoning",
                        label: "思考链",
                        value: sessionTotals.reasoning,
                        displayValue: formatCompactNumber(
                          sessionTotals.reasoning,
                        ),
                        colorClass: "bg-[var(--chart-4)]",
                      },
                    ]}
                  />
                  <div className="grid grid-cols-2 gap-y-1 text-[11px] text-muted-foreground tabular-nums">
                    <span>输入 {formatCompactNumber(sessionTotals.prompt)}</span>
                    <span className="text-right">
                      输出 {formatCompactNumber(sessionTotals.completion)}
                    </span>
                    <span>缓存 {formatCompactNumber(sessionTotals.cached)}</span>
                    <span className="text-right">
                      思考 {formatCompactNumber(sessionTotals.reasoning)}
                    </span>
                  </div>
                </div>
              </aside>
            </ResizablePanel>
          </>
        ) : null}
      </ResizablePanelGroup>

      {/* Code Export Dialog */}
      <Dialog open={codeDialogOpen} onOpenChange={setCodeDialogOpen}>
        <DialogContent className="sm:max-w-xl">
          <DialogHeader>
            <DialogTitle className="flex items-center justify-between pr-6">
              <span>导出请求代码</span>
              <CopyButton
                value={exportedSnippet}
                label="复制"
                variant="outline"
                copiedToast="已复制 cURL 命令"
              />
            </DialogTitle>
          </DialogHeader>
          <pre className="max-h-80 overflow-auto rounded-lg border border-border bg-muted/45 p-3.5 font-mono text-xs leading-relaxed">
            <code>{exportedSnippet}</code>
          </pre>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default function PlaygroundPage() {
  return (
    <Suspense
      fallback={
        <div className="flex h-full flex-1 flex-col gap-3 p-4">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="flex-1 w-full" />
        </div>
      }
    >
      <PlaygroundContent />
    </Suspense>
  );
}
