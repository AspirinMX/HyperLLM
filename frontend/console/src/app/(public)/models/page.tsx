"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import {
  Brain,
  Calculator,
  Database,
  Eye,
  Lock,
  Play,
  Search,
  Wrench,
} from "lucide-react";
import { Bar, BarChart, XAxis, YAxis } from "recharts";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Slider } from "@/components/ui/slider";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { CopyButton } from "@/components/common/copy-button";
import { EmptyState } from "@/components/common/empty-state";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { catalogApi } from "@/lib/api/endpoints";
import { isApiErrorCode } from "@/lib/api/error";
import type { ProtocolFamily, PublicModelCatalogDto } from "@/lib/api/types";
import {
  ceilTokenCostMicros,
  formatCompactNumber,
  formatMicroUsd,
  formatPerMillionUsd,
} from "@/lib/format/micro-usd";
import { CAPABILITY_LABEL, PROTOCOL_LABEL } from "@/lib/format/labels";
import { cn } from "@/lib/utils";

type CapabilityFilter = "tools" | "vision" | "thinking" | "cache";

const COST_CHART_CONFIG: ChartConfig = {
  input: { label: "常规输入", color: "var(--chart-1)" },
  cacheRead: { label: "缓存读取", color: "var(--chart-2)" },
  output: { label: "输出", color: "var(--chart-3)" },
  reasoning: { label: "思考链", color: "var(--chart-4)" },
};

