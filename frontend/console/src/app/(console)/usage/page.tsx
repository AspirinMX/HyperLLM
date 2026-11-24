"use client";

import { Suspense, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { BarChart3, Download, RefreshCw } from "lucide-react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Line,
  LineChart,
  XAxis,
  YAxis,
} from "recharts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { EmptyState } from "@/components/common/empty-state";
import { ProportionBar } from "@/components/common/proportion-bar";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { apiKeyApi, projectApi, usageApi } from "@/lib/api/endpoints";
import type { UsageGroupBy } from "@/lib/api/types";
import {
  formatCompactNumber,
  formatDateTime,
  formatLatencyMs,
  formatMicroUsd,
} from "@/lib/format/micro-usd";

type PresetRange = "24h" | "7d" | "30d" | "custom";
type SeriesTab = "tokens" | "cost" | "requests";
type LatencyMode = "ttft" | "duration";
type RankMetric = "cost" | "tokens" | "requests";

const RANGE_HOURS: Record<Exclude<PresetRange, "custom">, number> = {
  "24h": 24,
  "7d": 24 * 7,
  "30d": 24 * 30,
};

function toDateInputValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function escapeCsvCell(value: string | number): string {
  const raw = String(value);
  if (raw.includes(",") || raw.includes('"') || raw.includes("\n")) {
    return `"${raw.replace(/"/g, '""')}"`;
  }
  return raw;
}

const SERIES_CONFIG: ChartConfig = {
  promptTokens: { label: "常规输入", color: "var(--chart-1)" },
  cachedReadTokens: { label: "缓存读取", color: "var(--chart-2)" },
  completionTokens: { label: "输出", color: "var(--chart-3)" },
  reasoningTokens: { label: "思考链", color: "var(--chart-4)" },
  cashUsd: { label: "现金实扣", color: "var(--chart-2)" },
  bonusUsd: { label: "赠金抵扣", color: "var(--chart-3)" },
  successes: { label: "成功", color: "var(--chart-2)" },
  failures: { label: "失败", color: "var(--chart-5)" },
  successRate: { label: "成功率 %", color: "var(--chart-1)" },
};

const LATENCY_CONFIG: ChartConfig = {
  p50: { label: "P50", color: "var(--chart-2)" },
  p90: { label: "P90", color: "var(--chart-3)" },
  p99: { label: "P99", color: "var(--chart-5)" },
};

const RANK_CONFIG: ChartConfig = {
  metricValue: { label: "数值", color: "var(--chart-1)" },
};

