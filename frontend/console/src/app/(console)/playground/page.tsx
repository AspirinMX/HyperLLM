"use client";

import {
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type ClipboardEvent,
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
  Columns2,
  ImagePlus,
  PanelRight,
  Square,
  Terminal,
  Trash2,
  X,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
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
  buildProtocolCodeSnippet,
  PLAYGROUND_PROTOCOL_META,
  runPlaygroundCompletion,
  type CodeSnippetLanguage,
  type PlaygroundImageAttachment,
  type PlaygroundProtocol,
  type StreamUsageStats,
  type ThinkingEffort,
} from "@/lib/api/sse";
import type { PublicModelCatalogDto } from "@/lib/api/types";
import { GATEWAY_PUBLIC_BASE_URL } from "@/lib/config";
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
  images?: PlaygroundImageAttachment[];
  thinking?: string;
  streaming?: boolean;
  ttftMs?: number | null;
  totalMs?: number;
  usage?: StreamUsageStats;
  estimatedCostMicros?: number;
  error?: unknown;
}

interface SandboxTokenCache {
  projectId: string;
  token: string;
  expiresAtMs: number;
}

const MAX_IMAGES_PER_TURN = 4;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const ALLOWED_IMAGE_MIME = new Set([
  "image/png",
  "image/jpeg",
  "image/webp",
  "image/gif",
]);

function defaultProtocolForModel(
  model: PublicModelCatalogDto | undefined,
): PlaygroundProtocol {
  if (!model) return "OPENAI_CHAT";
  if (model.nativeProtocol === "ANTHROPIC") return "ANTHROPIC_MESSAGES";
  if (model.nativeProtocol === "GEMINI") return "GEMINI_GENERATE_CONTENT";
  return "OPENAI_CHAT";
}

function estimateCostMicros(
  model: PublicModelCatalogDto | null | undefined,
  usage: StreamUsageStats,
): number {
  const p = model?.pricing;
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
}

