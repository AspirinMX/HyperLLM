"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import {
  Activity,
  AlertTriangle,
  Bell,
  ChevronDown,
  Loader2,
  Play,
  RefreshCw,
  RotateCcw,
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
  Sheet,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
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
  PaymentAnomalyDto,
  PaymentAnomalyStatus,
  ReconciliationFinding,
} from "@/lib/api/types";
import {
  formatDateTime,
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

export default function AdminOperationsPage() {
  const { profile } = useAdminAuth();
  const canResolveFinance = canFinanceWrite(profile?.role);

  const [tab, setTab] = useState<"reconcile" | "anomalies" | "deliveries">(
    "reconcile",
  );
  const [findingStateFilter, setFindingStateFilter] = useState<
    "OPEN" | "RESOLVED"
  >("OPEN");
  const [anomalyStatusFilter, setAnomalyStatusFilter] =
    useState<PaymentAnomalyStatus>("OPEN");
  const [deliveryStatusFilter, setDeliveryStatusFilter] =
    useState<string>("ALL");

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

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs
          value={tab}
          onValueChange={(v) =>
            setTab(v as "reconcile" | "anomalies" | "deliveries")
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
              }}
            >
              <RefreshCw className="size-3.5" />
            </Button>
          )}
        </div>
      </div>

      <ApiErrorAlert
        error={
          findingsState.error ?? anomaliesState.error ?? deliveriesState.error
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
      </Tabs>

      {/* Reconciliation Inspection Sheet */}
      <Sheet
        open={Boolean(inspectFinding)}
        onOpenChange={(open) => {
          if (!open) setInspectFinding(null);
        }}
      >
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle className="flex items-center gap-2">
              <AlertTriangle className="size-4 text-amber-500" />
              <span>对账异常详情</span>
            </SheetTitle>
          </SheetHeader>

          {inspectFinding ? (
            <form
              onSubmit={handleResolveFinding}
              className="flex flex-1 flex-col gap-4 px-6 pb-6"
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
                  <SheetFooter className="px-0 pt-2">
                    <Button
                      type="submit"
                      disabled={resolvingFinding}
                      className="w-full"
                    >
                      {resolvingFinding ? (
                        <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                      ) : null}
                      <span>关闭工单</span>
                    </Button>
                  </SheetFooter>
                </>
              ) : inspectFinding.resolutionNote ? (
                <div className="rounded-lg bg-muted/40 p-3 text-xs">
                  <div className="text-muted-foreground">处理说明</div>
                  <p className="mt-1">{inspectFinding.resolutionNote}</p>
                </div>
              ) : null}
            </form>
          ) : null}
        </SheetContent>
      </Sheet>

      {/* Payment Anomaly Inspection Sheet */}
      <Sheet
        open={Boolean(inspectAnomaly)}
        onOpenChange={(open) => {
          if (!open) setInspectAnomaly(null);
        }}
      >
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle>支付异常核查</SheetTitle>
          </SheetHeader>

          {inspectAnomaly ? (
            <form
              onSubmit={handleResolveAnomaly}
              className="flex flex-1 flex-col gap-4 px-6 pb-6"
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
                  <SheetFooter className="px-0 pt-2">
                    <Button
                      type="submit"
                      disabled={resolvingAnomaly}
                      className="w-full"
                    >
                      {resolvingAnomaly ? (
                        <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                      ) : null}
                      <span>提交处置</span>
                    </Button>
                  </SheetFooter>
                </>
              ) : null}
            </form>
          ) : null}
        </SheetContent>
      </Sheet>

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