export default function ModelsPage() {
  const { status } = useConsoleAuth();
  const isAuthed = status === "authenticated";

  const { data: models, loading, error, reload } = useAsyncData(
    (signal) => catalogApi.listModels({ auth: isAuthed, signal }),
    [isAuthed],
    status !== "loading",
  );

  const [query, setQuery] = useState("");
  const [protocol, setProtocol] = useState<"ALL" | ProtocolFamily>("ALL");
  const [caps, setCaps] = useState<Set<CapabilityFilter>>(new Set());

  const [calcOpen, setCalcOpen] = useState(false);
  const [calcModelCode, setCalcModelCode] = useState<string>("");
  const [inputK, setInputK] = useState(50);
  const [cacheK, setCacheK] = useState(20);
  const [outputK, setOutputK] = useState(10);
  const [reasoningK, setReasoningK] = useState(5);

  const toggleCap = (cap: CapabilityFilter) => {
    setCaps((prev) => {
      const next = new Set(prev);
      if (next.has(cap)) next.delete(cap);
      else next.add(cap);
      return next;
    });
  };

  const filtered = useMemo(() => {
    const list = models ?? [];
    const q = query.trim().toLowerCase();
    return list.filter((m) => {
      if (protocol !== "ALL" && m.nativeProtocol !== protocol) return false;
      if (caps.has("tools") && !m.supportsTools) return false;
      if (caps.has("vision") && !m.supportsVision) return false;
      if (caps.has("thinking") && !m.supportsThinking) return false;
      if (caps.has("cache") && !m.supportsPromptCache) return false;
      if (q) {
        const hay = `${m.displayName} ${m.modelCode} ${m.providerBadge ?? ""}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [models, query, protocol, caps]);

  const maxInputPrice = useMemo(
    () => Math.max(1, ...filtered.map((m) => m.pricing?.inputPriceMicros1m ?? 0)),
    [filtered],
  );
  const maxOutputPrice = useMemo(
    () => Math.max(1, ...filtered.map((m) => m.pricing?.outputPriceMicros1m ?? 0)),
    [filtered],
  );

  const selectedModel: PublicModelCatalogDto | undefined = useMemo(() => {
    const list = models ?? [];
    return list.find((m) => m.modelCode === calcModelCode) ?? list[0];
  }, [models, calcModelCode]);

  const estimate = useMemo(() => {
    const p = selectedModel?.pricing;
    if (!p) {
      return {
        inputMicros: 0,
        cacheMicros: 0,
        outputMicros: 0,
        reasoningMicros: 0,
        totalMicros: 0,
        savedMicros: 0,
      };
    }
    const inputMicros = ceilTokenCostMicros(inputK * 1000, p.inputPriceMicros1m);
    const cacheMicros = ceilTokenCostMicros(cacheK * 1000, p.cacheReadPriceMicros1m);
    const outputMicros = ceilTokenCostMicros(outputK * 1000, p.outputPriceMicros1m);
    const reasoningMicros = ceilTokenCostMicros(
      reasoningK * 1000,
      p.reasoningPriceMicros1m > 0 ? p.reasoningPriceMicros1m : p.outputPriceMicros1m,
    );
    const uncachedEquiv = ceilTokenCostMicros(cacheK * 1000, p.inputPriceMicros1m);
    const savedMicros = Math.max(0, uncachedEquiv - cacheMicros);
    return {
      inputMicros,
      cacheMicros,
      outputMicros,
      reasoningMicros,
      totalMicros: inputMicros + cacheMicros + outputMicros + reasoningMicros,
      savedMicros,
    };
  }, [selectedModel, inputK, cacheK, outputK, reasoningK]);

  const chartData = useMemo(
    () => [
      {
        name: "成本构成",
        input: estimate.inputMicros / 1_000_000,
        cacheRead: estimate.cacheMicros / 1_000_000,
        output: estimate.outputMicros / 1_000_000,
        reasoning: estimate.reasoningMicros / 1_000_000,
      },
    ],
    [estimate],
  );

  const needsAuth =
    isApiErrorCode(error, "AUTH_INVALID_CREDENTIALS") ||
    isApiErrorCode(error, "AUTH_TOKEN_EXPIRED");

  const openCalculatorFor = (code: string) => {
    setCalcModelCode(code);
    setCalcOpen(true);
  };

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-5 px-4 py-8 sm:px-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <h1 className="text-lg font-semibold tracking-tight">模型广场</h1>
          {models ? (
            <Badge variant="secondary" className="tabular-nums">
              {filtered.length}
            </Badge>
          ) : null}
        </div>
        <Button
          variant="outline"
          size="sm"
          disabled={!models || models.length === 0}
          onClick={() => setCalcOpen(true)}
        >
          <Calculator className="size-3.5" />
          <span>费用试算</span>
        </Button>
      </div>

      {/* Filter toolbar */}
      <div className="flex flex-wrap items-center gap-2.5">
        <div className="relative w-full sm:w-64">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索模型名或厂商"
            className="pl-8"
          />
        </div>

        <Tabs
          value={protocol}
          onValueChange={(v) => setProtocol(v as "ALL" | ProtocolFamily)}
        >
          <TabsList>
            <TabsTrigger value="ALL">全部</TabsTrigger>
            <TabsTrigger value="OPENAI">OpenAI</TabsTrigger>
            <TabsTrigger value="ANTHROPIC">Anthropic</TabsTrigger>
          </TabsList>
        </Tabs>

        <div className="flex flex-wrap items-center gap-1.5">
          {(
            [
              { key: "tools", label: "工具", icon: Wrench },
              { key: "vision", label: "视觉", icon: Eye },
              { key: "thinking", label: "推理", icon: Brain },
              { key: "cache", label: "缓存", icon: Database },
            ] as const
          ).map(({ key, label, icon: Icon }) => {
            const active = caps.has(key);
            return (
              <Button
                key={key}
                type="button"
                variant={active ? "secondary" : "outline"}
                size="sm"
                onClick={() => toggleCap(key)}
                className={cn(active && "border-foreground/25")}
              >
                <Icon className="size-3.5" />
                <span>{label}</span>
              </Button>
            );
          })}
        </div>
      </div>

      {error && !needsAuth ? (
        <ApiErrorAlert error={error} onRetry={reload} />
      ) : null}

      {/* Catalog table */}
      <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        {loading ? (
          <div className="flex flex-col gap-3 p-5">
            {Array.from({ length: 6 }).map((_, idx) => (
              <Skeleton key={idx} className="h-10 w-full" />
            ))}
          </div>
        ) : needsAuth ? (
          <EmptyState
            icon={<Lock className="size-4" />}
            title="登录后查看全部模型与实时定价"
            action={
              <Button size="sm" render={<Link href="/login?redirect=/models" />}>
                立即登录
              </Button>
            }
          />
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={<Search className="size-4" />}
            title="没有匹配的模型"
            action={
              query || protocol !== "ALL" || caps.size > 0 ? (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setQuery("");
                    setProtocol("ALL");
                    setCaps(new Set());
                  }}
                >
                  清除筛选
                </Button>
              ) : null
            }
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/35 hover:bg-muted/35">
                <TableHead>模型</TableHead>
                <TableHead>协议 / 类型</TableHead>
                <TableHead>能力</TableHead>
                <TableHead className="text-right">上下文</TableHead>
                <TableHead className="w-36 text-right">输入 / 1M</TableHead>
                <TableHead className="w-36 text-right">输出 / 1M</TableHead>
                <TableHead className="text-right">缓存读 / 1M</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((model) => {
                const inMicros = model.pricing?.inputPriceMicros1m ?? 0;
                const outMicros = model.pricing?.outputPriceMicros1m ?? 0;
                const inPct = Math.min(100, Math.round((inMicros / maxInputPrice) * 100));
                const outPct = Math.min(100, Math.round((outMicros / maxOutputPrice) * 100));

                return (
                  <TableRow key={model.id}>
                    <TableCell>
                      <div className="flex flex-col gap-0.5">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{model.displayName}</span>
                          {model.providerBadge ? (
                            <Badge variant="secondary" className="text-[11px]">
                              {model.providerBadge}
                            </Badge>
                          ) : null}
                        </div>
                        <div className="flex items-center gap-1">
                          <code className="font-mono text-xs text-muted-foreground">
                            {model.modelCode}
                          </code>
                          <CopyButton
                            value={model.modelCode}
                            copiedToast={`已复制 ${model.modelCode}`}
                          />
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1.5">
                        <Badge variant="outline">
                          {PROTOCOL_LABEL[model.nativeProtocol]}
                        </Badge>
                        <span className="text-xs text-muted-foreground">
                          {CAPABILITY_LABEL[model.capabilityType]}
                        </span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1.5 text-muted-foreground">
                        {model.supportsTools ? (
                          <Tooltip>
                            <TooltipTrigger render={<span className="inline-flex" />}>
                              <Wrench className="size-3.5" />
                            </TooltipTrigger>
                            <TooltipContent>工具调用</TooltipContent>
                          </Tooltip>
                        ) : null}
                        {model.supportsVision ? (
                          <Tooltip>
                            <TooltipTrigger render={<span className="inline-flex" />}>
                              <Eye className="size-3.5" />
                            </TooltipTrigger>
                            <TooltipContent>视觉理解</TooltipContent>
                          </Tooltip>
                        ) : null}
                        {model.supportsThinking ? (
                          <Tooltip>
                            <TooltipTrigger render={<span className="inline-flex" />}>
                              <Brain className="size-3.5" />
                            </TooltipTrigger>
                            <TooltipContent>深度推理</TooltipContent>
                          </Tooltip>
                        ) : null}
                        {model.supportsPromptCache ? (
                          <Tooltip>
                            <TooltipTrigger render={<span className="inline-flex" />}>
                              <Database className="size-3.5" />
                            </TooltipTrigger>
                            <TooltipContent>提示词缓存</TooltipContent>
                          </Tooltip>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">
                      <div>{formatCompactNumber(model.contextWindow)}</div>
                      {model.maxOutputTokens ? (
                        <div className="text-[11px] text-muted-foreground">
                          出 {formatCompactNumber(model.maxOutputTokens)}
                        </div>
                      ) : null}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex flex-col items-end gap-1">
                        <span className="font-mono text-xs tabular-nums">
                          {formatPerMillionUsd(model.pricing?.inputPriceMicros1m)}
                        </span>
                        <div className="h-1 w-20 overflow-hidden rounded-full bg-muted">
                          <div
                            className="h-full rounded-full bg-[var(--chart-1)]"
                            style={{ width: `${Math.max(inPct, inMicros > 0 ? 6 : 0)}%` }}
                          />
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex flex-col items-end gap-1">
                        <span className="font-mono text-xs tabular-nums">
                          {formatPerMillionUsd(model.pricing?.outputPriceMicros1m)}
                        </span>
                        <div className="h-1 w-20 overflow-hidden rounded-full bg-muted">
                          <div
                            className="h-full rounded-full bg-[var(--chart-3)]"
                            style={{ width: `${Math.max(outPct, outMicros > 0 ? 6 : 0)}%` }}
                          />
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums text-muted-foreground">
                      {formatPerMillionUsd(model.pricing?.cacheReadPriceMicros1m)}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="xs"
                          onClick={() => openCalculatorFor(model.modelCode)}
                        >
                          <Calculator className="size-3" />
                          <span>试算</span>
                        </Button>
                        <Button
                          variant="ghost"
                          size="xs"
                          render={
                            <Link
                              href={`/playground?model=${encodeURIComponent(model.modelCode)}`}
                            />
                          }
                        >
                          <Play className="size-3" />
                          <span>试用</span>
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </div>

      {/* Cost Calculator Sheet */}
      <Sheet open={calcOpen} onOpenChange={setCalcOpen}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle>费用试算</SheetTitle>
          </SheetHeader>

          <div className="flex flex-col gap-5 px-6 pb-6">
            <div className="flex flex-col gap-2">
              <Label>选择模型</Label>
              <Select
                value={selectedModel?.modelCode ?? ""}
                onValueChange={(v) => setCalcModelCode(v ?? "")}
              >
                <SelectTrigger className="w-full">
                  <SelectValue placeholder="选择模型" />
                </SelectTrigger>
                <SelectContent>
                  {(models ?? []).map((m) => (
                    <SelectItem key={m.id} value={m.modelCode}>
                      {m.displayName} ({m.modelCode})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="flex flex-col gap-4 border-y border-border py-4">
              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">常规输入 Token</span>
                  <span className="font-mono tabular-nums">{inputK}K</span>
                </div>
                <Slider
                  value={[inputK]}
                  min={0}
                  max={500}
                  step={5}
                  onValueChange={(v) =>
                    setInputK(Array.isArray(v) ? v[0] : v)
                  }
                />
              </div>

              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">缓存命中读取 Token</span>
                  <span className="font-mono tabular-nums">{cacheK}K</span>
                </div>
                <Slider
                  value={[cacheK]}
                  min={0}
                  max={500}
                  step={5}
                  onValueChange={(v) =>
                    setCacheK(Array.isArray(v) ? v[0] : v)
                  }
                />
              </div>

              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">输出 Token</span>
                  <span className="font-mono tabular-nums">{outputK}K</span>
                </div>
                <Slider
                  value={[outputK]}
                  min={0}
                  max={200}
                  step={2}
                  onValueChange={(v) =>
                    setOutputK(Array.isArray(v) ? v[0] : v)
                  }
                />
              </div>

              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">思考链 Token</span>
                  <span className="font-mono tabular-nums">{reasoningK}K</span>
                </div>
                <Slider
                  value={[reasoningK]}
                  min={0}
                  max={200}
                  step={2}
                  onValueChange={(v) =>
                    setReasoningK(Array.isArray(v) ? v[0] : v)
                  }
                />
              </div>
            </div>

            {/* Total readout + stacked visual breakdown */}
            <div className="flex flex-col gap-3">
              <div className="flex items-baseline justify-between">
                <span className="text-xs text-muted-foreground">估算费用</span>
                <div className="flex items-baseline gap-2">
                  <span className="text-2xl font-semibold tabular-nums">
                    {formatMicroUsd(estimate.totalMicros)}
                  </span>
                  {estimate.savedMicros > 0 ? (
                    <Badge variant="secondary" className="tabular-nums text-emerald-700 dark:text-emerald-400">
                      缓存节省 {formatMicroUsd(estimate.savedMicros)}
                    </Badge>
                  ) : null}
                </div>
              </div>

              <ChartContainer
                config={COST_CHART_CONFIG}
                className="h-14 w-full aspect-auto"
              >
                <BarChart data={chartData} layout="vertical" margin={{ left: 0, right: 0, top: 8, bottom: 8 }}>
                  <XAxis type="number" hide />
                  <YAxis type="category" dataKey="name" hide />
                  <ChartTooltip
                    content={
                      <ChartTooltipContent
                        formatter={(value) => `$${Number(value).toFixed(4)}`}
                      />
                    }
                  />
                  <Bar dataKey="input" stackId="a" fill="var(--color-input)" radius={[4, 0, 0, 4]} />
                  <Bar dataKey="cacheRead" stackId="a" fill="var(--color-cacheRead)" />
                  <Bar dataKey="output" stackId="a" fill="var(--color-output)" />
                  <Bar dataKey="reasoning" stackId="a" fill="var(--color-reasoning)" radius={[0, 4, 4, 0]} />
                </BarChart>
              </ChartContainer>

              <div className="grid grid-cols-2 gap-2 text-xs">
                <div className="flex items-center justify-between rounded-lg bg-muted/45 px-2.5 py-1.5">
                  <span className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="size-2 rounded-full bg-[var(--chart-1)]" />
                    输入
                  </span>
                  <span className="font-mono tabular-nums">
                    {formatMicroUsd(estimate.inputMicros)}
                  </span>
                </div>
                <div className="flex items-center justify-between rounded-lg bg-muted/45 px-2.5 py-1.5">
                  <span className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="size-2 rounded-full bg-[var(--chart-2)]" />
                    缓存读
                  </span>
                  <span className="font-mono tabular-nums">
                    {formatMicroUsd(estimate.cacheMicros)}
                  </span>
                </div>
                <div className="flex items-center justify-between rounded-lg bg-muted/45 px-2.5 py-1.5">
                  <span className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="size-2 rounded-full bg-[var(--chart-3)]" />
                    输出
                  </span>
                  <span className="font-mono tabular-nums">
                    {formatMicroUsd(estimate.outputMicros)}
                  </span>
                </div>
                <div className="flex items-center justify-between rounded-lg bg-muted/45 px-2.5 py-1.5">
                  <span className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="size-2 rounded-full bg-[var(--chart-4)]" />
                    思考链
                  </span>
                  <span className="font-mono tabular-nums">
                    {formatMicroUsd(estimate.reasoningMicros)}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </SheetContent>
      </Sheet>
    </div>
  );
}
