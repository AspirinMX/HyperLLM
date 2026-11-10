"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ArrowRight,
  BarChart3,
  CreditCard,
  FolderKanban,
  KeyRound,
  RefreshCw,
  Terminal,
} from "lucide-react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  XAxis,
  YAxis,
} from "recharts";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Skeleton } from "@/components/ui/skeleton";
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { CopyButton } from "@/components/common/copy-button";
import { EmptyState } from "@/components/common/empty-state";
import { ProportionBar } from "@/components/common/proportion-bar";
import { StatusDot } from "@/components/common/status-dot";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { projectApi, usageApi } from "@/lib/api/endpoints";
import type { ProjectBudgetDto, ProjectDto } from "@/lib/api/types";
import {
  formatCompactNumber,
  formatDateTime,
  formatLatencyMs,
  formatMicroUsd,
} from "@/lib/format/micro-usd";
import {
  canUsePlaygroundOrKeys,
  canViewWallet,
  PROJECT_ENV_LABEL,
} from "@/lib/format/labels";

type TimeWindow = "24h" | "7d" | "30d";
type TrendTab = "cost" | "requests" | "tokens";

const WINDOW_HOURS: Record<TimeWindow, number> = {
  "24h": 24,
  "7d": 24 * 7,
  "30d": 24 * 30,
};

const TREND_CHART_CONFIG: ChartConfig = {
  costUsd: { label: "费用 (USD)", color: "var(--chart-1)" },
  successes: { label: "成功", color: "var(--chart-2)" },
  failures: { label: "失败", color: "var(--chart-5)" },
  promptTokens: { label: "输入", color: "var(--chart-1)" },
  cachedReadTokens: { label: "缓存读取", color: "var(--chart-2)" },
  completionTokens: { label: "输出", color: "var(--chart-3)" },
  reasoningTokens: { label: "思考链", color: "var(--chart-4)" },
};

const MODEL_CHART_CONFIG: ChartConfig = {
  costUsd: { label: "费用 (USD)", color: "var(--chart-1)" },
};

interface ProjectWithBudget {
  project: ProjectDto;
  budget: ProjectBudgetDto | null;
}