function readFileAsAttachment(file: File): Promise<PlaygroundImageAttachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result ?? "");
      const commaIdx = dataUrl.indexOf(",");
      const base64 = commaIdx >= 0 ? dataUrl.slice(commaIdx + 1) : "";
      resolve({
        id: `img-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        name: file.name || "pasted-image",
        mimeType: file.type || "image/png",
        dataUrl,
        base64,
      });
    };
    reader.onerror = () => reject(new Error("读取图片失败"));
    reader.readAsDataURL(file);
  });
}

function MessageTurnView({ msg }: { msg: TurnMessage }) {
  const isUser = msg.role === "user";
  const ttft = msg.ttftMs ?? 0;
  const total = msg.totalMs ?? 0;
  const genMs = Math.max(0, total - ttft);

  return (
    <div
      className={cn(
        "flex flex-col gap-2",
        isUser ? "items-end" : "items-start",
      )}
    >
      <div
        className={cn(
          "max-w-[92%] rounded-xl px-4 py-2.5 text-sm leading-relaxed",
          isUser
            ? "bg-primary text-primary-foreground"
            : "w-full border border-border bg-card text-card-foreground",
        )}
      >
        {msg.error ? <ApiErrorAlert error={msg.error} /> : null}

        {msg.images && msg.images.length > 0 ? (
          <div className="mb-2 flex flex-wrap gap-2">
            {msg.images.map((img) => (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                key={img.id}
                src={img.dataUrl}
                alt={img.name}
                className="h-24 max-w-44 rounded-lg border border-border/50 object-cover"
              />
            ))}
          </div>
        ) : null}

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
          msg.content ? (
            <p className="whitespace-pre-wrap">{msg.content}</p>
          ) : null
        ) : (
          <div className="prose prose-sm dark:prose-invert max-w-none break-words [&_pre]:overflow-x-auto [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_pre]:font-mono [&_pre]:text-xs">
            {msg.content ? (
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {msg.content}
              </ReactMarkdown>
            ) : msg.streaming ? (
              <span className="text-xs text-muted-foreground">生成中…</span>
            ) : null}
          </div>
        )}
      </div>

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
                TTFT {formatLatencyMs(ttft)} · 总 {formatLatencyMs(total)}
              </span>
            </div>
          ) : null}
          {msg.usage ? (
            <span className="font-mono">
              ↑{formatCompactNumber(msg.usage.promptTokens)}
              {msg.usage.cachedReadTokens > 0
                ? ` ⚡${formatCompactNumber(msg.usage.cachedReadTokens)}`
                : ""}
              {msg.usage.cachedWriteTokens > 0
                ? ` +⚡${formatCompactNumber(msg.usage.cachedWriteTokens)}`
                : ""}{" "}
              ↓{formatCompactNumber(msg.usage.completionTokens)}
              {msg.usage.reasoningTokens > 0
                ? ` 💭${formatCompactNumber(msg.usage.reasoningTokens)}`
                : ""}
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
}

function ProtocolSelect({
  value,
  onChange,
  compact = false,
}: {
  value: PlaygroundProtocol;
  onChange: (next: PlaygroundProtocol) => void;
  compact?: boolean;
}) {
  return (
    <Select
      value={value}
      onValueChange={(v) => {
        if (v) onChange(v as PlaygroundProtocol);
      }}
    >
      <SelectTrigger
        size="sm"
        className={cn(compact ? "w-32 text-xs" : "w-36 text-xs")}
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {(Object.keys(PLAYGROUND_PROTOCOL_META) as PlaygroundProtocol[]).map(
          (key) => (
            <SelectItem key={key} value={key}>
              {PLAYGROUND_PROTOCOL_META[key].shortLabel}
            </SelectItem>
          ),
        )}
      </SelectContent>
    </Select>
  );
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

  const [arenaMode, setArenaMode] = useState(false);
  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [selectedModelCode, setSelectedModelCode] = useState(queryModel);
  const [protocol, setProtocol] = useState<PlaygroundProtocol>("OPENAI_CHAT");

  // Arena right-pane model + protocol
  const [arenaModelCode, setArenaModelCode] = useState("");
  const [arenaProtocol, setArenaProtocol] =
    useState<PlaygroundProtocol>("ANTHROPIC_MESSAGES");

  const [systemPrompt, setSystemPrompt] = useState("");
  const [temperature, setTemperature] = useState(0.7);
  const [maxTokens, setMaxTokens] = useState(2048);
  const [thinkingEffort, setThinkingEffort] = useState<ThinkingEffort>("off");
  const [streamEnabled, setStreamEnabled] = useState(true);
  const [showConfig, setShowConfig] = useState(true);
  const [codeDialogOpen, setCodeDialogOpen] = useState(false);
  const [snippetLang, setSnippetLang] = useState<CodeSnippetLanguage>("curl");

  const [messagesA, setMessagesA] = useState<TurnMessage[]>([]);
  const [messagesB, setMessagesB] = useState<TurnMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [pendingImages, setPendingImages] = useState<
    PlaygroundImageAttachment[]
  >([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const tokenCacheRef = useRef<SandboxTokenCache | null>(null);
  const [tokenReady, setTokenReady] = useState(false);
  const abortControllersRef = useRef<AbortController[]>([]);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const scrollEndARef = useRef<HTMLDivElement | null>(null);
  const scrollEndBRef = useRef<HTMLDivElement | null>(null);

  const models = useMemo(() => modelsState.data ?? [], [modelsState.data]);

  const activeProjectId = useMemo(() => {
    const list = projectsState.data?.items ?? [];
    if (selectedProjectId && list.some((p) => p.id === selectedProjectId)) {
      return selectedProjectId;
    }
    return list[0]?.id ?? "";
  }, [projectsState.data, selectedProjectId]);

  const activeModel = useMemo(() => {
    return (
      models.find((m) => m.modelCode === selectedModelCode) ??
      models.find((m) => m.modelCode === queryModel) ??
      models[0] ??
      null
    );
  }, [models, selectedModelCode, queryModel]);

  const activeModelCode = activeModel?.modelCode ?? selectedModelCode;

  const activeArenaModel = useMemo(() => {
    if (arenaModelCode) {
      const found = models.find((m) => m.modelCode === arenaModelCode);
      if (found) return found;
    }
    return (
      models.find((m) => m.modelCode !== activeModelCode) ??
      activeModel ??
      null
    );
  }, [models, arenaModelCode, activeModelCode, activeModel]);

  const activeArenaModelCode =
    activeArenaModel?.modelCode ?? arenaModelCode ?? activeModelCode;

  useEffect(() => {
    scrollEndARef.current?.scrollIntoView({ behavior: "auto" });
    scrollEndBRef.current?.scrollIntoView({ behavior: "auto" });
  }, [messagesA.length, messagesB.length, isStreaming]);

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

  async function addImageFiles(files: File[]) {
    const remaining = MAX_IMAGES_PER_TURN - pendingImages.length;
    if (remaining <= 0) {
      toast.error(`单轮最多附加 ${MAX_IMAGES_PER_TURN} 张图片`);
      return;
    }
    const next: PlaygroundImageAttachment[] = [];
    for (const file of files.slice(0, remaining)) {
      if (!ALLOWED_IMAGE_MIME.has(file.type)) {
        toast.error(`不支持的图片格式：${file.type || file.name}`);
        continue;
      }
      if (file.size > MAX_IMAGE_BYTES) {
        toast.error(`图片「${file.name}」超过 5MB 上限`);
        continue;
      }
      try {
        next.push(await readFileAsAttachment(file));
      } catch {
        toast.error(`读取图片「${file.name}」失败`);
      }
    }
    if (next.length > 0) {
      setPendingImages((prev) => [...prev, ...next]);
    }
  }

  function handleFileInputChange(e: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files ?? []);
    e.target.value = "";
    if (files.length > 0) {
      void addImageFiles(files);
    }
  }

  function handlePaste(e: ClipboardEvent<HTMLTextAreaElement>) {
    const items = Array.from(e.clipboardData?.items ?? []);
    const files: File[] = [];
    for (const item of items) {
      if (item.kind === "file" && ALLOWED_IMAGE_MIME.has(item.type)) {
        const file = item.getAsFile();
        if (file) files.push(file);
      }
    }
    if (files.length > 0) {
      e.preventDefault();
      void addImageFiles(files);
    }
  }

  async function runLane(params: {
    sandboxToken: string;
    laneModel: PublicModelCatalogDto | null;
    laneModelCode: string;
    laneProtocol: PlaygroundProtocol;
    priorMessages: TurnMessage[];
    userMsg: TurnMessage;
    setMessages: React.Dispatch<React.SetStateAction<TurnMessage[]>>;
    controller: AbortController;
  }) {
    const assistantId = `a-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    const placeholder: TurnMessage = {
      id: assistantId,
      role: "assistant",
      content: "",
      thinking: "",
      streaming: true,
    };

    const history = [...params.priorMessages, params.userMsg].map((m) => ({
      role: m.role,
      content: m.content,
      images: m.images,
    }));

    params.setMessages((prev) => [...prev, params.userMsg, placeholder]);

    try {
      const result = await runPlaygroundCompletion({
        protocol: params.laneProtocol,
        sandboxToken: params.sandboxToken,
        model: params.laneModelCode,
        systemPrompt,
        messages: history,
        temperature,
        maxTokens,
        thinkingEffort,
        stream: streamEnabled,
        signal: params.controller.signal,
        onTextDelta: (chunk) => {
          params.setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId ? { ...m, content: m.content + chunk } : m,
            ),
          );
        },
        onThinkingDelta: (chunk) => {
          params.setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId
                ? { ...m, thinking: (m.thinking ?? "") + chunk }
                : m,
            ),
          );
        },
        onTtft: (ttftMs) => {
          params.setMessages((prev) =>
            prev.map((m) => (m.id === assistantId ? { ...m, ttftMs } : m)),
          );
        },
        onUsage: (usage) => {
          const cost = estimateCostMicros(params.laneModel, usage);
          params.setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId
                ? { ...m, usage, estimatedCostMicros: cost }
                : m,
            ),
          );
        },
      });

      const cost = estimateCostMicros(params.laneModel, result.usage);
      params.setMessages((prev) =>
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
                estimatedCostMicros: cost,
              }
            : m,
        ),
      );
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        params.setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId ? { ...m, streaming: false } : m,
          ),
        );
      } else {
        params.setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? { ...m, streaming: false, error: err }
              : m,
          ),
        );
      }
    }
  }

  async function handleSend() {
    const trimmed = draft.trim();
    if ((!trimmed && pendingImages.length === 0) || isStreaming) return;
    if (!activeProjectId || !activeModelCode) {
      toast.error("请先选择项目与模型");
      return;
    }

    setError(null);
    const turnImages = pendingImages;
    setDraft("");
    setPendingImages([]);

    const userMsg: TurnMessage = {
      id: `u-${Date.now()}`,
      role: "user",
      content: trimmed,
      images: turnImages.length > 0 ? turnImages : undefined,
    };

    setIsStreaming(true);
    try {
      const sandboxToken = await ensureSandboxToken();
      const ctrlA = new AbortController();
      const controllers = [ctrlA];
      const tasks: Promise<void>[] = [
        runLane({
          sandboxToken,
          laneModel: activeModel,
          laneModelCode: activeModelCode,
          laneProtocol: protocol,
          priorMessages: messagesA,
          userMsg,
          setMessages: setMessagesA,
          controller: ctrlA,
        }),
      ];

      if (arenaMode && activeArenaModelCode) {
        const ctrlB = new AbortController();
        controllers.push(ctrlB);
        tasks.push(
          runLane({
            sandboxToken,
            laneModel: activeArenaModel,
            laneModelCode: activeArenaModelCode,
            laneProtocol: arenaProtocol,
            priorMessages: messagesB,
            userMsg: { ...userMsg, id: `${userMsg.id}-b` },
            setMessages: setMessagesB,
            controller: ctrlB,
          }),
        );
      }

      abortControllersRef.current = controllers;
      await Promise.allSettled(tasks);
    } catch (err) {
      setError(err);
    } finally {
      setIsStreaming(false);
      abortControllersRef.current = [];
    }
  }

  function handleStop() {
    for (const ctrl of abortControllersRef.current) {
      ctrl.abort();
    }
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void handleSend();
    }
  }

  const sessionTotals = useMemo(() => {
    const all = arenaMode ? [...messagesA, ...messagesB] : messagesA;
    return all.reduce(
      (acc, m) => {
        if (m.role !== "assistant") return acc;
        acc.prompt += m.usage?.promptTokens ?? 0;
        acc.cachedRead += m.usage?.cachedReadTokens ?? 0;
        acc.cachedWrite += m.usage?.cachedWriteTokens ?? 0;
        acc.completion += m.usage?.completionTokens ?? 0;
        acc.reasoning += m.usage?.reasoningTokens ?? 0;
        acc.costMicros += m.estimatedCostMicros ?? 0;
        return acc;
      },
      {
        prompt: 0,
        cachedRead: 0,
        cachedWrite: 0,
        completion: 0,
        reasoning: 0,
        costMicros: 0,
      },
    );
  }, [messagesA, messagesB, arenaMode]);

  const exportedSnippet = useMemo(() => {
    const sampleMessages = messagesA
      .filter((m) => m.content.trim().length > 0)
      .map((m) => ({ role: m.role, content: m.content }));
    return buildProtocolCodeSnippet({
      baseUrl: GATEWAY_PUBLIC_BASE_URL,
      protocol,
      language: snippetLang,
      model: activeModelCode || "gpt-4o",
      systemPrompt,
      messages: sampleMessages,
      temperature,
      maxTokens,
      stream: streamEnabled,
    });
  }, [
    protocol,
    snippetLang,
    activeModelCode,
    systemPrompt,
    messagesA,
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
            <SelectTrigger size="sm" className="w-32 sm:w-36">
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
              const target = models.find((m) => m.modelCode === code);
              setProtocol(defaultProtocolForModel(target));
            }}
          >
            <SelectTrigger size="sm" className="w-44 font-mono text-xs">
              <SelectValue placeholder="选择模型" />
            </SelectTrigger>
            <SelectContent>
              {models.map((m) => (
                <SelectItem key={m.id} value={m.modelCode}>
                  {m.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <ProtocolSelect value={protocol} onChange={setProtocol} />

          <Button
            type="button"
            variant={arenaMode ? "secondary" : "outline"}
            size="xs"
            disabled={isStreaming}
            onClick={() => setArenaMode((v) => !v)}
            className="hidden md:inline-flex"
          >
            <Columns2 className="size-3.5" />
            <span>{arenaMode ? "退出对比" : "双模型对比"}</span>
          </Button>
        </div>

        <div className="flex items-center gap-1.5">
          <StatusDot
            tone={tokenReady ? "success" : "muted"}
            label={tokenReady ? "沙盒就绪" : "待签发"}
            className="mr-1 hidden lg:inline-flex"
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
            disabled={
              (messagesA.length === 0 && messagesB.length === 0) || isStreaming
            }
            onClick={() => {
              setMessagesA([]);
              setMessagesB([]);
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
            {error ? (
              <div className="px-4 pt-3 sm:px-6">
                <ApiErrorAlert error={error} />
              </div>
            ) : null}

            {arenaMode ? (
              <div className="grid min-h-0 flex-1 grid-cols-1 divide-y divide-border md:grid-cols-2 md:divide-x md:divide-y-0">
                {/* Lane A */}
                <div className="flex min-h-0 flex-col overflow-hidden">
                  <div className="flex h-10 shrink-0 items-center justify-between border-b border-border bg-muted/25 px-3 text-xs">
                    <div className="flex items-center gap-2 truncate">
                      <Badge variant="outline" className="h-5 px-1.5 text-[10px]">
                        A
                      </Badge>
                      <span className="truncate font-mono font-medium">
                        {activeModel?.displayName ?? activeModelCode}
                      </span>
                    </div>
                    <Badge variant="secondary" className="text-[10px]">
                      {PLAYGROUND_PROTOCOL_META[protocol].shortLabel}
                    </Badge>
                  </div>
                  <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-4 pb-40">
                    <div className="flex flex-col gap-4">
                      {messagesA.length === 0 ? (
                        <p className="py-16 text-center text-xs text-muted-foreground">
                          发送消息同时对比两路模型与协议表现
                        </p>
                      ) : (
                        messagesA.map((msg) => (
                          <MessageTurnView key={msg.id} msg={msg} />
                        ))
                      )}
                      <div ref={scrollEndARef} />
                    </div>
                  </div>
                </div>

                {/* Lane B */}
                <div className="flex min-h-0 flex-col overflow-hidden">
                  <div className="flex h-10 shrink-0 items-center justify-between gap-2 border-b border-border bg-muted/25 px-3 text-xs">
                    <div className="flex min-w-0 items-center gap-1.5">
                      <Badge variant="outline" className="h-5 px-1.5 text-[10px]">
                        B
                      </Badge>
                      <Select
                        value={activeArenaModelCode}
                        onValueChange={(v) => {
                          const code = v ?? "";
                          setArenaModelCode(code);
                          const target = models.find(
                            (m) => m.modelCode === code,
                          );
                          setArenaProtocol(defaultProtocolForModel(target));
                        }}
                      >
                        <SelectTrigger
                          size="sm"
                          className="h-7 w-40 font-mono text-xs"
                        >
                          <SelectValue placeholder="对比模型" />
                        </SelectTrigger>
                        <SelectContent>
                          {models.map((m) => (
                            <SelectItem key={m.id} value={m.modelCode}>
                              {m.displayName}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <ProtocolSelect
                      value={arenaProtocol}
                      onChange={setArenaProtocol}
                      compact
                    />
                  </div>
                  <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-4 pb-40">
                    <div className="flex flex-col gap-4">
                      {messagesB.length === 0 ? (
                        <p className="py-16 text-center text-xs text-muted-foreground">
                          选择右侧模型与协议，支持跨协议实时对照
                        </p>
                      ) : (
                        messagesB.map((msg) => (
                          <MessageTurnView key={msg.id} msg={msg} />
                        ))
                      )}
                      <div ref={scrollEndBRef} />
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-5 pb-40 sm:px-6">
                <div className="mx-auto flex max-w-3xl flex-col gap-5">
                  {messagesA.length === 0 ? (
                    <div className="flex flex-col items-center justify-center gap-3 py-24 text-center">
                      <div className="flex size-10 items-center justify-center rounded-xl bg-muted text-muted-foreground">
                        <Terminal className="size-4" />
                      </div>
                      <p className="text-sm font-medium text-muted-foreground">
                        {activeModel
                          ? `使用 ${PLAYGROUND_PROTOCOL_META[protocol].label} 向 ${activeModel.displayName} 发起对话`
                          : "选择项目与模型开始调试"}
                      </p>
                      <p className="text-xs text-muted-foreground/80">
                        支持四协议双向实时转译、思考链输出与多模态图片粘贴/上传
                      </p>
                    </div>
                  ) : (
                    messagesA.map((msg) => (
                      <MessageTurnView key={msg.id} msg={msg} />
                    ))
                  )}
                  <div ref={scrollEndARef} />
                </div>
              </div>
            )}

            {/* Floating Pill Composer with Multimodal Image Support */}
            <div className="pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-background via-background/95 to-transparent px-4 pt-6 pb-4 sm:px-6">
              <div className="squircle-lg pointer-events-auto mx-auto flex max-w-3xl flex-col gap-2 rounded-2xl border border-border bg-card p-2 shadow-sm">
                {pendingImages.length > 0 ? (
                  <div className="flex flex-wrap gap-2 px-1 pt-1">
                    {pendingImages.map((img) => (
                      <div
                        key={img.id}
                        className="group relative overflow-hidden rounded-lg border border-border"
                      >
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={img.dataUrl}
                          alt={img.name}
                          className="size-14 object-cover"
                        />
                        <button
                          type="button"
                          onClick={() =>
                            setPendingImages((prev) =>
                              prev.filter((x) => x.id !== img.id),
                            )
                          }
                          aria-label={`移除图片 ${img.name}`}
                          className="absolute top-0.5 right-0.5 flex size-4 items-center justify-center rounded-full bg-background/85 text-foreground shadow-sm"
                        >
                          <X className="size-3" />
                        </button>
                      </div>
                    ))}
                  </div>
                ) : null}

                <div className="flex items-end gap-2">
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/png,image/jpeg,image/webp,image/gif"
                    multiple
                    onChange={handleFileInputChange}
                    className="hidden"
                  />
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    disabled={
                      isStreaming || pendingImages.length >= MAX_IMAGES_PER_TURN
                    }
                    onClick={() => fileInputRef.current?.click()}
                    aria-label="添加图片"
                    title="上传图片（也可直接粘贴，最多 4 张，每张 ≤ 5MB）"
                  >
                    <ImagePlus className="size-4" />
                  </Button>
                  <Textarea
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onKeyDown={handleKeyDown}
                    onPaste={handlePaste}
                    placeholder="输入消息或粘贴图片…（Enter 发送，Shift+Enter 换行）"
                    className="max-h-36 min-h-9 flex-1 border-0 bg-transparent px-1 py-1.5 shadow-none focus-visible:ring-0 dark:bg-transparent"
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
                      disabled={
                        (!draft.trim() && pendingImages.length === 0) ||
                        !activeProjectId ||
                        !activeModelCode
                      }
                      onClick={handleSend}
                      aria-label="发送消息"
                    >
                      <ArrowUp className="size-4" />
                    </Button>
                  )}
                </div>
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
                        setMaxTokens(
                          Math.max(1, Number(e.target.value) || 1024),
                        )
                      }
                      className="font-mono tabular-nums"
                    />
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <Label>深度思考 / 推理强度</Label>
                    <Select
                      value={thinkingEffort}
                      onValueChange={(v) => {
                        if (v) setThinkingEffort(v as ThinkingEffort);
                      }}
                    >
                      <SelectTrigger size="sm" className="text-xs">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="off">关闭（默认）</SelectItem>
                        <SelectItem value="low">低（1K budget / low）</SelectItem>
                        <SelectItem value="medium">
                          中（4K budget / medium）
                        </SelectItem>
                        <SelectItem value="high">
                          高（8K budget / high）
                        </SelectItem>
                      </SelectContent>
                    </Select>
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

                {/* Session Cumulative Telemetry (strictly disjoint 5 buckets) */}
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
                        value: sessionTotals.prompt,
                        displayValue: formatCompactNumber(sessionTotals.prompt),
                        colorClass: "bg-[var(--chart-1)]",
                      },
                      {
                        key: "cachedRead",
                        label: "缓存读取",
                        value: sessionTotals.cachedRead,
                        displayValue: formatCompactNumber(
                          sessionTotals.cachedRead,
                        ),
                        colorClass: "bg-[var(--chart-2)]",
                      },
                      {
                        key: "cachedWrite",
                        label: "缓存写入",
                        value: sessionTotals.cachedWrite,
                        displayValue: formatCompactNumber(
                          sessionTotals.cachedWrite,
                        ),
                        colorClass: "bg-[var(--chart-5)]",
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
                    <span>
                      缓存读 {formatCompactNumber(sessionTotals.cachedRead)}
                    </span>
                    <span className="text-right">
                      思考 {formatCompactNumber(sessionTotals.reasoning)}
                    </span>
                    {sessionTotals.cachedWrite > 0 ? (
                      <span>
                        缓存写 {formatCompactNumber(sessionTotals.cachedWrite)}
                      </span>
                    ) : null}
                  </div>
                </div>
              </aside>
            </ResizablePanel>
          </>
        ) : null}
      </ResizablePanelGroup>

      {/* Multi-language & 4-Protocol Code Export Dialog */}
      <Dialog open={codeDialogOpen} onOpenChange={setCodeDialogOpen}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle className="flex items-center justify-between pr-6">
              <span>
                导出请求代码 · {PLAYGROUND_PROTOCOL_META[protocol].shortLabel}
              </span>
              <CopyButton
                value={exportedSnippet}
                label="复制"
                variant="outline"
                copiedToast="已复制请求代码"
              />
            </DialogTitle>
          </DialogHeader>
          <Tabs
            value={snippetLang}
            onValueChange={(v) => setSnippetLang(v as CodeSnippetLanguage)}
            className="gap-3"
          >
            <TabsList>
              <TabsTrigger value="curl">cURL</TabsTrigger>
              <TabsTrigger value="python">Python</TabsTrigger>
              <TabsTrigger value="typescript">TypeScript</TabsTrigger>
            </TabsList>
            <TabsContent value={snippetLang} className="m-0">
              <pre className="max-h-96 overflow-auto rounded-lg border border-border bg-muted/45 p-3.5 font-mono text-xs leading-relaxed">
                <code>{exportedSnippet}</code>
              </pre>
            </TabsContent>
          </Tabs>
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
