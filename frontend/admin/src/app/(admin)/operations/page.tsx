"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import {
  Activity,
  AlertTriangle,
  Bell,
  ChevronDown,
  FileClock,
  Loader2,
  Play,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldAlert,
  Wrench,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
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
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { EmptyState } from "@/components/common/empty-state";
import { StatusDot } from "@/components/common/status-dot";
import { useAdminAuth } from "@/components/providers/admin-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminOperationsApi } from "@/lib/api/endpoints";
import type {
  AlertDeliveryDto,
  AuditLogDto,
  PaymentAnomalyDto,
  PaymentAnomalyStatus,
  ReconciliationFinding,
} from "@/lib/api/types";
import {
  formatDateTime,
  formatFullDateTime,
  formatMicroUsd,
  parseUsdToMicros,
} from "@/lib/format/micro-usd";
import {
  ANOMALY_KIND_LABEL,
  ANOMALY_STATUS_LABEL,
  canFinanceWrite,
  PAYMENT_CHANNEL_LABEL,
} from "@/lib/format/labels";
import { cn } from "@/lib/utils";

const CHECK_KIND_LABEL: Record<string, string> = {
  LEDGER_VS_WALLET: "账本与钱包快照偏差",
  BATCH_VS_WALLET: "赠金批次与快照偏差",
  WALLET_VS_REDIS: "数据库与实时镜像偏差",
  SHORTFALL_AUDIT: "短缺与回收异常",
};

const DELIVERY_STATUS_LABEL: Record<string, string> = {
  PENDING: "排队中",
  DELIVERED: "已送达",
  RETRYING: "重试中",
  DEAD: "死信失败",
};