function UsageAnalyticsContent() {
  const searchParams = useSearchParams();
  const initialModel = searchParams.get("model") ?? "";
  const { currentOrg } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;

  const [range, setRange] = useState<PresetRange>("7d");
  const [customFrom, setCustomFrom] = useState(() =>
    toDateInputValue(new Date(Date.now() - 7 * 24 * 3600 * 1000)),
  );
  const [customTo, setCustomTo] = useState(() => toDateInputValue(new Date()));
  const [projectFilter, setProjectFilter] = useState<string>("ALL");
  const [apiKeyFilter, setApiKeyFilter] = useState<string>("ALL");
  const [modelFilter, setModelFilter] = useState<string>(initialModel);

  const [seriesTab, setSeriesTab] = useState<SeriesTab>("tokens");
  const [latencyMode, setLatencyMode] = useState<LatencyMode>("ttft");
  const [groupBy, setGroupBy] = useState<Exclude<UsageGroupBy, "HOUR" | "CHANNEL">>("MODEL");
  const [rankMetric, setRankMetric] = useState<RankMetric>("cost");

  const { fromIso, toIso } = useMemo(() => {
    if (range === "custom" && customFrom && customTo) {
      const start = new Date(`${customFrom}T00:00:00`);
      const end = new Date(`${customTo}T23:59:59.999`);
      if (
        !Number.isNaN(start.getTime()) &&
        !Number.isNaN(end.getTime()) &&
        end >= start
      ) {
        return { fromIso: start.toISOString(), toIso: end.toISOString() };
      }
    }
    const preset: Exclude<PresetRange, "custom"> =
      range === "custom" ? "7d" : range;
    const to = new Date();
    const from = new Date(to.getTime() - RANGE_HOURS[preset] * 3600 * 1000);
    return { fromIso: from.toISOString(), toIso: to.toISOString() };
  }, [range, customFrom, customTo]);

  const projectsState = useAsyncData(
    (signal) => projectApi.list(orgId!, 1, 50, signal),
    [orgId],
    Boolean(orgId),
  );

  const keysState = useAsyncData(
    (signal) => apiKeyApi.list(projectFilter, 1, 50, signal),
    [projectFilter],
    projectFilter !== "ALL",
  );

  const queryFilters = useMemo(
    () => ({
      from: fromIso,
      to: toIso,
      projectId: projectFilter !== "ALL" ? projectFilter : undefined,
      apiKeyId:
        projectFilter !== "ALL" && apiKeyFilter !== "ALL"
          ? apiKeyFilter
          : undefined,
      model: modelFilter.trim() || undefined,
    }),
    [fromIso, toIso, projectFilter, apiKeyFilter, modelFilter],
  );

  const hourlyState = useAsyncData(
    (signal) =>
      usageApi.query(orgId!, { ...queryFilters, groupBy: "HOUR" }, signal),
    [orgId, queryFilters],
    Boolean(orgId),
  );

  const groupedState = useAsyncData(
    (signal) =>
      usageApi.query(orgId!, { ...queryFilters, groupBy }, signal),
    [orgId, queryFilters, groupBy],
    Boolean(orgId),
  );

  const projectNameMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of projectsState.data?.items ?? []) {
      map.set(p.id, p.name);
    }
    return map;
  }, [projectsState.data]);

  const keyNameMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const k of keysState.data?.items ?? []) {
      map.set(k.id, `${k.name} (${k.keyPrefix}…)`);
    }
    return map;
  }, [keysState.data]);

  const resolveDimensionLabel = (dim: string) => {
    if (groupBy === "PROJECT") return projectNameMap.get(dim) ?? dim.slice(0, 8);
    if (groupBy === "API_KEY") return keyNameMap.get(dim) ?? dim.slice(0, 8);
    return dim || "未归类";
  };

  const hourlySeries = useMemo(() => {
    return [...(hourlyState.data?.rows ?? [])]
      .sort((a, b) => a.dimension.localeCompare(b.dimension))
      .map((r) => {
        const failures = Math.max(0, r.requests - r.successes);
        const successRate =
          r.requests > 0 ? Number(((r.successes / r.requests) * 100).toFixed(2)) : 100;
        return {
          label: formatDateTime(r.dimension),
          promptTokens: r.promptTokens,
          cachedReadTokens: r.cachedReadTokens,
          completionTokens: r.completionTokens,
          reasoningTokens: r.reasoningTokens,
          cashUsd: Number((r.cashMicros / 1_000_000).toFixed(6)),
          bonusUsd: Number((r.bonusMicros / 1_000_000).toFixed(6)),
          successes: r.successes,
          failures,
          successRate,
          p50: Math.round(
            latencyMode === "ttft" ? r.ttftP50Ms : r.durationP50Ms,
          ),
          p90: Math.round(
            latencyMode === "ttft" ? r.ttftP90Ms : r.durationP90Ms,
          ),
          p99: Math.round(
            latencyMode === "ttft" ? r.ttftP99Ms : r.durationP99Ms,
          ),
        };
      });
  }, [hourlyState.data, latencyMode]);

  const rankedRows = useMemo(() => {
    const rows = groupedState.data?.rows ?? [];
    const mapped = rows.map((r) => {
      const totalTokens =
        r.promptTokens +
        r.cachedReadTokens +
        r.completionTokens +
        r.reasoningTokens;
      const metricValue =
        rankMetric === "cost"
          ? Number((r.costMicros / 1_000_000).toFixed(6))
          : rankMetric === "tokens"
            ? totalTokens
            : r.requests;
      return {
        ...r,
        displayLabel: resolveDimensionLabel(r.dimension),
        totalTokens,
        metricValue,
      };
    });
    return mapped.sort((a, b) => b.metricValue - a.metricValue);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groupedState.data, rankMetric, groupBy, projectNameMap, keyNameMap]);

  const totals = hourlyState.data?.totals;
  const totalInputTokens =
    (totals?.promptTokens ?? 0) + (totals?.cachedReadTokens ?? 0);
  const totalTokens =
    totalInputTokens +
    (totals?.completionTokens ?? 0) +
    (totals?.reasoningTokens ?? 0);
  const cacheHitRatio =
    totals && totalInputTokens > 0
      ? (totals.cachedReadTokens / totalInputTokens) * 100
      : 0;
  const successRateTotal =
    totals && totals.requests > 0
      ? (totals.successes / totals.requests) * 100
      : 100;
  const maxRankedCost = Math.max(1, ...rankedRows.map((r) => r.costMicros));

  function handleExportCsv() {
    const headers = [
      "Dimension",
      "Requests",
      "Successes",
      "UncachedPromptTokens",
      "CachedReadTokens",
      "CompletionTokens",
      "ReasoningTokens",
      "TotalTokens",
      "CostUSD",
      "CashUSD",
      "BonusUSD",
      "TtftP50Ms",
      "TtftP90Ms",
      "TtftP99Ms",
    ];
    const lines = [
      headers.join(","),
      ...rankedRows.map((r) =>
        [
          r.displayLabel,
          r.requests,
          r.successes,
          r.promptTokens,
          r.cachedReadTokens,
          r.completionTokens,
          r.reasoningTokens,
          r.totalTokens,
          (r.costMicros / 1_000_000).toFixed(6),
          (r.cashMicros / 1_000_000).toFixed(6),
          (r.bonusMicros / 1_000_000).toFixed(6),
          Math.round(r.ttftP50Ms),
          Math.round(r.ttftP90Ms),
          Math.round(r.ttftP99Ms),
        ]
          .map(escapeCsvCell)
          .join(","),
      ),
    ];
    const blob = new Blob(["\uFEFF" + lines.join("\n")], {
      type: "text/csv;charset=utf-8;",
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `hyperllm-usage-${groupBy.toLowerCase()}-${fromIso.slice(0, 10)}_${toIso.slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Sticky Filter Bar */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <Tabs
            value={range}
            onValueChange={(v) => setRange(v as PresetRange)}
          >
            <TabsList>
              <TabsTrigger value="24h">24 小时</TabsTrigger>
              <TabsTrigger value="7d">近 7 天</TabsTrigger>
              <TabsTrigger value="30d">近 30 天</TabsTrigger>
              <TabsTrigger value="custom">自定义</TabsTrigger>
            </TabsList>
          </Tabs>

          {range === "custom" ? (
            <div className="flex items-center gap-1.5">
              <Input
                type="date"
                aria-label="开始日期"
                value={customFrom}
                onChange={(e) => setCustomFrom(e.target.value)}
                className="w-36 font-mono text-xs"
              />
              <span className="text-xs text-muted-foreground">至</span>
              <Input
                type="date"
                aria-label="结束日期"
                value={customTo}
                onChange={(e) => setCustomTo(e.target.value)}
                className="w-36 font-mono text-xs"
              />
            </div>
          ) : null}

          <Select
            value={projectFilter}
            onValueChange={(v) => {
              setProjectFilter(v ?? "ALL");
              setApiKeyFilter("ALL");
            }}
          >
            <SelectTrigger className="w-36">
              <SelectValue placeholder="全部项目" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">全部项目</SelectItem>
              {(projectsState.data?.items ?? []).map((p) => (
                <SelectItem key={p.id} value={p.id}>
                  {p.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {projectFilter !== "ALL" ? (
            <Select
              value={apiKeyFilter}
              onValueChange={(v) => setApiKeyFilter(v ?? "ALL")}
            >
              <SelectTrigger className="w-40">
                <SelectValue placeholder="全部密钥" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部密钥</SelectItem>
                {(keysState.data?.items ?? []).map((k) => (
                  <SelectItem key={k.id} value={k.id}>
                    {k.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : null}

          <Input
            value={modelFilter}
            onChange={(e) => setModelFilter(e.target.value)}
            placeholder="按模型标识筛选"
            className="w-44 font-mono text-xs"
          />
        </div>

        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={rankedRows.length === 0}
            onClick={handleExportCsv}
          >
            <Download className="size-3.5" />
            <span>导出 CSV</span>
          </Button>
          <Button
            type="button"
            variant="outline"
            size="icon"
            onClick={() => {
              hourlyState.reload();
              groupedState.reload();
            }}
            aria-label="刷新报表"
          >
            <RefreshCw
              className={
                hourlyState.refreshing
                  ? "size-3.5 animate-spin motion-reduce:animate-none"
                  : "size-3.5"
              }
            />
          </Button>
        </div>
      </div>

      <ApiErrorAlert
        error={hourlyState.error ?? groupedState.error}
        onRetry={() => {
          hourlyState.reload();
          groupedState.reload();
        }}
      />

      {/* Row 1: Single-layer 4-Metric Strip */}
      <section className="squircle-md grid divide-y divide-border overflow-hidden rounded-xl border border-border bg-card sm:grid-cols-2 lg:grid-cols-4 lg:divide-x lg:divide-y-0">
        <div className="flex flex-col gap-2.5 p-4">
          <span className="text-xs text-muted-foreground">总费用</span>
          <span className="text-2xl font-semibold tabular-nums">
            {formatMicroUsd(totals?.costMicros ?? 0)}
          </span>
          <ProportionBar
            heightClass="h-1.5"
            segments={[
              {
                key: "cash",
                label: "现金",
                value: totals?.cashMicros ?? 0,
                displayValue: formatMicroUsd(totals?.cashMicros ?? 0),
                colorClass: "bg-[var(--chart-2)]",
              },
              {
                key: "bonus",
                label: "赠金",
                value: totals?.bonusMicros ?? 0,
                displayValue: formatMicroUsd(totals?.bonusMicros ?? 0),
                colorClass: "bg-[var(--chart-3)]",
              },
            ]}
          />
        </div>

        <div className="flex flex-col gap-2.5 p-4">
          <span className="text-xs text-muted-foreground">总 Token 消耗</span>
          <span className="text-2xl font-semibold tabular-nums">
            {formatCompactNumber(totalTokens)}
          </span>
          <ProportionBar
            heightClass="h-1.5"
            segments={[
              {
                key: "in",
                label: "常规输入",
                value: Math.max(
                  0,
                  (totals?.promptTokens ?? 0) - (totals?.cachedReadTokens ?? 0),
                ),
                displayValue: formatCompactNumber(
                  Math.max(
                    0,
                    (totals?.promptTokens ?? 0) -
                      (totals?.cachedReadTokens ?? 0),
                  ),
                ),
                colorClass: "bg-[var(--chart-1)]",
              },
              {
                key: "cache",
                label: "缓存读",
                value: totals?.cachedReadTokens ?? 0,
                displayValue: formatCompactNumber(totals?.cachedReadTokens ?? 0),
                colorClass: "bg-[var(--chart-2)]",
              },
              {
                key: "out",
                label: "输出",
                value: totals?.completionTokens ?? 0,
                displayValue: formatCompactNumber(totals?.completionTokens ?? 0),
                colorClass: "bg-[var(--chart-3)]",
              },
              {
                key: "reason",
                label: "思考链",
                value: totals?.reasoningTokens ?? 0,
                displayValue: formatCompactNumber(totals?.reasoningTokens ?? 0),
                colorClass: "bg-[var(--chart-4)]",
              },
            ]}
          />
        </div>

        <div className="flex flex-col gap-2.5 p-4">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>请求总量</span>
            <span className="font-mono font-medium text-foreground tabular-nums">
              {successRateTotal.toFixed(1)}% 成功
            </span>
          </div>
          <span className="text-2xl font-semibold tabular-nums">
            {formatCompactNumber(totals?.requests ?? 0)}
          </span>
          <ProportionBar
            heightClass="h-1.5"
            segments={[
              {
                key: "ok",
                label: "成功",
                value: totals?.successes ?? 0,
                displayValue: formatCompactNumber(totals?.successes ?? 0),
                colorClass: "bg-[var(--chart-2)]",
              },
              {
                key: "err",
                label: "失败",
                value: Math.max(
                  0,
                  (totals?.requests ?? 0) - (totals?.successes ?? 0),
                ),
                displayValue: formatCompactNumber(
                  Math.max(
                    0,
                    (totals?.requests ?? 0) - (totals?.successes ?? 0),
                  ),
                ),
                colorClass: "bg-[var(--chart-5)]",
              },
            ]}
          />
        </div>

        <div className="flex flex-col gap-2.5 p-4">
          <span className="text-xs text-muted-foreground">提示词缓存命中率</span>
          <span className="text-2xl font-semibold tabular-nums">
            {cacheHitRatio.toFixed(1)}%
          </span>
          <span className="text-[11px] text-muted-foreground tabular-nums">
            命中 {formatCompactNumber(totals?.cachedReadTokens ?? 0)} / 输入{" "}
            {formatCompactNumber(totals?.promptTokens ?? 0)}
          </span>
        </div>
      </section>

      {/* Row 2: Full-width Time Series Chart */}
      <section className="squircle-md flex flex-col rounded-xl border border-border bg-card">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3">
          <span className="text-sm font-medium">时段分布趋势</span>
          <Tabs
            value={seriesTab}
            onValueChange={(v) => setSeriesTab(v as SeriesTab)}
          >
            <TabsList className="h-7">
              <TabsTrigger value="tokens" className="text-xs">
                Token 构成
              </TabsTrigger>
              <TabsTrigger value="cost" className="text-xs">
                费用构成
              </TabsTrigger>
              <TabsTrigger value="requests" className="text-xs">
                请求与成功率
              </TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        <div className="p-4">
          {hourlyState.loading ? (
            <Skeleton className="h-64 w-full" />
          ) : hourlySeries.length === 0 ? (
            <EmptyState
              icon={<BarChart3 className="size-4" />}
              title="所选条件下暂无趋势数据"
            />
          ) : (
            <ChartContainer
              config={SERIES_CONFIG}
              className="h-64 w-full aspect-auto"
            >
              {seriesTab === "tokens" ? (
                <AreaChart data={hourlySeries}>
                  <CartesianGrid vertical={false} strokeDasharray="3 3" />
                  <XAxis
                    dataKey="label"
                    tickLine={false}
                    axisLine={false}
                    minTickGap={28}
                  />
                  <YAxis
                    tickLine={false}
                    axisLine={false}
                    width={48}
                    tickFormatter={(v) => formatCompactNumber(Number(v))}
                  />
                  <ChartTooltip content={<ChartTooltipContent />} />
                  <ChartLegend content={<ChartLegendContent />} />
                  <Area
                    type="monotone"
                    dataKey="promptTokens"
                    stackId="t"
                    stroke="var(--color-promptTokens)"
                    fill="var(--color-promptTokens)"
                    fillOpacity={0.22}
                  />
                  <Area
                    type="monotone"
                    dataKey="cachedReadTokens"
                    stackId="t"
                    stroke="var(--color-cachedReadTokens)"
                    fill="var(--color-cachedReadTokens)"
                    fillOpacity={0.3}
                  />
                  <Area
                    type="monotone"
                    dataKey="completionTokens"
                    stackId="t"
                    stroke="var(--color-completionTokens)"
                    fill="var(--color-completionTokens)"
                    fillOpacity={0.26}
                  />
                  <Area
                    type="monotone"
                    dataKey="reasoningTokens"
                    stackId="t"
                    stroke="var(--color-reasoningTokens)"
                    fill="var(--color-reasoningTokens)"
                    fillOpacity={0.28}
                  />
                </AreaChart>
              ) : seriesTab === "cost" ? (
                <BarChart data={hourlySeries}>
                  <CartesianGrid vertical={false} strokeDasharray="3 3" />
                  <XAxis
                    dataKey="label"
                    tickLine={false}
                    axisLine={false}
                    minTickGap={28}
                  />
                  <YAxis
                    tickLine={false}
                    axisLine={false}
                    width={54}
                    tickFormatter={(v) => `$${Number(v).toFixed(2)}`}
                  />
                  <ChartTooltip
                    content={
                      <ChartTooltipContent
                        formatter={(value) => `$${Number(value).toFixed(4)}`}
                      />
                    }
                  />
                  <ChartLegend content={<ChartLegendContent />} />
                  <Bar
                    dataKey="cashUsd"
                    stackId="c"
                    fill="var(--color-cashUsd)"
                  />
                  <Bar
                    dataKey="bonusUsd"
                    stackId="c"
                    fill="var(--color-bonusUsd)"
                    radius={[3, 3, 0, 0]}
                  />
                </BarChart>
              ) : (
                <ComposedChart data={hourlySeries}>
                  <CartesianGrid vertical={false} strokeDasharray="3 3" />
                  <XAxis
                    dataKey="label"
                    tickLine={false}
                    axisLine={false}
                    minTickGap={28}
                  />
                  <YAxis
                    yAxisId="left"
                    tickLine={false}
                    axisLine={false}
                    width={46}
                    tickFormatter={(v) => formatCompactNumber(Number(v))}
                  />
                  <YAxis
                    yAxisId="right"
                    orientation="right"
                    domain={[0, 100]}
                    tickLine={false}
                    axisLine={false}
                    width={42}
                    tickFormatter={(v) => `${v}%`}
                  />
                  <ChartTooltip content={<ChartTooltipContent />} />
                  <ChartLegend content={<ChartLegendContent />} />
                  <Bar
                    yAxisId="left"
                    dataKey="successes"
                    stackId="r"
                    fill="var(--color-successes)"
                  />
                  <Bar
                    yAxisId="left"
                    dataKey="failures"
                    stackId="r"
                    fill="var(--color-failures)"
                    radius={[3, 3, 0, 0]}
                  />
                  <Line
                    yAxisId="right"
                    type="monotone"
                    dataKey="successRate"
                    stroke="var(--color-successRate)"
                    strokeWidth={2}
                    dot={false}
                  />
                </ComposedChart>
              )}
            </ChartContainer>
          )}
        </div>
      </section>

      {/* Row 3: Dual 6:6 Latency Quantiles & Dimension Ranking */}
      <section className="grid gap-5 lg:grid-cols-12">
        <div className="squircle-md flex flex-col rounded-xl border border-border bg-card lg:col-span-6">
          <div className="flex items-center justify-between border-b border-border px-4 py-3">
            <span className="text-sm font-medium">延迟分位分布</span>
            <Tabs
              value={latencyMode}
              onValueChange={(v) => setLatencyMode(v as LatencyMode)}
            >
              <TabsList className="h-7">
                <TabsTrigger value="ttft" className="text-xs">
                  首字 TTFT
                </TabsTrigger>
                <TabsTrigger value="duration" className="text-xs">
                  端到端耗时
                </TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
          <div className="p-4">
            {hourlyState.loading ? (
              <Skeleton className="h-56 w-full" />
            ) : hourlySeries.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="暂无延迟统计"
              />
            ) : (
              <ChartContainer
                config={LATENCY_CONFIG}
                className="h-56 w-full aspect-auto"
              >
                <LineChart data={hourlySeries}>
                  <CartesianGrid vertical={false} strokeDasharray="3 3" />
                  <XAxis
                    dataKey="label"
                    tickLine={false}
                    axisLine={false}
                    minTickGap={30}
                  />
                  <YAxis
                    tickLine={false}
                    axisLine={false}
                    width={52}
                    tickFormatter={(v) => formatLatencyMs(Number(v))}
                  />
                  <ChartTooltip
                    content={
                      <ChartTooltipContent
                        formatter={(v) => formatLatencyMs(Number(v))}
                      />
                    }
                  />
                  <ChartLegend content={<ChartLegendContent />} />
                  <Line
                    type="monotone"
                    dataKey="p50"
                    stroke="var(--color-p50)"
                    strokeWidth={1.75}
                    dot={false}
                  />
                  <Line
                    type="monotone"
                    dataKey="p90"
                    stroke="var(--color-p90)"
                    strokeWidth={1.75}
                    dot={false}
                  />
                  <Line
                    type="monotone"
                    dataKey="p99"
                    stroke="var(--color-p99)"
                    strokeWidth={1.75}
                    dot={false}
                  />
                </LineChart>
              </ChartContainer>
            )}
          </div>
        </div>

        <div className="squircle-md flex flex-col rounded-xl border border-border bg-card lg:col-span-6">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3">
            <Tabs
              value={groupBy}
              onValueChange={(v) =>
                setGroupBy(v as Exclude<UsageGroupBy, "HOUR" | "CHANNEL">)
              }
            >
              <TabsList className="h-7">
                <TabsTrigger value="MODEL" className="text-xs">
                  按模型
                </TabsTrigger>
                <TabsTrigger value="PROJECT" className="text-xs">
                  按项目
                </TabsTrigger>
                <TabsTrigger value="API_KEY" className="text-xs">
                  按密钥
                </TabsTrigger>
              </TabsList>
            </Tabs>

            <Tabs
              value={rankMetric}
              onValueChange={(v) => setRankMetric(v as RankMetric)}
            >
              <TabsList className="h-7">
                <TabsTrigger value="cost" className="text-xs">
                  费用
                </TabsTrigger>
                <TabsTrigger value="tokens" className="text-xs">
                  Token
                </TabsTrigger>
                <TabsTrigger value="requests" className="text-xs">
                  请求
                </TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
          <div className="p-4">
            {groupedState.loading ? (
              <Skeleton className="h-56 w-full" />
            ) : rankedRows.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="暂无维度排行数据"
              />
            ) : (
              <ChartContainer
                config={RANK_CONFIG}
                className="h-56 w-full aspect-auto"
              >
                <BarChart
                  data={rankedRows.slice(0, 7)}
                  layout="vertical"
                  margin={{ left: 0, right: 12, top: 4, bottom: 4 }}
                >
                  <XAxis type="number" hide />
                  <YAxis
                    type="category"
                    dataKey="displayLabel"
                    width={116}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(v: string) =>
                      v.length > 16 ? `${v.slice(0, 15)}…` : v
                    }
                  />
                  <ChartTooltip
                    content={
                      <ChartTooltipContent
                        formatter={(value) =>
                          rankMetric === "cost"
                            ? `$${Number(value).toFixed(4)}`
                            : formatCompactNumber(Number(value))
                        }
                      />
                    }
                  />
                  <Bar
                    dataKey="metricValue"
                    fill="var(--color-metricValue)"
                    radius={[0, 4, 4, 0]}
                  />
                </BarChart>
              </ChartContainer>
            )}
          </div>
        </div>
      </section>

      {/* Row 4: Breakdown Table */}
      <section className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        <div className="border-b border-border px-4 py-3 text-sm font-medium">
          维度明细
        </div>
        {rankedRows.length === 0 ? (
          <EmptyState
            icon={<BarChart3 className="size-4" />}
            title="暂无明细数据"
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/35 hover:bg-muted/35">
                <TableHead>名称</TableHead>
                <TableHead className="text-right">请求数</TableHead>
                <TableHead className="text-right">成功率</TableHead>
                <TableHead className="text-right">总 Token</TableHead>
                <TableHead className="text-right">TTFT P90</TableHead>
                <TableHead className="w-44 text-right">总费用</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rankedRows.map((row) => {
                const rate =
                  row.requests > 0 ? (row.successes / row.requests) * 100 : 100;
                const barPct = Math.round((row.costMicros / maxRankedCost) * 100);
                return (
                  <TableRow key={row.dimension}>
                    <TableCell className="font-medium">
                      {row.displayLabel}
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">
                      {formatCompactNumber(row.requests)}
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">
                      {rate.toFixed(1)}%
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">
                      {formatCompactNumber(row.totalTokens)}
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">
                      {formatLatencyMs(row.ttftP90Ms)}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex flex-col items-end gap-1">
                        <span className="font-mono text-xs tabular-nums">
                          {formatMicroUsd(row.costMicros)}
                        </span>
                        <div className="h-1 w-24 overflow-hidden rounded-full bg-muted">
                          <div
                            className="h-full rounded-full bg-[var(--chart-1)]"
                            style={{
                              width: `${Math.max(barPct, row.costMicros > 0 ? 5 : 0)}%`,
                            }}
                          />
                        </div>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </section>
    </div>
  );
}

export default function UsagePage() {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-4">
          <Skeleton className="h-9 w-80" />
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-72 w-full" />
        </div>
      }
    >
      <UsageAnalyticsContent />
    </Suspense>
  );
}
