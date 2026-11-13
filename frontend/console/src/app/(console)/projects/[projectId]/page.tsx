"use client";

import { Suspense, use, useMemo, useState } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  BarChart3,
  KeyRound,
  Loader2,
  Save,
  Users,
} from "lucide-react";
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from "recharts";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
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
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { EmptyState } from "@/components/common/empty-state";
import { ProportionBar } from "@/components/common/proportion-bar";
import { StatusDot } from "@/components/common/status-dot";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { apiKeyApi, orgApi, projectApi, usageApi } from "@/lib/api/endpoints";
import {
  formatDateTime,
  formatMicroUsd,
} from "@/lib/format/micro-usd";
import {
  API_KEY_STATUS_LABEL,
  canManageOrg,
  ORG_ROLE_LABEL,
  PROJECT_ENV_LABEL,
  PROJECT_STATUS_LABEL,
} from "@/lib/format/labels";

const USAGE_CHART_CONFIG: ChartConfig = {
  costUsd: { label: "消费 (USD)", color: "var(--chart-1)" },
};

function ProjectDetailContent({
  params,
}: {
  params: Promise<{ projectId: string }>;
}) {
  const { projectId } = use(params);
  const { currentOrg } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;
  const canEdit = canManageOrg(currentOrg?.role);

  const projectState = useAsyncData(
    (signal) => projectApi.get(projectId, signal),
    [projectId],
    Boolean(projectId),
  );

  const budgetState = useAsyncData(
    (signal) => projectApi.getBudget(projectId, signal),
    [projectId],
    Boolean(projectId),
  );

  const keysState = useAsyncData(
    (signal) => apiKeyApi.list(projectId, 1, 50, signal),
    [projectId],
    Boolean(projectId),
  );

  const membersState = useAsyncData(
    (signal) => orgApi.listMembers(orgId!, signal),
    [orgId],
    Boolean(orgId) && canEdit,
  );

  const { fromIso, toIso } = useMemo(() => {
    const to = new Date();
    const from = new Date(to.getTime() - 7 * 24 * 3600 * 1000);
    return { fromIso: from.toISOString(), toIso: to.toISOString() };
  }, []);

  const usageState = useAsyncData(
    (signal) =>
      usageApi.query(
        orgId!,
        { from: fromIso, to: toIso, projectId, groupBy: "HOUR" },
        signal,
      ),
    [orgId, projectId],
    Boolean(orgId),
  );

  const [selectedUserIds, setSelectedUserIds] = useState<string[] | null>(null);
  const [savingMembers, setSavingMembers] = useState(false);

  const activeUserIds = useMemo(() => {
    if (selectedUserIds !== null) return selectedUserIds;
    return (membersState.data ?? [])
      .filter((m) => m.role === "DEVELOPER")
      .map((m) => m.userId);
  }, [selectedUserIds, membersState.data]);

  function toggleMember(userId: string) {
    setSelectedUserIds((prev) => {
      const base = prev ?? activeUserIds;
      return base.includes(userId)
        ? base.filter((id) => id !== userId)
        : [...base, userId];
    });
  }

  async function handleSaveMembers() {
    setSavingMembers(true);
    try {
      await projectApi.replaceMembers(projectId, activeUserIds);
      toast.success("项目成员授权已保存");
    } catch {
      toast.error("保存成员授权失败");
    } finally {
      setSavingMembers(false);
    }
  }

  const project = projectState.data;
  const budget = budgetState.data;

  const usageSeries = useMemo(() => {
    return [...(usageState.data?.rows ?? [])]
      .sort((a, b) => a.dimension.localeCompare(b.dimension))
      .map((r) => ({
        label: formatDateTime(r.dimension),
        costUsd: Number((r.costMicros / 1_000_000).toFixed(6)),
      }));
  }, [usageState.data]);

  const cap = budget?.hardCapMicros ?? project?.monthlyHardCapMicros ?? null;
  const soft = budget?.softCapMicros ?? project?.monthlySoftCapMicros ?? null;
  const used = budget?.usedMicros ?? 0;
  const held = budget?.heldMicros ?? 0;
  const softRatio = cap && cap > 0 && soft && soft > 0 ? soft / cap : null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon-sm"
            render={<Link href="/projects" />}
            aria-label="返回项目列表"
          >
            <ArrowLeft className="size-4" />
          </Button>
          {project ? (
            <div className="flex items-center gap-2">
              <h1 className="text-lg font-semibold tracking-tight">
                {project.name}
              </h1>
              <Badge variant="outline">
                {PROJECT_ENV_LABEL[project.environment]}
              </Badge>
              <StatusDot
                tone={project.status === "ACTIVE" ? "success" : "warning"}
                label={PROJECT_STATUS_LABEL[project.status]}
              />
            </div>
          ) : (
            <Skeleton className="h-6 w-40" />
          )}
        </div>

        <Button
          size="sm"
          variant="outline"
          render={<Link href={`/api-keys?projectId=${projectId}`} />}
        >
          <KeyRound className="size-3.5" />
          <span>管理密钥</span>
        </Button>
      </div>

      <ApiErrorAlert error={projectState.error} onRetry={projectState.reload} />

      {/* Monthly Budget Visual Gauge Panel */}
      <section className="squircle-md flex flex-col gap-4 rounded-xl border border-border bg-card p-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium">
              当月预算{budget ? `（${budget.month}）` : ""}
            </span>
            {budget ? (
              <StatusDot
                tone={
                  budget.state === "UNVERIFIED"
                    ? "danger"
                    : budget.source === "LIVE"
                      ? "success"
                      : "muted"
                }
                label={
                  budget.state === "UNVERIFIED"
                    ? "待核验拦截"
                    : budget.source === "LIVE"
                      ? "实时同步"
                      : "账本确认"
                }
              />
            ) : null}
          </div>
          <span className="font-mono text-sm font-semibold tabular-nums">
            {formatMicroUsd(used + held)}
            {cap ? ` / ${formatMicroUsd(cap)}` : " · 未设硬上限"}
          </span>
        </div>

        {cap && cap > 0 ? (
          <ProportionBar
            total={cap}
            heightClass="h-2.5"
            markerRatio={softRatio}
            markerLabel={soft ? `软上限 ${formatMicroUsd(soft)}` : undefined}
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

        <div className="grid grid-cols-2 gap-3 text-xs sm:grid-cols-4">
          <div className="flex flex-col gap-0.5">
            <span className="text-muted-foreground">已消耗</span>
            <span className="font-mono font-medium tabular-nums">
              {formatMicroUsd(used)}
            </span>
          </div>
          <div className="flex flex-col gap-0.5">
            <span className="text-muted-foreground">在途预占</span>
            <span className="font-mono font-medium tabular-nums">
              {formatMicroUsd(held)}
            </span>
          </div>
          <div className="flex flex-col gap-0.5">
            <span className="text-muted-foreground">剩余可用</span>
            <span className="font-mono font-medium tabular-nums">
              {budget?.availableMicros !== null &&
              budget?.availableMicros !== undefined
                ? formatMicroUsd(budget.availableMicros)
                : "不限"}
            </span>
          </div>
          <div className="flex flex-col gap-0.5">
            <span className="text-muted-foreground">软预警上限</span>
            <span className="font-mono font-medium tabular-nums">
              {soft ? formatMicroUsd(soft) : "未配置"}
            </span>
          </div>
        </div>
      </section>

      {/* Tabs: Usage / Keys / Members */}
      <Tabs defaultValue="usage" className="gap-4">
        <TabsList>
          <TabsTrigger value="usage">近 7 天用量</TabsTrigger>
          <TabsTrigger value="keys">
            API 密钥（{keysState.data?.total ?? 0}）
          </TabsTrigger>
          {canEdit ? <TabsTrigger value="members">成员授权</TabsTrigger> : null}
        </TabsList>

        <TabsContent value="usage">
          <div className="squircle-md rounded-xl border border-border bg-card p-4">
            {usageState.loading ? (
              <Skeleton className="h-56 w-full" />
            ) : usageSeries.length === 0 ? (
              <EmptyState
                icon={<BarChart3 className="size-4" />}
                title="近 7 天暂无项目调用数据"
              />
            ) : (
              <ChartContainer
                config={USAGE_CHART_CONFIG}
                className="h-56 w-full aspect-auto"
              >
                <AreaChart data={usageSeries}>
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
                    fillOpacity={0.16}
                    strokeWidth={2}
                  />
                </AreaChart>
              </ChartContainer>
            )}
          </div>
        </TabsContent>

        <TabsContent value="keys">
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {(keysState.data?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<KeyRound className="size-4" />}
                title="该项目下暂无 API 密钥"
                action={
                  <Button
                    size="sm"
                    variant="outline"
                    render={<Link href={`/api-keys?projectId=${projectId}`} />}
                  >
                    前往签发
                  </Button>
                }
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>名称</TableHead>
                    <TableHead>前缀</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>最近使用</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(keysState.data?.items ?? []).map((key) => (
                    <TableRow key={key.id}>
                      <TableCell className="font-medium">{key.name}</TableCell>
                      <TableCell className="font-mono text-xs text-muted-foreground">
                        {key.keyPrefix}…
                      </TableCell>
                      <TableCell>
                        <StatusDot
                          tone={
                            key.status === "ACTIVE"
                              ? "success"
                              : key.status === "DISABLED"
                                ? "warning"
                                : "muted"
                          }
                          label={API_KEY_STATUS_LABEL[key.status]}
                        />
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground tabular-nums">
                        {formatDateTime(key.lastUsedAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
        </TabsContent>

        {canEdit ? (
          <TabsContent value="members">
            <div className="squircle-md flex flex-col rounded-xl border border-border bg-card">
              <div className="flex items-center justify-between border-b border-border px-4 py-3">
                <span className="text-sm font-medium">项目访问成员</span>
                <Button
                  size="sm"
                  disabled={savingMembers}
                  onClick={handleSaveMembers}
                >
                  {savingMembers ? (
                    <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                  ) : (
                    <Save className="size-3.5" />
                  )}
                  <span>保存授权</span>
                </Button>
              </div>
              {(membersState.data ?? []).length === 0 ? (
                <EmptyState
                  icon={<Users className="size-4" />}
                  title="暂无可分配成员"
                />
              ) : (
                <div className="divide-y divide-border">
                  {(membersState.data ?? []).map((m) => {
                    const isElevated = m.role === "OWNER" || m.role === "ADMIN";
                    const checked =
                      isElevated || activeUserIds.includes(m.userId);
                    return (
                      <label
                        key={m.memberId}
                        className="flex cursor-pointer items-center justify-between gap-3 px-4 py-3 text-sm hover:bg-muted/35"
                      >
                        <div className="flex items-center gap-3">
                          <Checkbox
                            checked={checked}
                            disabled={isElevated}
                            onCheckedChange={() => toggleMember(m.userId)}
                          />
                          <div className="flex flex-col">
                            <span className="font-medium">{m.displayName}</span>
                            <span className="text-xs text-muted-foreground">
                              {m.email}
                            </span>
                          </div>
                        </div>
                        <Badge variant="outline">
                          {ORG_ROLE_LABEL[m.role]}
                        </Badge>
                      </label>
                    );
                  })}
                </div>
              )}
            </div>
          </TabsContent>
        ) : null}
      </Tabs>
    </div>
  );
}

export default function ProjectDetailPage({
  params,
}: {
  params: Promise<{ projectId: string }>;
}) {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-4">
          <Skeleton className="h-7 w-48" />
          <Skeleton className="h-32 w-full" />
        </div>
      }
    >
      <ProjectDetailContent params={params} />
    </Suspense>
  );
}
