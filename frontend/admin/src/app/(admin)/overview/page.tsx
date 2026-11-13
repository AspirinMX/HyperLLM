"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import {
  AlertTriangle,
  ArrowRight,
  BarChart3,
  ChevronDown,
  RefreshCw,
} from "lucide-react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Line,
  XAxis,
  YAxis,
} from "recharts";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
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
import { useAsyncData } from "@/hooks/use-async-data";
import { adminAnalyticsApi, adminOperationsApi } from "@/lib/api/endpoints";
import type { PaymentChannel } from "@/lib/api/types";
import {
  formatCompactNumber,
  formatDateTime,
  formatMicroUsd,
} from "@/lib/format/micro-usd";
import { PAYMENT_CHANNEL_LABEL } from "@/lib/format/labels";
import { cn } from "@/lib/utils";

type TimeRange = "24h" | "7d" | "30d";
type MarginGroup = "CHANNEL" | "MODEL" | "ORG" | "HOUR";

const HOURS_MAP: Record<TimeRange, number> = {
  "24h": 24,
  "7d": 24 * 7,
  "30d": 24 * 30,
};

const MARGIN_CHART_CONFIG: ChartConfig = {
  cashUsd: { label: "现金收入", color: "var(--chart-1)" },
  upstreamUsd: { label: "上游成本", color: "var(--chart-5)" },
  marginPct: { label: "现金毛利率 %", color: "var(--chart-2)" },
};

const PACK_CHART_CONFIG: ChartConfig = {
  paidUsd: { label: "实收", color: "var(--chart-2)" },
  refundedUsd: { label: "退款", color: "var(--chart-5)" },
};