export default function OverviewPage() {
  const router = useRouter();
  const { currentOrg, wallet, refreshWallet } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;
  const showWallet = canViewWallet(currentOrg?.role);
  const showDev = canUsePlaygroundOrKeys(currentOrg?.role);

  const [windowKey, setWindowKey] = useState<TimeWindow>("7d");
  const [trendTab, setTrendTab] = useState<TrendTab>("cost");

  const { fromIso, toIso } = useMemo(() => {
    const to = new Date();
    const from = new Date(to.getTime() - WINDOW_HOURS[windowKey] * 3600 * 1000);
    return { fromIso: from.toISOString(), toIso: to.toISOString() };
  }, [windowKey]);

  const hourlyUsage = useAsyncData(
    (signal) =>
      usageApi.query(
        orgId!,
        { from: fromIso, to: toIso, groupBy: "HOUR" },
        signal,
      ),
    [orgId, windowKey],
    Boolean(orgId),
  );

  const modelUsage = useAsyncData(
    (signal) =>
      usageApi.query(
        orgId!,
        { from: fromIso, to: toIso, groupBy: "MODEL" },
        signal,
      ),
    [orgId, windowKey],
    Boolean(orgId),
  );

  const projectsState = useAsyncData(
    async (signal): Promise<ProjectWithBudget[]> => {
      const page = await projectApi.list(orgId!, 1, 6, signal);
      const budgets = await Promise.all(
        page.items.map((p) =>
          projectApi.getBudget(p.id, signal).catch(() => null),
        ),
      );
      return page.items.map((project, i) => ({ project, budget: budgets[i] }));
    },
    [orgId],
    Boolean(orgId),
  );

  const hourlySeries = useMemo(() => {
    const rows = hourlyUsage.data?.rows ?? [];
    return [...rows]
      .sort((a, b) => a.dimension.localeCompare(b.dimension))
      .map((r) => ({
        label: formatDateTime(r.dimension),
        costUsd: Number((r.costMicros / 1_000_000).toFixed(6)),
        requests: r.requests,
        successes: r.successes,
        failures: Math.max(0, r.requests - r.successes),
        promptTokens: r.promptTokens,
        cachedReadTokens: r.cachedReadTokens,
        completionTokens: r.completionTokens,
        reasoningTokens: r.reasoningTokens,
      }));
  }, [hourlyUsage.data]);

  const topModels = useMemo(() => {
    const rows = modelUsage.data?.rows ?? [];
    return [...rows]
      .sort((a, b) => b.costMicros - a.costMicros || b.requests - a.requests)
      .slice(0, 6)
      .map((r) => ({
        model: r.dimension || "未知模型",
        costUsd: Number((r.costMicros / 1_000_000).toFixed(6)),
        costMicros: r.costMicros,
        requests: r.requests,
      }));
  }, [modelUsage.data]);

  const totals = hourlyUsage.data?.totals;
  const successRate =
    totals && totals.requests > 0
      ? (totals.successes / totals.requests) * 100
      : null;

  const latencySummary = useMemo(() => {
    const rows = (hourlyUsage.data?.rows ?? []).filter((r) => r.requests > 0);
    if (rows.length === 0) return { p50: 0, p90: 0, p99: 0 };
    const totalReq = rows.reduce((s, r) => s + r.requests, 0) || 1;
    return {
      p50: rows.reduce((s, r) => s + r.ttftP50Ms * r.requests, 0) / totalReq,
      p90: rows.reduce((s, r) => s + r.ttftP90Ms * r.requests, 0) / totalReq,
      p99: rows.reduce((s, r) => s + r.ttftP99Ms * r.requests, 0) / totalReq,
    };
  }, [hourlyUsage.data]);

  function handleRefreshAll() {
    hourlyUsage.reload();
    modelUsage.reload();
    projectsState.reload();
    if (showWallet) void refreshWallet();
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-lg font-semibold tracking-tight">工作空间总览</h1>
        <div className="flex items-center gap-2">
          <Tabs
            value={windowKey}
            onValueChange={(v) => setWindowKey(v as TimeWindow)}
          >
            <TabsList>
              <TabsTrigger value="24h">24 小时</TabsTrigger>
              <TabsTrigger value="7d">近 7 天</TabsTrigger>
              <TabsTrigger value="30d">近 30 天</TabsTrigger>
            </TabsList>
          </Tabs>
          <Button
            type="button"
            variant="outline"
            size="icon"
            onClick={handleRefreshAll}
            aria-label="刷新"
          >
            <RefreshCw
              className={
                hourlyUsage.refreshing
                  ? "size-3.5 animate-spin motion-reduce:animate-none"
                  : "size-3.5"
              }
            />
          </Button>
        </div>
      </div>

      {hourlyUsage.error ? (
        <ApiErrorAlert error={hourlyUsage.error} onRetry={handleRefreshAll} />
      ) : null}

      {/* Row 1: Single-layer 4-cell KPI strip with embedded sparklines & proportions */}
      <section className="squircle-md grid divide-y divide-border overflow-hidden rounded-xl border border-border bg-card sm:grid-cols-2 lg:grid-cols-4 lg:divide-x lg:divide-y-0">
        {/* Cell 1: Balance or Active Projects */}
        <div className="flex flex-col justify-between gap-3 p-4">
          {showWallet ? (
            <>
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>可用余额</span>
                {wallet ? (
                  <StatusDot
                    tone={wallet.ledgerConfirmed ? "muted" : "success"}
                    label={wallet.ledgerConfirmed ? "账本确认" : "实时"}
                  />
                ) : null}
              </div>
              <div className="text-2xl font-semibold tracking-tight tabular-nums">
                {wallet ? formatMicroUsd(wallet.availableMicros) : "—"}
              </div>
              <div className="flex flex-col gap-1.5">
                <ProportionBar
                  heightClass="h-1.5"
                  segments={
                    wallet
                      ? [
                          {
                            key: "cash",
                            label: "现金",
                            value: Math.max(0, wallet.cashBalanceMicros),
                            displayValue: formatMicroUsd(wallet.cashBalanceMicros),
                            colorClass: "bg-[var(--chart-2)]",
                          },
                          {
                            key: "bonus",
                            label: "赠金",
                            value: Math.max(0, wallet.bonusBalanceMicros),
                            displayValue: formatMicroUsd(wallet.bonusBalanceMicros),
                            colorClass: "bg-[var(--chart-3)]",
                          },
                          {
                            key: "held",
                            label: "预占",
                            value: Math.max(0, wallet.heldMicros),
                            displayValue: formatMicroUsd(wallet.heldMicros),
                            colorClass: "bg-[var(--chart-4)]",
                          },
                        ]
                      : []
                  }
                />
                <div className="flex items-center justify-between text-[11px] text-muted-foreground tabular-nums">
                  <span>现金 {wallet ? formatMicroUsd(wallet.cashBalanceMicros) : "—"}</span>
                  <span>赠金 {wallet ? formatMicroUsd(wallet.bonusBalanceMicros) : "—"}</span>
                </div>
              </div>
            </>
          ) : (
            <>
              <div className="text-xs text-muted-foreground">可访问项目</div>
              <div className="text-2xl font-semibold tracking-tight tabular-nums">
                {projectsState.data?.length ?? 0}
              </div>
              <div className="text-[11px] text-muted-foreground">
                点击下方项目查看月度预算水位
              </div>
            </>
          )}
        </div>

        {/* Cell 2: Spend + Micro Area Sparkline */}
        <div className="flex flex-col justify-between gap-2 p-4">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>周期消费</span>
            {totals && totals.packUnits > 0 ? (
              <Badge variant="secondary" className="h-4 px-1.5 text-[10px] tabular-nums">
                含包 {formatCompactNumber(totals.packUnits)}
              </Badge>
            ) : null}
          </div>
          <div className="flex items-end justify-between gap-2">
            <span className="text-2xl font-semibold tracking-tight tabular-nums">
              {hourlyUsage.loading
                ? "…"
                : formatMicroUsd(totals?.costMicros ?? 0)}
            </span>
            <div className="h-9 w-24 shrink-0">
              {hourlySeries.length > 1 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={hourlySeries}>
                    <Area
                      type="monotone"
                      dataKey="costUsd"
                      stroke="var(--chart-1)"
                      fill="var(--chart-1)"
                      fillOpacity={0.14}
                      strokeWidth={1.5}
                      isAnimationActive={false}
                    />
                  </AreaChart>
                </ResponsiveContainer>
              ) : null}
            </div>
          </div>
          <div className="flex items-center justify-between text-[11px] text-muted-foreground tabular-nums">
            <span>现金 {formatMicroUsd(totals?.cashMicros ?? 0)}</span>
            <span>赠金 {formatMicroUsd(totals?.bonusMicros ?? 0)}</span>
          </div>
        </div>

        {/* Cell 3: Requests + Success Rate + Micro Bar Sparkline */}
        <div className="flex flex-col justify-between gap-2 p-4">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>请求总量</span>
            {successRate !== null ? (
              <StatusDot
                tone={
                  successRate >= 99
                    ? "success"
                    : successRate >= 95
                      ? "warning"
                      : "danger"
                }
                label={`${successRate.toFixed(1)}%`}
              />
            ) : null}
          </div>
          <div className="flex items-end justify-between gap-2">
            <span className="text-2xl font-semibold tracking-tight tabular-nums">
              {hourlyUsage.loading
                ? "…"
                : formatCompactNumber(totals?.requests ?? 0)}
            </span>
            <div className="h-9 w-24 shrink-0">
              {hourlySeries.length > 1 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={hourlySeries.slice(-16)}>
                    <Bar
                      dataKey="successes"
                      fill="var(--chart-2)"
                      radius={[2, 2, 0, 0]}
                      isAnimationActive={false}
                    />
                  </BarChart>
                </ResponsiveContainer>
              ) : null}
            </div>
          </div>
          <div className="flex items-center justify-between text-[11px] text-muted-foreground tabular-nums">
            <span>
              Token{" "}
              {formatCompactNumber(
                (totals?.promptTokens ?? 0) +
                  (totals?.completionTokens ?? 0) +
                  (totals?.cachedReadTokens ?? 0),
              )}
            </span>
            <span>缓存 {formatCompactNumber(totals?.cachedReadTokens ?? 0)}</span>
          </div>
        </div>

        {/* Cell 4: TTFT P90 + P50/P99 */}
        <div className="flex flex-col justify-between gap-3 p-4">
          <div className="text-xs text-muted-foreground">首字延迟 P90</div>
          <div className="text-2xl font-semibold tracking-tight tabular-nums">
            {hourlyUsage.loading ? "…" : formatLatencyMs(latencySummary.p90)}
          </div>
          <div className="flex items-center justify-between text-[11px] text-muted-foreground tabular-nums">
            <span>P50 {formatLatencyMs(latencySummary.p50)}</span>
            <span>P99 {formatLatencyMs(latencySummary.p99)}</span>
          </div>
        </div>
      </section>

      {/* Row 2: 8:4 Trend Chart & Model Distribution Chart */}
      <section className="grid gap-5 lg:grid-cols-12">
        <div className="squircle-md flex flex-col rounded-xl border border-border bg-card lg:col-span-8">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3">
            <span className="text-sm font-medium">调用趋势</span>
            <Tabs
              value={trendTab}
              onValueChange={(v) => setTrendTab(v as TrendTab)}
            >
              <TabsList className="h-7">
                <TabsTrigger value="cost" className="text-xs">
                  消费
                </TabsTrigger>
                <TabsTrigger value="requests" className="text-xs">
                  请求
                </TabsTrigger>
                <TabsTrigger value="tokens" className="text-xs">
                  Token
                </TabsTrigger>
              </TabsList>
            </Tabs>
          </div>

          <div className="p-4">
            {hourlyUsage.loading ? (
              <Skeleton className="h-60 w-full" />
            ) : hourlySeries.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="该时段暂无调用记录"
                action={
                  showDev ? (
                    <Button size="sm" variant="outline" render={<Link href="/playground" />}>
                      前往 Playground 发起调试
                    </Button>
                  ) : null
                }
              />
            ) : (
              <ChartContainer
                config={TREND_CHART_CONFIG}
                className="h-60 w-full aspect-auto"
              >
                {trendTab === "cost" ? (
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
                      width={52}
                      tickFormatter={(v) => `$${Number(v).toFixed(2)}`}
                    />
                    <ChartTooltip
                      content={
                        <ChartTooltipContent
                          formatter={(value) => `$${Number(value).toFixed(4)}`}
                        />
                      }
                    />
                    <Area
                      type="monotone"
                      dataKey="costUsd"
                      stroke="var(--color-costUsd)"
                      fill="var(--color-costUsd)"
                      fillOpacity={0.14}
                      strokeWidth={2}
                    />
                  </AreaChart>
                ) : trendTab === "requests" ? (
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
                      width={44}
                      tickFormatter={(v) => formatCompactNumber(Number(v))}
                    />
                    <ChartTooltip content={<ChartTooltipContent />} />
                    <Bar
                      dataKey="successes"
                      stackId="req"
                      fill="var(--color-successes)"
                      radius={[0, 0, 0, 0]}
                    />
                    <Bar
                      dataKey="failures"
                      stackId="req"
                      fill="var(--color-failures)"
                      radius={[3, 3, 0, 0]}
                    />
                  </BarChart>
                ) : (
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
                    <Area
                      type="monotone"
                      dataKey="promptTokens"
                      stackId="tok"
                      stroke="var(--color-promptTokens)"
                      fill="var(--color-promptTokens)"
                      fillOpacity={0.22}
                    />
                    <Area
                      type="monotone"
                      dataKey="cachedReadTokens"
                      stackId="tok"
                      stroke="var(--color-cachedReadTokens)"
                      fill="var(--color-cachedReadTokens)"
                      fillOpacity={0.28}
                    />
                    <Area
                      type="monotone"
                      dataKey="completionTokens"
                      stackId="tok"
                      stroke="var(--color-completionTokens)"
                      fill="var(--color-completionTokens)"
                      fillOpacity={0.24}
                    />
                    <Area
                      type="monotone"
                      dataKey="reasoningTokens"
                      stackId="tok"
                      stroke="var(--color-reasoningTokens)"
                      fill="var(--color-reasoningTokens)"
                      fillOpacity={0.26}
                    />
                  </AreaChart>
                )}
              </ChartContainer>
            )}
          </div>
        </div>

        {/* Top Models Horizontal Bar Chart */}
        <div className="squircle-md flex flex-col rounded-xl border border-border bg-card lg:col-span-4">
          <div className="flex items-center justify-between border-b border-border px-4 py-3">
            <span className="text-sm font-medium">模型消费分布</span>
            <Button
              variant="ghost"
              size="xs"
              render={<Link href="/usage" />}
              className="text-muted-foreground"
            >
              明细
              <ArrowRight className="size-3" />
            </Button>
          </div>
          <div className="flex-1 p-4">
            {modelUsage.loading ? (
              <Skeleton className="h-60 w-full" />
            ) : topModels.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="暂无模型分布"
              />
            ) : (
              <ChartContainer
                config={MODEL_CHART_CONFIG}
                className="h-60 w-full aspect-auto"
              >
                <BarChart
                  data={topModels}
                  layout="vertical"
                  margin={{ left: 0, right: 12, top: 4, bottom: 4 }}
                >
                  <XAxis type="number" hide />
                  <YAxis
                    type="category"
                    dataKey="model"
                    width={104}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(v: string) =>
                      v.length > 15 ? `${v.slice(0, 14)}…` : v
                    }
                  />
                  <ChartTooltip
                    content={
                      <ChartTooltipContent
                        formatter={(value) => `$${Number(value).toFixed(4)}`}
                      />
                    }
                  />
                  <Bar
                    dataKey="costUsd"
                    fill="var(--color-costUsd)"
                    radius={[0, 4, 4, 0]}
                    className="cursor-pointer"
                    onClick={(entry) => {
                      const payload = entry?.payload as
                        | { model?: string }
                        | undefined;
                      if (payload?.model) {
                        router.push(
                          `/usage?model=${encodeURIComponent(payload.model)}`,
                        );
                      }
                    }}
                  />
                </BarChart>
              </ChartContainer>
            )}
          </div>
        </div>
      </section>

      {/* Row 3: 7:5 Project Budgets + Quick Access */}
      <section className="grid gap-5 lg:grid-cols-12">
        <div className="squircle-md flex flex-col rounded-xl border border-border bg-card lg:col-span-7">
          <div className="flex items-center justify-between border-b border-border px-4 py-3">
            <span className="text-sm font-medium">项目当月预算</span>
            <Button
              variant="ghost"
              size="xs"
              render={<Link href="/projects" />}
              className="text-muted-foreground"
            >
              全部项目
              <ArrowRight className="size-3" />
            </Button>
          </div>
          {projectsState.loading ? (
            <div className="flex flex-col gap-3 p-4">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : (projectsState.data ?? []).length === 0 ? (
            <EmptyState
              icon={<FolderKanban className="size-4" />}
              title="暂无项目"
              action={
                <Button size="sm" variant="outline" render={<Link href="/projects" />}>
                  创建项目
                </Button>
              }
            />
          ) : (
            <div className="divide-y divide-border">
              {(projectsState.data ?? []).map(({ project, budget }) => {
                const cap = budget?.hardCapMicros ?? project.monthlyHardCapMicros;
                const soft = budget?.softCapMicros ?? project.monthlySoftCapMicros;
                const used = budget?.usedMicros ?? 0;
                const held = budget?.heldMicros ?? 0;
                const softRatio =
                  cap && cap > 0 && soft && soft > 0 ? soft / cap : null;

                return (
                  <Link
                    key={project.id}
                    href={`/projects/${project.id}`}
                    className="flex flex-col gap-2 px-4 py-3 transition-colors motion-reduce:transition-none hover:bg-muted/40"
                  >
                    <div className="flex items-center justify-between gap-2 text-xs">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-foreground">
                          {project.name}
                        </span>
                        <Badge variant="outline" className="h-4 px-1.5 text-[10px]">
                          {PROJECT_ENV_LABEL[project.environment]}
                        </Badge>
                      </div>
                      <span className="font-mono text-muted-foreground tabular-nums">
                        {formatMicroUsd(used + held)}
                        {cap ? ` / ${formatMicroUsd(cap)}` : " · 无上限"}
                      </span>
                    </div>
                    {cap && cap > 0 ? (
                      <ProportionBar
                        total={cap}
                        heightClass="h-1.5"
                        markerRatio={softRatio}
                        markerLabel={
                          soft ? `软预算上限 ${formatMicroUsd(soft)}` : undefined
                        }
                        segments={[
                          {
                            key: "used",
                            label: "已消耗",
                            value: used,
                            displayValue: formatMicroUsd(used),
                            colorClass:
                              used + held >= cap
                                ? "bg-[var(--chart-5)]"
                                : "bg-[var(--chart-1)]",
                          },
                          {
                            key: "held",
                            label: "在途预占",
                            value: held,
                            displayValue: formatMicroUsd(held),
                            colorClass: "bg-[var(--chart-3)]",
                          },
                        ]}
                      />
                    ) : null}
                  </Link>
                );
              })}
            </div>
          )}
        </div>

        {/* Quick Actions */}
        <div className="squircle-md flex flex-col rounded-xl border border-border bg-card lg:col-span-5">
          <div className="border-b border-border px-4 py-3 text-sm font-medium">
            快速接入
          </div>
          <div className="flex flex-col divide-y divide-border">
            <div className="flex items-center justify-between gap-2 px-4 py-3">
              <div className="flex flex-col gap-0.5">
                <span className="text-xs text-muted-foreground">网关地址</span>
                <code className="font-mono text-xs">
                  https://api.hyperllm.dev/v1
                </code>
              </div>
              <CopyButton
                value="https://api.hyperllm.dev/v1"
                label="复制"
                copiedToast="已复制网关地址"
              />
            </div>

            {showDev ? (
              <Link
                href="/playground"
                className="flex items-center justify-between px-4 py-3 text-sm transition-colors motion-reduce:transition-none hover:bg-muted/40"
              >
                <span className="flex items-center gap-2.5">
                  <Terminal className="size-4 text-muted-foreground" />
                  <span>打开 Playground 实时调试</span>
                </span>
                <ArrowRight className="size-3.5 text-muted-foreground" />
              </Link>
            ) : null}

            {showDev ? (
              <Link
                href="/api-keys"
                className="flex items-center justify-between px-4 py-3 text-sm transition-colors motion-reduce:transition-none hover:bg-muted/40"
              >
                <span className="flex items-center gap-2.5">
                  <KeyRound className="size-4 text-muted-foreground" />
                  <span>签发与管理 API 密钥</span>
                </span>
                <ArrowRight className="size-3.5 text-muted-foreground" />
              </Link>
            ) : null}

            {showWallet ? (
              <Link
                href="/billing"
                className="flex items-center justify-between px-4 py-3 text-sm transition-colors motion-reduce:transition-none hover:bg-muted/40"
              >
                <span className="flex items-center gap-2.5">
                  <CreditCard className="size-4 text-muted-foreground" />
                  <span>在线充值 · 购包 · 兑换码</span>
                </span>
                <ArrowRight className="size-3.5 text-muted-foreground" />
              </Link>
            ) : null}
          </div>
        </div>
      </section>
    </div>
  );
}