const ACTOR_TYPE_LABEL: Record<string, string> = {
  ADMIN: "管理员",
  USER: "开发者",
  SYSTEM: "系统",
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function formatPrettyJson(raw: string | null): string {
  if (!raw) return "";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

export default function AdminOperationsPage() {
  const { profile } = useAdminAuth();
  const canResolveFinance = canFinanceWrite(profile?.role);

  const [tab, setTab] = useState<
    "reconcile" | "anomalies" | "deliveries" | "audit"
  >("reconcile");
  const [findingStateFilter, setFindingStateFilter] = useState<
    "OPEN" | "RESOLVED"
  >("OPEN");
  const [anomalyStatusFilter, setAnomalyStatusFilter] =
    useState<PaymentAnomalyStatus>("OPEN");
  const [deliveryStatusFilter, setDeliveryStatusFilter] =
    useState<string>("ALL");

  // Audit log query filters & pagination
  const [auditActionDraft, setAuditActionDraft] = useState("");
  const [auditOrgDraft, setAuditOrgDraft] = useState("");
  const [auditActionFilter, setAuditActionFilter] = useState("");
  const [auditOrgFilter, setAuditOrgFilter] = useState("");
  const [auditPage, setAuditPage] = useState(1);
  const AUDIT_PAGE_SIZE = 20;
  const [inspectAudit, setInspectAudit] = useState<AuditLogDto | null>(null);

  const findingsState = useAsyncData(
    (signal) => adminOperationsApi.listFindings(findingStateFilter, 100, signal),
    [findingStateFilter],
  );

  const anomaliesState = useAsyncData(
    (signal) =>
      adminOperationsApi.listAnomalies(
        { status: anomalyStatusFilter, page: 1, pageSize: 50 },
        signal,
      ),
    [anomalyStatusFilter],
  );

  const deliveriesState = useAsyncData(
    (signal) =>
      adminOperationsApi.listAlertDeliveries(
        {
          status:
            deliveryStatusFilter === "ALL" ? undefined : deliveryStatusFilter,
          limit: 100,
        },
        signal,
      ),
    [deliveryStatusFilter],
  );

  const auditLogsState = useAsyncData(
    (signal) =>
      adminOperationsApi.listAuditLogs(
        {
          orgId: auditOrgFilter || undefined,
          action: auditActionFilter || undefined,
          page: auditPage,
          pageSize: AUDIT_PAGE_SIZE,
        },
        signal,
      ),
    [auditOrgFilter, auditActionFilter, auditPage],
  );

  // Reconciliation actions
  const [runningReconcile, setRunningReconcile] = useState(false);
  const [inspectFinding, setInspectFinding] =
    useState<ReconciliationFinding | null>(null);
  const [findingNote, setFindingNote] = useState("");
  const [resolvingFinding, setResolvingFinding] = useState(false);

  // Budget month repair dialog
  const [budgetDialogOpen, setBudgetDialogOpen] = useState(false);
  const [budgetOrgId, setBudgetOrgId] = useState("");
  const [budgetProjectId, setBudgetProjectId] = useState("");
  const [budgetMonth, setBudgetMonth] = useState(() =>
    new Date().toISOString().slice(0, 7),
  );
  const [budgetConfirmedUsd, setBudgetConfirmedUsd] = useState("");
  const [resolvingBudget, setResolvingBudget] = useState(false);
  const [budgetError, setBudgetError] = useState<unknown>(null);

  // Anomaly inspection sheet
  const [inspectAnomaly, setInspectAnomaly] =
    useState<PaymentAnomalyDto | null>(null);
  const [anomalyResolutionStatus, setAnomalyResolutionStatus] =
    useState<PaymentAnomalyStatus>("RESOLVED");
  const [anomalyNote, setAnomalyNote] = useState("");
  const [resolvingAnomaly, setResolvingAnomaly] = useState(false);

  // Delivery retry
  const [retryingDeliveryId, setRetryingDeliveryId] = useState<string | null>(
    null,
  );

  async function handleRunReconcile() {
    setRunningReconcile(true);
    try {
      const res = await adminOperationsApi.runReconciliation();
      toast.success(`巡检完成，共核查 ${res.checkedOrganisations} 个组织`);
      findingsState.reload();
    } catch {
      toast.error("触发巡检失败");
    } finally {
      setRunningReconcile(false);
    }
  }

  async function handleResolveFinding(e: FormEvent) {
    e.preventDefault();
    if (!inspectFinding) return;
    setResolvingFinding(true);
    try {
      await adminOperationsApi.resolveFinding(
        inspectFinding.id,
        findingNote.trim(),
      );
      toast.success("对账工单已关闭");
      setInspectFinding(null);
      setFindingNote("");
      findingsState.reload();
    } catch {
      toast.error("关闭工单失败");
    } finally {
      setResolvingFinding(false);
    }
  }

  async function handleResolveBudget(e: FormEvent) {
    e.preventDefault();
    setBudgetError(null);
    const micros = parseUsdToMicros(budgetConfirmedUsd);
    if (micros === null) {
      toast.error("请输入有效的核定金额");
      return;
    }

    setResolvingBudget(true);
    try {
      await adminOperationsApi.resolveBudgetMonth(
        budgetOrgId.trim(),
        budgetProjectId.trim(),
        budgetMonth.trim(),
        micros,
      );
      toast.success("项目预算月已核验并恢复准入");
      setBudgetDialogOpen(false);
      setBudgetOrgId("");
      setBudgetProjectId("");
      setBudgetConfirmedUsd("");
    } catch (err) {
      setBudgetError(err);
    } finally {
      setResolvingBudget(false);
    }
  }

  async function handleResolveAnomaly(e: FormEvent) {
    e.preventDefault();
    if (!inspectAnomaly || !anomalyNote.trim()) return;
    setResolvingAnomaly(true);
    try {
      await adminOperationsApi.resolveAnomaly(
        inspectAnomaly.id,
        anomalyResolutionStatus,
        anomalyNote.trim(),
      );
      toast.success("支付异常工单已处理");
      setInspectAnomaly(null);
      setAnomalyNote("");
      anomaliesState.reload();
    } catch {
      toast.error("处理失败，请检查权限后重试");
    } finally {
      setResolvingAnomaly(false);
    }
  }

  async function handleRetryDelivery(item: AlertDeliveryDto) {
    setRetryingDeliveryId(item.id);
    try {
      await adminOperationsApi.retryAlertDelivery(item.id);
      toast.success("已重新加入投递队列");
      deliveriesState.reload();
    } catch {
      toast.error("仅重试中或死信记录可重新入队");
    } finally {
      setRetryingDeliveryId(null);
    }
  }

  function handleApplyAuditFilter(e: FormEvent) {
    e.preventDefault();
    const trimmedOrg = auditOrgDraft.trim();
    if (trimmedOrg && !UUID_PATTERN.test(trimmedOrg)) {
      toast.error("组织 ID 必须为合法 UUID 格式");
      return;
    }
    setAuditOrgFilter(trimmedOrg);
    setAuditActionFilter(auditActionDraft.trim());
    setAuditPage(1);
  }

  function handleResetAuditFilter() {
    setAuditOrgDraft("");
    setAuditActionDraft("");
    setAuditOrgFilter("");
    setAuditActionFilter("");
    setAuditPage(1);
  }

  const auditTotal = auditLogsState.data?.total ?? 0;
  const auditTotalPages = Math.max(1, Math.ceil(auditTotal / AUDIT_PAGE_SIZE));

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs
          value={tab}
          onValueChange={(v) =>
            setTab(v as "reconcile" | "anomalies" | "deliveries" | "audit")
          }
        >
          <TabsList>
            <TabsTrigger value="reconcile">
              账务对账（{findingsState.data?.length ?? 0}）
            </TabsTrigger>
            <TabsTrigger value="anomalies">
              支付异常（{anomaliesState.data?.total ?? 0}）
            </TabsTrigger>
            <TabsTrigger value="deliveries">
              告警投递（{deliveriesState.data?.length ?? 0}）
            </TabsTrigger>
            <TabsTrigger value="audit">
              审计日志（{auditTotal}）
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <div className="flex items-center gap-2">
          {tab === "reconcile" ? (
            <>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setBudgetError(null);
                  setBudgetDialogOpen(true);
                }}
              >
                <Wrench className="size-3.5" />
                <span>核验项目月预算</span>
              </Button>
              <Button
                size="sm"
                disabled={runningReconcile}
                onClick={handleRunReconcile}
              >
                {runningReconcile ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : (
                  <Play className="size-3.5" />
                )}
                <span>立即巡检</span>
              </Button>
            </>
          ) : (
            <Button
              size="icon-sm"
              variant="outline"
              aria-label="刷新"
              onClick={() => {
                anomaliesState.reload();
                deliveriesState.reload();
                auditLogsState.reload();
              }}
            >
              <RefreshCw className="size-3.5" />
            </Button>
          )}
        </div>
      </div>

      <ApiErrorAlert
        error={
          findingsState.error ??
          anomaliesState.error ??
          deliveriesState.error ??
          auditLogsState.error
        }
      />

      <Tabs value={tab} className="gap-4">
        {/* Tab 1: Reconciliation Findings */}
        <TabsContent value="reconcile" className="flex flex-col gap-4">
          <Tabs
            value={findingStateFilter}
            onValueChange={(v) =>
              setFindingStateFilter(v as "OPEN" | "RESOLVED")
            }
          >
            <TabsList>
              <TabsTrigger value="OPEN">待处理</TabsTrigger>
              <TabsTrigger value="RESOLVED">已关闭</TabsTrigger>
            </TabsList>
          </Tabs>

          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {findingsState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : (findingsState.data ?? []).length === 0 ? (
              <EmptyState
                icon={<Activity className="size-4" />}
                title={
                  findingStateFilter === "OPEN"
                    ? "账务一致，暂无待处理异常"
                    : "暂无历史关闭工单"
                }
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>级别 / 检查项</TableHead>
                    <TableHead>关联租户</TableHead>
                    <TableHead className="text-right">期望值</TableHead>
                    <TableHead className="text-right">实际值</TableHead>
                    <TableHead className="text-right">差额</TableHead>
                    <TableHead className="text-right">发现时间</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(findingsState.data ?? []).map((item) => (
                    <TableRow
                      key={item.id}
                      className="cursor-pointer"
                      onClick={() => {
                        setInspectFinding(item);
                        setFindingNote(item.resolutionNote ?? "");
                      }}
                    >
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <Badge
                            variant={
                              item.severity === "P1" ? "destructive" : "secondary"
                            }
                            className="h-5 px-1.5 text-[10px]"
                          >
                            {item.severity}
                          </Badge>
                          <span className="font-medium">
                            {CHECK_KIND_LABEL[item.checkKind] ?? item.checkKind}
                          </span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Link
                          href={`/tenants/${item.orgId}`}
                          onClick={(e) => e.stopPropagation()}
                          className="font-mono text-xs text-muted-foreground hover:text-foreground hover:underline"
                        >
                          查看租户钱包 →
                        </Link>
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatMicroUsd(item.expectedMicros)}
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatMicroUsd(item.actualMicros)}
                      </TableCell>
                      <TableCell
                        className={cn(
                          "text-right font-mono text-xs font-medium tabular-nums",
                          item.deltaMicros && item.deltaMicros !== 0
                            ? "text-rose-600 dark:text-rose-400"
                            : "text-muted-foreground",
                        )}
                      >
                        {formatMicroUsd(item.deltaMicros, { signed: true })}
                      </TableCell>
                      <TableCell className="text-right text-xs text-muted-foreground tabular-nums">
                        {formatDateTime(item.lastSeenAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
        </TabsContent>

        {/* Tab 2: Payment Anomalies */}
        <TabsContent value="anomalies" className="flex flex-col gap-4">
          <Tabs
            value={anomalyStatusFilter}
            onValueChange={(v) =>
              setAnomalyStatusFilter(v as PaymentAnomalyStatus)
            }
          >
            <TabsList>
              <TabsTrigger value="OPEN">待处理</TabsTrigger>
              <TabsTrigger value="RESOLVED">已解决</TabsTrigger>
              <TabsTrigger value="IGNORED">已忽略</TabsTrigger>
            </TabsList>
          </Tabs>

          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {anomaliesState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : (anomaliesState.data?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<ShieldAlert className="size-4" />}
                title="暂无支付异常工单"
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>异常类型</TableHead>
                    <TableHead>渠道</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead className="text-right">记录时间</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(anomaliesState.data?.items ?? []).map((item) => (
                    <TableRow
                      key={item.id}
                      className="cursor-pointer"
                      onClick={() => {
                        setInspectAnomaly(item);
                        setAnomalyResolutionStatus("RESOLVED");
                        setAnomalyNote("");
                      }}
                    >
                      <TableCell className="font-medium">
                        {ANOMALY_KIND_LABEL[item.kind]}
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline">
                          {PAYMENT_CHANNEL_LABEL[item.paymentChannel]}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <StatusDot
                          tone={
                            item.status === "OPEN"
                              ? "danger"
                              : item.status === "RESOLVED"
                                ? "success"
                                : "muted"
                          }
                          label={ANOMALY_STATUS_LABEL[item.status]}
                        />
                      </TableCell>
                      <TableCell className="text-right text-xs text-muted-foreground tabular-nums">
                        {formatDateTime(item.createdAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
        </TabsContent>

        {/* Tab 3: Alert Deliveries */}
        <TabsContent value="deliveries" className="flex flex-col gap-4">
          <Tabs
            value={deliveryStatusFilter}
            onValueChange={setDeliveryStatusFilter}
          >
            <TabsList>
              <TabsTrigger value="ALL">全部</TabsTrigger>
              <TabsTrigger value="RETRYING">重试中</TabsTrigger>
              <TabsTrigger value="DEAD">死信失败</TabsTrigger>
              <TabsTrigger value="DELIVERED">已送达</TabsTrigger>
            </TabsList>
          </Tabs>

          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {deliveriesState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : (deliveriesState.data ?? []).length === 0 ? (
              <EmptyState
                icon={<Bell className="size-4" />}
                title="暂无告警投递记录"
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>主题</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead className="text-right">尝试次数</TableHead>
                    <TableHead>下次重试 / 错误</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(deliveriesState.data ?? []).map((item) => {
                    const retryable =
                      item.status === "RETRYING" || item.status === "DEAD";
                    return (
                      <TableRow key={item.id}>
                        <TableCell>
                          <div className="font-medium">{item.subject}</div>
                          <div className="text-xs text-muted-foreground">
                            {item.alertType}
                          </div>
                        </TableCell>
                        <TableCell>
                          <StatusDot
                            tone={
                              item.status === "DELIVERED"
                                ? "success"
                                : item.status === "DEAD"
                                  ? "danger"
                                  : "warning"
                            }
                            label={
                              DELIVERY_STATUS_LABEL[item.status] ?? item.status
                            }
                          />
                        </TableCell>
                        <TableCell className="text-right font-mono text-xs tabular-nums">
                          {item.attempts}
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          <div className="tabular-nums">
                            {formatDateTime(item.nextAttemptAt)}
                          </div>
                          {item.lastErrorClass ? (
                            <code className="font-mono text-[11px] text-rose-600 dark:text-rose-400">
                              {item.lastErrorClass}
                            </code>
                          ) : null}
                        </TableCell>
                        <TableCell className="text-right">
                          {retryable ? (
                            <Button
                              size="xs"
                              variant="outline"
                              disabled={retryingDeliveryId === item.id}
                              onClick={() => handleRetryDelivery(item)}
                            >
                              {retryingDeliveryId === item.id ? (
                                <Loader2 className="size-3 animate-spin motion-reduce:animate-none" />
                              ) : (
                                <RotateCcw className="size-3" />
                              )}
                              <span>重新投递</span>
                            </Button>
                          ) : null}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            )}
          </div>
        </TabsContent>

        {/* Tab 4: Audit Logs */}
        <TabsContent value="audit" className="flex flex-col gap-4">
          <form
            onSubmit={handleApplyAuditFilter}
            className="flex flex-wrap items-end gap-2"
          >
            <div className="flex min-w-52 flex-1 flex-col gap-1">
              <Label htmlFor="audit-action-q" className="text-xs text-muted-foreground">
                操作关键词
              </Label>
              <Input
                id="audit-action-q"
                value={auditActionDraft}
                onChange={(e) => setAuditActionDraft(e.target.value)}
                placeholder="例如：WALLET.TOPUP、ORG.STATUS、MODEL."
                className="h-8 font-mono text-xs"
              />
            </div>
            <div className="flex min-w-64 flex-1 flex-col gap-1">
              <Label htmlFor="audit-org-q" className="text-xs text-muted-foreground">
                关联组织 UUID（可选）
              </Label>
              <Input
                id="audit-org-q"
                value={auditOrgDraft}
                onChange={(e) => setAuditOrgDraft(e.target.value)}
                placeholder="精确筛选某租户的审计记录"
                className="h-8 font-mono text-xs"
              />
            </div>
            <div className="flex items-center gap-2">
              <Button type="submit" size="sm">
                <Search className="size-3.5" />
                <span>查询</span>
              </Button>
              {(auditActionFilter || auditOrgFilter) ? (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={handleResetAuditFilter}
                >
                  重置
                </Button>
              ) : null}
            </div>
          </form>

          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {auditLogsState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : (auditLogsState.data?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<FileClock className="size-4" />}
                title="暂无匹配的审计日志"
              />
            ) : (
              <>
                <Table>
                  <TableHeader>
                    <TableRow className="bg-muted/35 hover:bg-muted/35">
                      <TableHead>时间</TableHead>
                      <TableHead>操作</TableHead>
                      <TableHead>操作者</TableHead>
                      <TableHead>目标资源</TableHead>
                      <TableHead className="text-right">来源 IP</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(auditLogsState.data?.items ?? []).map((entry) => (
                      <TableRow
                        key={entry.id}
                        className="cursor-pointer"
                        onClick={() => setInspectAudit(entry)}
                      >
                        <TableCell className="text-xs text-muted-foreground tabular-nums">
                          {formatFullDateTime(entry.createdAt)}
                        </TableCell>
                        <TableCell>
                          <code className="font-mono text-xs font-medium">
                            {entry.action}
                          </code>
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-1.5">
                            <Badge variant="outline" className="h-5 px-1.5 text-[10px]">
                              {ACTOR_TYPE_LABEL[entry.actorType] ??
                                entry.actorType}
                            </Badge>
                            {entry.actorId ? (
                              <code className="font-mono text-[11px] text-muted-foreground">
                                {entry.actorId.slice(0, 8)}
                              </code>
                            ) : null}
                          </div>
                        </TableCell>
                        <TableCell className="text-xs">
                          <span className="font-medium">
                            {entry.resourceType}
                          </span>
                          {entry.resourceId ? (
                            <code className="ml-1.5 font-mono text-[11px] text-muted-foreground">
                              {entry.resourceId.length > 20
                                ? `${entry.resourceId.slice(0, 18)}…`
                                : entry.resourceId}
                            </code>
                          ) : null}
                        </TableCell>
                        <TableCell className="text-right font-mono text-xs text-muted-foreground tabular-nums">
                          {entry.clientIp ?? "—"}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                <div className="flex items-center justify-between border-t border-border px-4 py-2.5 text-xs text-muted-foreground">
                  <span className="tabular-nums">
                    第 {auditPage} / {auditTotalPages} 页 · 共 {auditTotal} 条
                  </span>
                  <div className="flex items-center gap-2">
                    <Button
                      size="xs"
                      variant="outline"
                      disabled={auditPage <= 1}
                      onClick={() => setAuditPage((p) => Math.max(1, p - 1))}
                    >
                      上一页
                    </Button>
                    <Button
                      size="xs"
                      variant="outline"
                      disabled={auditPage >= auditTotalPages}
                      onClick={() =>
                        setAuditPage((p) => Math.min(auditTotalPages, p + 1))
                      }
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              </>
            )}
          </div>
        </TabsContent>
      </Tabs>

      {/* Reconciliation Inspection Dialog */}
      <Dialog
        open={Boolean(inspectFinding)}
        onOpenChange={(open) => {
          if (!open) setInspectFinding(null);
        }}
      >
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="size-4 text-amber-500" />
              <span>对账异常详情</span>
            </DialogTitle>
          </DialogHeader>

          {inspectFinding ? (
            <form
              onSubmit={handleResolveFinding}
              className="flex flex-col gap-4"
            >
              <div className="grid grid-cols-3 gap-2 rounded-lg bg-muted/45 p-3 text-xs tabular-nums">
                <div>
                  <div className="text-muted-foreground">期望</div>
                  <div className="mt-0.5 font-mono font-medium">
                    {formatMicroUsd(inspectFinding.expectedMicros)}
                  </div>
                </div>
                <div>
                  <div className="text-muted-foreground">实际</div>
                  <div className="mt-0.5 font-mono font-medium">
                    {formatMicroUsd(inspectFinding.actualMicros)}
                  </div>
                </div>
                <div>
                  <div className="text-muted-foreground">差额</div>
                  <div className="mt-0.5 font-mono font-medium text-rose-600 dark:text-rose-400">
                    {formatMicroUsd(inspectFinding.deltaMicros, {
                      signed: true,
                    })}
                  </div>
                </div>
              </div>

              {inspectFinding.detailJson ? (
                <Collapsible className="rounded-lg border border-border px-3 py-2">
                  <CollapsibleTrigger className="flex w-full items-center justify-between text-xs font-medium text-muted-foreground">
                    <span>技术诊断详情</span>
                    <ChevronDown className="size-3.5" />
                  </CollapsibleTrigger>
                  <CollapsibleContent className="mt-2 max-h-48 overflow-auto font-mono text-[11px] whitespace-pre-wrap text-muted-foreground">
                    {inspectFinding.detailJson}
                  </CollapsibleContent>
                </Collapsible>
              ) : null}

              {inspectFinding.state === "OPEN" ? (
                <>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="finding-note">处理说明</Label>
                    <Textarea
                      id="finding-note"
                      required
                      value={findingNote}
                      onChange={(e) => setFindingNote(e.target.value)}
                      placeholder="记录核查原因与处置结论…"
                    />
                  </div>
                  <DialogFooter>
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => setInspectFinding(null)}
                    >
                      取消
                    </Button>
                    <Button type="submit" disabled={resolvingFinding}>
                      {resolvingFinding ? (
                        <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                      ) : null}
                      <span>关闭工单</span>
                    </Button>
                  </DialogFooter>
                </>
              ) : inspectFinding.resolutionNote ? (
                <div className="rounded-lg bg-muted/40 p-3 text-xs">
                  <div className="text-muted-foreground">处理说明</div>
                  <p className="mt-1">{inspectFinding.resolutionNote}</p>
                </div>
              ) : null}
            </form>
          ) : null}
        </DialogContent>
      </Dialog>

      {/* Payment Anomaly Inspection Dialog */}
      <Dialog
        open={Boolean(inspectAnomaly)}
        onOpenChange={(open) => {
          if (!open) setInspectAnomaly(null);
        }}
      >
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>支付异常核查</DialogTitle>
          </DialogHeader>

          {inspectAnomaly ? (
            <form
              onSubmit={handleResolveAnomaly}
              className="flex flex-col gap-4"
            >
              <div className="flex items-center justify-between rounded-lg bg-muted/45 px-3 py-2.5 text-xs">
                <span className="font-medium">
                  {ANOMALY_KIND_LABEL[inspectAnomaly.kind]}
                </span>
                <Badge variant="outline">
                  {PAYMENT_CHANNEL_LABEL[inspectAnomaly.paymentChannel]}
                </Badge>
              </div>

              {inspectAnomaly.detailJson ? (
                <Collapsible className="rounded-lg border border-border px-3 py-2">
                  <CollapsibleTrigger className="flex w-full items-center justify-between text-xs font-medium text-muted-foreground">
                    <span>技术诊断详情</span>
                    <ChevronDown className="size-3.5" />
                  </CollapsibleTrigger>
                  <CollapsibleContent className="mt-2 max-h-48 overflow-auto font-mono text-[11px] whitespace-pre-wrap text-muted-foreground">
                    {inspectAnomaly.detailJson}
                  </CollapsibleContent>
                </Collapsible>
              ) : null}

              {inspectAnomaly.status === "OPEN" && canResolveFinance ? (
                <>
                  <div className="flex flex-col gap-1.5">
                    <Label>处置结果</Label>
                    <Select
                      value={anomalyResolutionStatus}
                      onValueChange={(v) =>
                        setAnomalyResolutionStatus(
                          (v as PaymentAnomalyStatus) ?? "RESOLVED",
                        )
                      }
                    >
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="RESOLVED">已解决</SelectItem>
                        <SelectItem value="IGNORED">忽略</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor="anom-note">核查结论</Label>
                    <Textarea
                      id="anom-note"
                      required
                      value={anomalyNote}
                      onChange={(e) => setAnomalyNote(e.target.value)}
                      placeholder="填写渠道对账核查结论…"
                    />
                  </div>
                  <DialogFooter>
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => setInspectAnomaly(null)}
                    >
                      取消
                    </Button>
                    <Button type="submit" disabled={resolvingAnomaly}>
                      {resolvingAnomaly ? (
                        <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                      ) : null}
                      <span>提交处置</span>
                    </Button>
                  </DialogFooter>
                </>
              ) : null}
            </form>
          ) : null}
        </DialogContent>
      </Dialog>

      {/* Audit Log Inspection Dialog */}
      <Dialog
        open={Boolean(inspectAudit)}
        onOpenChange={(open) => {
          if (!open) setInspectAudit(null);
        }}
      >
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <FileClock className="size-4 text-muted-foreground" />
              <span>审计日志详情</span>
            </DialogTitle>
          </DialogHeader>

          {inspectAudit ? (
            <div className="flex flex-col gap-4 text-xs">
              <div className="grid grid-cols-2 gap-3 rounded-lg bg-muted/45 p-3">
                <div className="col-span-2">
                  <div className="text-muted-foreground">操作</div>
                  <code className="mt-0.5 block font-mono text-sm font-medium">
                    {inspectAudit.action}
                  </code>
                </div>
                <div>
                  <div className="text-muted-foreground">时间</div>
                  <div className="mt-0.5 tabular-nums">
                    {formatFullDateTime(inspectAudit.createdAt)}
                  </div>
                </div>
                <div>
                  <div className="text-muted-foreground">操作者</div>
                  <div className="mt-0.5 font-mono">
                    {ACTOR_TYPE_LABEL[inspectAudit.actorType] ??
                      inspectAudit.actorType}
                    {inspectAudit.actorId ? ` · ${inspectAudit.actorId}` : ""}
                  </div>
                </div>
                <div>
                  <div className="text-muted-foreground">目标资源</div>
                  <div className="mt-0.5 font-mono break-all">
                    {inspectAudit.resourceType}
                    {inspectAudit.resourceId
                      ? ` / ${inspectAudit.resourceId}`
                      : ""}
                  </div>
                </div>
                <div>
                  <div className="text-muted-foreground">来源 IP</div>
                  <div className="mt-0.5 font-mono">
                    {inspectAudit.clientIp ?? "—"}
                  </div>
                </div>
                {inspectAudit.orgId ? (
                  <div className="col-span-2">
                    <div className="text-muted-foreground">关联组织</div>
                    <Link
                      href={`/tenants/${inspectAudit.orgId}`}
                      className="mt-0.5 inline-block font-mono text-primary hover:underline"
                    >
                      {inspectAudit.orgId} →
                    </Link>
                  </div>
                ) : null}
                {inspectAudit.traceId ? (
                  <div className="col-span-2">
                    <div className="text-muted-foreground">Trace ID</div>
                    <code className="mt-0.5 block font-mono break-all">
                      {inspectAudit.traceId}
                    </code>
                  </div>
                ) : null}
              </div>

              <div className="flex flex-col gap-1.5">
                <div className="font-medium text-muted-foreground">
                  变更载荷 (diff_json)
                </div>
                {inspectAudit.diffJson ? (
                  <pre className="max-h-80 overflow-auto rounded-lg border border-border bg-muted/35 p-3 font-mono text-[11px] whitespace-pre-wrap">
                    {formatPrettyJson(inspectAudit.diffJson)}
                  </pre>
                ) : (
                  <div className="rounded-lg border border-dashed border-border p-3 text-muted-foreground">
                    无附加变更载荷
                  </div>
                )}
              </div>
            </div>
          ) : null}
        </DialogContent>
      </Dialog>

      {/* Budget Month Resolution Dialog */}
      <Dialog open={budgetDialogOpen} onOpenChange={setBudgetDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>人工核验项目月预算</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleResolveBudget} className="flex flex-col gap-4">
            <ApiErrorAlert error={budgetError} />
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="bm-org">组织 ID</Label>
              <Input
                id="bm-org"
                required
                value={budgetOrgId}
                onChange={(e) => setBudgetOrgId(e.target.value)}
                placeholder="组织标识"
                className="font-mono text-xs"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="bm-proj">项目 ID</Label>
              <Input
                id="bm-proj"
                required
                value={budgetProjectId}
                onChange={(e) => setBudgetProjectId(e.target.value)}
                placeholder="项目标识"
                className="font-mono text-xs"
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="bm-month">预算月份</Label>
                <Input
                  id="bm-month"
                  required
                  value={budgetMonth}
                  onChange={(e) => setBudgetMonth(e.target.value)}
                  placeholder="2026-11"
                  className="font-mono text-xs tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="bm-amt">核定已用金额</Label>
                <div className="relative">
                  <Input
                    id="bm-amt"
                    required
                    inputMode="decimal"
                    value={budgetConfirmedUsd}
                    onChange={(e) => setBudgetConfirmedUsd(e.target.value)}
                    placeholder="42.50"
                    className="pr-11 font-mono tabular-nums"
                  />
                  <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                    USD
                  </span>
                </div>
              </div>
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setBudgetDialogOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={resolvingBudget}>
                {resolvingBudget ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>确认核验</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