export default function AdminOverviewPage() {
  const [range, setRange] = useState<TimeRange>("7d");
  const [groupBy, setGroupBy] = useState<MarginGroup>("CHANNEL");

  const { fromIso, toIso } = useMemo(() => {
    const to = new Date();
    const from = new Date(to.getTime() - HOURS_MAP[range] * 3600 * 1000);
    return { fromIso: from.toISOString(), toIso: to.toISOString() };
  }, [range]);

  const marginState = useAsyncData(
    (signal) =>
      adminAnalyticsApi.margin({ from: fromIso, to: toIso, groupBy }, signal),
    [fromIso, toIso, groupBy],
  );

  const packState = useAsyncData(
    (signal) =>
      adminAnalyticsApi.packSales({ from: fromIso, to: toIso }, signal),
    [fromIso, toIso],
  );

  const rejectsState = useAsyncData(
    (signal) => adminAnalyticsApi.rejects(50, signal),
    [],
  );

  const pendingState = useAsyncData(
    async (signal) => {
      const [findings, anomalies] = await Promise.all([
        adminOperationsApi.listFindings("OPEN", 100, signal).catch(() => []),
        adminOperationsApi
          .listAnomalies({ status: "OPEN", page: 1, pageSize: 1 }, signal)
          .catch(() => ({ items: [], page: 1, pageSize: 1, total: 0 })),
      ]);
      return { findings: findings.length, anomalies: anomalies.total };
    },
    [],
  );

  const totals = useMemo(() => {
    const rows = marginState.data ?? [];
    return rows.reduce(
      (acc, r) => {
        acc.requests += r.requests;
        acc.cashMicros += r.cashMicros;
        acc.bonusMicros += r.bonusMicros;
        acc.costMicros += r.costMicros;
        acc.upstreamCostMicros += r.upstreamCostMicros;
        acc.contributionMicros += r.contributionMicros;
        acc.unknownCostRequests += r.unknownCostRequests;
        return acc;
      },
      {
        requests: 0,
        cashMicros: 0,
        bonusMicros: 0,
        costMicros: 0,
        upstreamCostMicros: 0,
        contributionMicros: 0,
        unknownCostRequests: 0,
      },
    );
  }, [marginState.data]);

  const overallMarginPct =
    totals.cashMicros > 0
      ? (totals.contributionMicros / totals.cashMicros) * 100
      : null;

  const chartRows = useMemo(() => {
    const rows = marginState.data ?? [];
    const sorted =
      groupBy === "HOUR"
        ? [...rows].sort((a, b) => a.dimension.localeCompare(b.dimension))
        : [...rows]
            .sort((a, b) => b.cashMicros - a.cashMicros)
            .slice(0, 12);

    return sorted.map((r) => {
      const marginPct =
        r.cashMicros > 0
          ? Number(((r.contributionMicros / r.cashMicros) * 100).toFixed(1))
          : 0;
      const label =
        groupBy === "HOUR"
          ? formatDateTime(r.dimension)
          : r.dimension.length > 16
            ? `${r.dimension.slice(0, 15)}…`
            : r.dimension || "未知";
      return {
        ...r,
        label,
        cashUsd: Number((r.cashMicros / 1_000_000).toFixed(4)),
        upstreamUsd: Number((r.upstreamCostMicros / 1_000_000).toFixed(4)),
        marginPct,
      };
    });
  }, [marginState.data, groupBy]);

  const packChartRows = useMemo(() => {
    const paid = packState.data?.paid ?? [];
    const refunded = packState.data?.refunded ?? [];
    const map = new Map<
      string,
      { channel: string; paidMicros: number; refundedMicros: number }
    >();

    for (const row of paid) {
      const label =
        PAYMENT_CHANNEL_LABEL[row.channel as PaymentChannel] ?? row.channel;
      const entry = map.get(label) ?? {
        channel: label,
        paidMicros: 0,
        refundedMicros: 0,
      };
      entry.paidMicros += row.creditMicros;
      map.set(label, entry);
    }
    for (const row of refunded) {
      const label =
        PAYMENT_CHANNEL_LABEL[row.channel as PaymentChannel] ?? row.channel;
      const entry = map.get(label) ?? {
        channel: label,
        paidMicros: 0,
        refundedMicros: 0,
      };
      entry.refundedMicros += row.creditMicros;
      map.set(label, entry);
    }

    return Array.from(map.values()).map((e) => ({
      channel: e.channel,
      paidUsd: Number((e.paidMicros / 1_000_000).toFixed(4)),
      refundedUsd: Number((e.refundedMicros / 1_000_000).toFixed(4)),
      paidMicros: e.paidMicros,
      refundedMicros: e.refundedMicros,
    }));
  }, [packState.data]);

  const netPackMicros = packChartRows.reduce(
    (sum, r) => sum + (r.paidMicros - r.refundedMicros),
    0,
  );

  const pendingTotal =
    (pendingState.data?.findings ?? 0) + (pendingState.data?.anomalies ?? 0);

  function refreshAll() {
    marginState.reload();
    packState.reload();
    rejectsState.reload();
    pendingState.reload();
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header Toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <Tabs
            value={range}
            onValueChange={(v) => setRange(v as TimeRange)}
          >
            <TabsList>
              <TabsTrigger value="24h">24 小时</TabsTrigger>
              <TabsTrigger value="7d">近 7 天</TabsTrigger>
              <TabsTrigger value="30d">近 30 天</TabsTrigger>
            </TabsList>
          </Tabs>

          <Tabs
            value={groupBy}
            onValueChange={(v) => setGroupBy(v as MarginGroup)}
          >
            <TabsList>
              <TabsTrigger value="CHANNEL">按渠道</TabsTrigger>
              <TabsTrigger value="MODEL">按模型</TabsTrigger>
              <TabsTrigger value="ORG">按租户</TabsTrigger>
              <TabsTrigger value="HOUR">按小时</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        <Button
          type="button"
          variant="outline"
          size="icon"
          aria-label="刷新大盘"
          onClick={refreshAll}
        >
          <RefreshCw
            className={
              marginState.refreshing
                ? "size-3.5 animate-spin motion-reduce:animate-none"
                : "size-3.5"
            }
          />
        </Button>
      </div>

      <ApiErrorAlert
        error={marginState.error ?? packState.error}
        onRetry={refreshAll}
      />

      {/* Row 1: 4-cell Financial Strip */}
      <section className="squircle-md grid divide-y divide-border overflow-hidden rounded-xl border border-border bg-card sm:grid-cols-2 lg:grid-cols-4 lg:divide-x lg:divide-y-0">
        <div className="flex flex-col gap-2.5 p-4">
          <span className="text-xs text-muted-foreground">平台总消费营收</span>
          <span className="text-2xl font-semibold tabular-nums">
            {formatMicroUsd(totals.costMicros)}
          </span>
          <ProportionBar
            heightClass="h-1.5"
            segments={[
              {
                key: "cash",
                label: "现金实扣",
                value: totals.cashMicros,
                displayValue: formatMicroUsd(totals.cashMicros),
                colorClass: "bg-[var(--chart-2)]",
              },
              {
                key: "bonus",
                label: "赠金抵扣",
                value: totals.bonusMicros,
                displayValue: formatMicroUsd(totals.bonusMicros),
                colorClass: "bg-[var(--chart-3)]",
              },
            ]}
          />
          <div className="flex items-center justify-between text-[11px] text-muted-foreground tabular-nums">
            <span>现金 {formatMicroUsd(totals.cashMicros)}</span>
            <span>请求 {formatCompactNumber(totals.requests)}</span>
          </div>
        </div>

        <div className="flex flex-col justify-between gap-2 p-4">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>上游采购成本</span>
            {totals.unknownCostRequests > 0 ? (
              <Badge
                variant="secondary"
                className="h-4 gap-1 px-1.5 text-[10px] text-amber-700 dark:text-amber-400"
              >
                <AlertTriangle className="size-2.5" />
                {formatCompactNumber(totals.unknownCostRequests)} 未知
              </Badge>
            ) : null}
          </div>
          <span className="text-2xl font-semibold tabular-nums">
            {formatMicroUsd(totals.upstreamCostMicros)}
          </span>
          <span className="text-[11px] text-muted-foreground">
            按渠道实分派折扣计量
          </span>
        </div>

        <div className="flex flex-col justify-between gap-2 p-4">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>现金贡献毛利</span>
            {overallMarginPct !== null ? (
              <Badge
                variant="secondary"
                className={cn(
                  "h-5 px-1.5 font-mono text-[11px] tabular-nums",
                  overallMarginPct >= 20
                    ? "text-emerald-700 dark:text-emerald-400"
                    : overallMarginPct >= 0
                      ? "text-amber-700 dark:text-amber-400"
                      : "text-rose-700 dark:text-rose-400",
                )}
              >
                {overallMarginPct.toFixed(1)}%
              </Badge>
            ) : null}
          </div>
          <span className="text-2xl font-semibold tabular-nums">
            {formatMicroUsd(totals.contributionMicros, { signed: true })}
          </span>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
            <div
              className={cn(
                "h-full rounded-full",
                (overallMarginPct ?? 0) >= 20
                  ? "bg-[var(--chart-2)]"
                  : (overallMarginPct ?? 0) >= 0
                    ? "bg-[var(--chart-3)]"
                    : "bg-[var(--chart-5)]",
              )}
              style={{
                width: `${Math.min(100, Math.max(0, overallMarginPct ?? 0))}%`,
              }}
            />
          </div>
        </div>

        <div className="flex flex-col justify-between gap-2 p-4">
          <span className="text-xs text-muted-foreground">资源包净销售</span>
          <span className="text-2xl font-semibold tabular-nums">
            {formatMicroUsd(netPackMicros)}
          </span>
          <Link
            href="/operations"
            className="flex items-center justify-between text-[11px] text-muted-foreground hover:text-foreground"
          >
            <span>
              待处理巡检{" "}
              <strong
                className={cn(
                  "font-mono tabular-nums",
                  pendingTotal > 0 && "text-rose-600 dark:text-rose-400",
                )}
              >
                {pendingTotal}
              </strong>
            </span>
            <ArrowRight className="size-3" />
          </Link>
        </div>
      </section>

      {/* Row 2: 7:5 Financial Charts */}
      <section className="grid gap-5 lg:grid-cols-12">
        <div className="squircle-md flex flex-col rounded-xl border border-border bg-card lg:col-span-7">
          <div className="border-b border-border px-4 py-3 text-sm font-medium">
            现金收入 · 采购成本 · 毛利率
          </div>
          <div className="p-4">
            {marginState.loading ? (
              <Skeleton className="h-64 w-full" />
            ) : chartRows.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="该时段暂无毛利数据"
              />
            ) : (
              <ChartContainer
                config={MARGIN_CHART_CONFIG}
                className="h-64 w-full aspect-auto"
              >
                <ComposedChart data={chartRows}>
                  <CartesianGrid vertical={false} strokeDasharray="3 3" />
                  <XAxis
                    dataKey="label"
                    tickLine={false}
                    axisLine={false}
                    minTickGap={20}
                  />
                  <YAxis
                    yAxisId="left"
                    tickLine={false}
                    axisLine={false}
                    width={52}
                    tickFormatter={(v) => `$${Number(v).toFixed(2)}`}
                  />
                  <YAxis
                    yAxisId="right"
                    orientation="right"
                    tickLine={false}
                    axisLine={false}
                    width={42}
                    tickFormatter={(v) => `${v}%`}
                  />
                  <ChartTooltip content={<ChartTooltipContent />} />
                  <ChartLegend content={<ChartLegendContent />} />
                  <Bar
                    yAxisId="left"
                    dataKey="cashUsd"
                    fill="var(--color-cashUsd)"
                    radius={[3, 3, 0, 0]}
                  />
                  <Bar
                    yAxisId="left"
                    dataKey="upstreamUsd"
                    fill="var(--color-upstreamUsd)"
                    radius={[3, 3, 0, 0]}
                  />
                  <Line
                    yAxisId="right"
                    type="monotone"
                    dataKey="marginPct"
                    stroke="var(--color-marginPct)"
                    strokeWidth={2}
                    dot={{ r: 2.5 }}
                  />
                </ComposedChart>
              </ChartContainer>
            )}
          </div>
        </div>

        <div className="squircle-md flex flex-col rounded-xl border border-border bg-card lg:col-span-5">
          <div className="border-b border-border px-4 py-3 text-sm font-medium">
            资源包渠道实收与退款
          </div>
          <div className="p-4">
            {packState.loading ? (
              <Skeleton className="h-64 w-full" />
            ) : packChartRows.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="该时段暂无资源包成交"
              />
            ) : (
              <ChartContainer
                config={PACK_CHART_CONFIG}
                className="h-64 w-full aspect-auto"
              >
                <BarChart
                  data={packChartRows}
                  layout="vertical"
                  margin={{ left: 0, right: 12, top: 8, bottom: 8 }}
                >
                  <XAxis
                    type="number"
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(v) => `$${Number(v).toFixed(0)}`}
                  />
                  <YAxis
                    type="category"
                    dataKey="channel"
                    width={64}
                    tickLine={false}
                    axisLine={false}
                  />
                  <ChartTooltip
                    content={
                      <ChartTooltipContent
                        formatter={(v) => `$${Number(v).toFixed(2)}`}
                      />
                    }
                  />
                  <ChartLegend content={<ChartLegendContent />} />
                  <Bar
                    dataKey="paidUsd"
                    fill="var(--color-paidUsd)"
                    radius={[0, 4, 4, 0]}
                  />
                  <Bar
                    dataKey="refundedUsd"
                    fill="var(--color-refundedUsd)"
                    radius={[0, 4, 4, 0]}
                  />
                </BarChart>
              </ChartContainer>
            )}
          </div>
        </div>
      </section>

      {/* Row 3: Margin Table & Ingest Rejects */}
      <Tabs defaultValue="margin" className="gap-4">
        <TabsList>
          <TabsTrigger value="margin">毛利明细排行</TabsTrigger>
          <TabsTrigger value="rejects">
            摄入拒收记录（{rejectsState.data?.length ?? 0}）
          </TabsTrigger>
        </TabsList>

        <TabsContent value="margin">
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {chartRows.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="暂无明细数据"
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>维度</TableHead>
                    <TableHead className="text-right">请求数</TableHead>
                    <TableHead className="text-right">现金收入</TableHead>
                    <TableHead className="text-right">赠金抵扣</TableHead>
                    <TableHead className="text-right">上游成本</TableHead>
                    <TableHead className="w-44 text-right">贡献毛利</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {chartRows.map((row) => (
                    <TableRow key={row.dimension}>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{row.label}</span>
                          {row.unknownCostRequests > 0 ? (
                            <Badge
                              variant="outline"
                              className="h-4 px-1 text-[10px] text-amber-700 dark:text-amber-400"
                            >
                              {row.unknownCostRequests} 未知成本
                            </Badge>
                          ) : null}
                        </div>
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatCompactNumber(row.requests)}
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatMicroUsd(row.cashMicros)}
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs text-muted-foreground tabular-nums">
                        {formatMicroUsd(row.bonusMicros)}
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatMicroUsd(row.upstreamCostMicros)}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex flex-col items-end gap-1">
                          <div className="flex items-center gap-1.5 font-mono text-xs tabular-nums">
                            <span>
                              {formatMicroUsd(row.contributionMicros, {
                                signed: true,
                              })}
                            </span>
                            <span
                              className={cn(
                                "text-[11px]",
                                row.marginPct >= 20
                                  ? "text-emerald-600 dark:text-emerald-400"
                                  : row.marginPct >= 0
                                    ? "text-amber-600 dark:text-amber-400"
                                    : "text-rose-600 dark:text-rose-400",
                              )}
                            >
                              {row.marginPct.toFixed(1)}%
                            </span>
                          </div>
                          <div className="h-1 w-24 overflow-hidden rounded-full bg-muted">
                            <div
                              className={cn(
                                "h-full rounded-full",
                                row.marginPct >= 20
                                  ? "bg-[var(--chart-2)]"
                                  : row.marginPct >= 0
                                    ? "bg-[var(--chart-3)]"
                                    : "bg-[var(--chart-5)]",
                              )}
                              style={{
                                width: `${Math.min(100, Math.max(4, Math.abs(row.marginPct)))}%`,
                              }}
                            />
                          </div>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
        </TabsContent>

        <TabsContent value="rejects">
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {(rejectsState.data ?? []).length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="暂无摄入拒收记录"
              />
            ) : (
              <div className="divide-y divide-border">
                {(rejectsState.data ?? []).map((row) => (
                  <Collapsible
                    key={`${row.partition}-${row.offset}`}
                    className="px-4 py-3"
                  >
                    <CollapsibleTrigger className="flex w-full items-center justify-between gap-3 text-left text-xs">
                      <div className="flex items-center gap-2.5">
                        <Badge variant="outline" className="font-mono text-[10px]">
                          {row.errorClass}
                        </Badge>
                        <span className="font-mono text-muted-foreground">
                          P{row.partition} · #{row.offset}
                        </span>
                      </div>
                      <div className="flex items-center gap-2 text-muted-foreground tabular-nums">
                        <span>{formatDateTime(row.createdAt)}</span>
                        <ChevronDown className="size-3.5" />
                      </div>
                    </CollapsibleTrigger>
                    <CollapsibleContent className="mt-2 rounded-lg bg-muted/45 p-3 font-mono text-xs text-muted-foreground">
                      {row.detail}
                    </CollapsibleContent>
                  </Collapsible>
                ))}
              </div>
            )}
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}
