"use client";

import { Suspense, use, useMemo, useState, type FormEvent } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  Ban,
  CheckCircle2,
  CreditCard,
  Loader2,
  PlusCircle,
  RefreshCw,
  RotateCcw,
} from "lucide-react";
import { Bar, BarChart, XAxis, YAxis } from "recharts";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
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
import { useAdminAuth } from "@/components/providers/admin-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminTenantApi } from "@/lib/api/endpoints";
import { createIdempotencyKey } from "@/lib/api/client";
import type {
  OrgStatus,
  PaymentOrderAdminDto,
  TopupType,
} from "@/lib/api/types";
import {
  formatDateTime,
  formatMicroUsd,
  formatMinorCurrency,
  parseMajorToMinor,
  parseUsdToMicros,
} from "@/lib/format/micro-usd";
import {
  canCommerceWrite,
  canFinanceWrite,
  ORDER_STATUS_LABEL,
  ORDER_TYPE_LABEL,
  ORG_STATUS_LABEL,
  PAYMENT_CHANNEL_LABEL,
} from "@/lib/format/labels";

const LIFETIME_CONFIG: ChartConfig = {
  amountUsd: { label: "金额 (USD)", color: "var(--chart-1)" },
};

function TenantDetailContent({
  params,
}: {
  params: Promise<{ orgId: string }>;
}) {
  const { orgId } = use(params);
  const { profile } = useAdminAuth();
  const canTopupRefund = canFinanceWrite(profile?.role);
  const canToggleStatus = canCommerceWrite(profile?.role);

  const orgState = useAsyncData(
    async (signal) => {
      const page = await adminTenantApi.listOrgs({ page: 1, pageSize: 200 }, signal);
      return page.items.find((o) => o.id === orgId) ?? null;
    },
    [orgId],
  );

  const walletState = useAsyncData(
    (signal) => adminTenantApi.getWallet(orgId, signal),
    [orgId],
  );

  const ordersState = useAsyncData(
    (signal) => adminTenantApi.listOrders(orgId, 1, 50, signal),
    [orgId],
  );

  // Manual top-up dialog
  const [topupOpen, setTopupOpen] = useState(false);
  const [topupType, setTopupType] = useState<TopupType>("CASH");
  const [topupUsd, setTopupUsd] = useState("100");
  const [topupNote, setTopupNote] = useState("");
  const [submittingTopup, setSubmittingTopup] = useState(false);
  const [topupError, setTopupError] = useState<unknown>(null);

  // Status toggle confirmation
  const [statusDialogOpen, setStatusDialogOpen] = useState(false);
  const [updatingStatus, setUpdatingStatus] = useState(false);

  // Refund dialog
  const [refundOrder, setRefundOrder] = useState<PaymentOrderAdminDto | null>(
    null,
  );
  const [refundMajor, setRefundMajor] = useState("");
  const [refundNote, setRefundNote] = useState("");
  const [refunding, setRefunding] = useState(false);
  const [refundError, setRefundError] = useState<unknown>(null);

  const org = orgState.data;
  const wallet = walletState.data;

  const lifetimeData = useMemo(() => {
    if (!wallet) return [];
    return [
      {
        name: "累计充值",
        amountUsd: Number((wallet.totalRechargedMicros / 1_000_000).toFixed(4)),
      },
      {
        name: "累计赠金",
        amountUsd: Number(
          (wallet.totalBonusGrantedMicros / 1_000_000).toFixed(4),
        ),
      },
      {
        name: "现金消耗",
        amountUsd: Number(
          (wallet.totalConsumedCashMicros / 1_000_000).toFixed(4),
        ),
      },
      {
        name: "赠金消耗",
        amountUsd: Number(
          (wallet.totalConsumedBonusMicros / 1_000_000).toFixed(4),
        ),
      },
    ];
  }, [wallet]);

  async function handleTopup(e: FormEvent) {
    e.preventDefault();
    setTopupError(null);
    const amountMicros = parseUsdToMicros(topupUsd);
    if (!amountMicros || amountMicros <= 0) {
      toast.error("请输入有效金额");
      return;
    }

    setSubmittingTopup(true);
    try {
      await adminTenantApi.topupWallet(orgId, {
        topupType,
        amountMicros,
        idempotencyKey: createIdempotencyKey(),
        description: topupNote.trim() || null,
      });
      toast.success("已提交异步入账，余额即将同步更新");
      setTopupOpen(false);
      setTopupNote("");
      window.setTimeout(() => {
        walletState.reload();
        orgState.reload();
      }, 1200);
    } catch (err) {
      setTopupError(err);
    } finally {
      setSubmittingTopup(false);
    }
  }

  async function handleToggleStatus() {
    if (!org) return;
    const nextStatus: OrgStatus =
      org.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    setUpdatingStatus(true);
    try {
      await adminTenantApi.updateOrgStatus(org.id, nextStatus, org.version);
      toast.success(nextStatus === "ACTIVE" ? "租户已恢复服务" : "租户已停用");
      setStatusDialogOpen(false);
      orgState.reload();
    } catch {
      toast.error("状态更新失败，请刷新重试");
    } finally {
      setUpdatingStatus(false);
    }
  }

  async function handleRefund(e: FormEvent) {
    e.preventDefault();
    if (!refundOrder) return;
    setRefundError(null);
    const minor = parseMajorToMinor(refundMajor);
    if (!minor) {
      toast.error("请输入有效的退款金额");
      return;
    }

    setRefunding(true);
    try {
      await adminTenantApi.refundOrder(
        refundOrder.id,
        minor,
        refundNote.trim(),
      );
      toast.success("退款记录已提交");
      setRefundOrder(null);
      ordersState.reload();
      walletState.reload();
    } catch (err) {
      setRefundError(err);
    } finally {
      setRefunding(false);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon-sm"
            render={<Link href="/tenants" />}
            aria-label="返回租户列表"
          >
            <ArrowLeft className="size-4" />
          </Button>
          {org ? (
            <div className="flex items-center gap-2">
              <h1 className="text-lg font-semibold tracking-tight">
                {org.name}
              </h1>
              <code className="font-mono text-xs text-muted-foreground">
                {org.slug}
              </code>
              <StatusDot
                tone={org.status === "ACTIVE" ? "success" : "danger"}
                label={ORG_STATUS_LABEL[org.status]}
              />
            </div>
          ) : (
            <Skeleton className="h-6 w-40" />
          )}
        </div>

        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            aria-label="刷新实时快照"
            onClick={() => {
              walletState.reload();
              ordersState.reload();
              orgState.reload();
            }}
          >
            <RefreshCw className="size-3.5" />
          </Button>
          {canTopupRefund ? (
            <Button
              size="sm"
              onClick={() => {
                setTopupError(null);
                setTopupOpen(true);
              }}
            >
              <PlusCircle className="size-3.5" />
              <span>人工充值 / 赠金</span>
            </Button>
          ) : null}
          {canToggleStatus && org ? (
            <Button
              size="sm"
              variant={org.status === "ACTIVE" ? "destructive" : "outline"}
              onClick={() => setStatusDialogOpen(true)}
            >
              {org.status === "ACTIVE" ? (
                <>
                  <Ban className="size-3.5" />
                  <span>停用租户</span>
                </>
              ) : (
                <>
                  <CheckCircle2 className="size-3.5" />
                  <span>恢复正常</span>
                </>
              )}
            </Button>
          ) : null}
        </div>
      </div>

      <ApiErrorAlert
        error={walletState.error ?? ordersState.error}
        onRetry={() => {
          walletState.reload();
          ordersState.reload();
        }}
      />

      {/* Live Wallet Snapshot + Lifetime Comparison Chart */}
      <section className="squircle-md grid divide-y divide-border overflow-hidden rounded-xl border border-border bg-card lg:grid-cols-12 lg:divide-x lg:divide-y-0">
        <div className="flex flex-col justify-between gap-4 p-5 lg:col-span-7">
          <div className="flex items-center justify-between">
            <div className="flex flex-col gap-1">
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <span>实时可用余额</span>
                {wallet ? (
                  <StatusDot
                    tone={wallet.ledgerConfirmed ? "muted" : "success"}
                    label={wallet.ledgerConfirmed ? "账本确认" : "网关实时快照"}
                  />
                ) : null}
              </div>
              <span className="text-3xl font-semibold tracking-tight tabular-nums">
                {walletState.loading
                  ? "…"
                  : formatMicroUsd(wallet?.availableMicros ?? 0)}
              </span>
            </div>
          </div>

          <div className="flex flex-col gap-2.5">
            <ProportionBar
              heightClass="h-2.5"
              segments={
                wallet
                  ? [
                      {
                        key: "cash",
                        label: "现金",
                        value: Math.max(0, wallet.cashMicros),
                        displayValue: formatMicroUsd(wallet.cashMicros),
                        colorClass: "bg-[var(--chart-2)]",
                      },
                      {
                        key: "bonus",
                        label: "未过期赠金",
                        value: Math.max(0, wallet.bonusMicros),
                        displayValue: formatMicroUsd(wallet.bonusMicros),
                        colorClass: "bg-[var(--chart-3)]",
                      },
                      {
                        key: "held",
                        label: "在途预占",
                        value: Math.max(0, wallet.heldMicros),
                        displayValue: formatMicroUsd(wallet.heldMicros),
                        colorClass: "bg-[var(--chart-4)]",
                      },
                    ]
                  : []
              }
            />

            <div className="grid grid-cols-3 gap-3 text-xs tabular-nums">
              <div>
                <div className="text-muted-foreground">实时现金</div>
                <div className="mt-0.5 font-mono font-medium">
                  {formatMicroUsd(wallet?.cashMicros ?? 0)}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground">未过期赠金</div>
                <div className="mt-0.5 font-mono font-medium">
                  {formatMicroUsd(wallet?.bonusMicros ?? 0)}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground">在途预占</div>
                <div className="mt-0.5 font-mono font-medium">
                  {formatMicroUsd(wallet?.heldMicros ?? 0)}
                </div>
              </div>
            </div>
          </div>
        </div>

        <div className="flex flex-col gap-2 p-5 lg:col-span-5">
          <span className="text-xs font-medium text-muted-foreground">
            账本累计收支
          </span>
          {walletState.loading ? (
            <Skeleton className="h-36 w-full" />
          ) : (
            <ChartContainer
              config={LIFETIME_CONFIG}
              className="h-36 w-full aspect-auto"
            >
              <BarChart
                data={lifetimeData}
                layout="vertical"
                margin={{ left: 0, right: 10, top: 4, bottom: 4 }}
              >
                <XAxis type="number" hide />
                <YAxis
                  type="category"
                  dataKey="name"
                  width={68}
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
                <Bar
                  dataKey="amountUsd"
                  fill="var(--color-amountUsd)"
                  radius={[0, 4, 4, 0]}
                />
              </BarChart>
            </ChartContainer>
          )}
        </div>
      </section>

      {/* Tenant Orders Table */}
      <section className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        <div className="border-b border-border px-4 py-3 text-sm font-medium">
          支付订单记录
        </div>
        {(ordersState.data?.items ?? []).length === 0 ? (
          <EmptyState
            icon={<CreditCard className="size-4" />}
            title="暂无支付订单"
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/35 hover:bg-muted/35">
                <TableHead>订单号 / 时间</TableHead>
                <TableHead>类型与渠道</TableHead>
                <TableHead className="text-right">支付金额</TableHead>
                <TableHead className="text-right">入账额度</TableHead>
                <TableHead>状态</TableHead>
                {canTopupRefund ? (
                  <TableHead className="text-right">操作</TableHead>
                ) : null}
              </TableRow>
            </TableHeader>
            <TableBody>
              {(ordersState.data?.items ?? []).map((order) => (
                <TableRow key={order.id}>
                  <TableCell>
                    <div className="font-mono text-xs">{order.orderNo}</div>
                    <div className="text-[11px] text-muted-foreground tabular-nums">
                      {formatDateTime(order.createdAt)}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <span className="text-sm">
                        {ORDER_TYPE_LABEL[order.orderType]}
                      </span>
                      <Badge variant="outline">
                        {PAYMENT_CHANNEL_LABEL[order.paymentChannel]}
                      </Badge>
                    </div>
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">
                    <div>
                      {formatMinorCurrency(
                        order.payAmountMinor,
                        order.payCurrency,
                      )}
                    </div>
                    {order.refundedAmountMinor > 0 ? (
                      <div className="text-[11px] text-rose-600 dark:text-rose-400">
                        已退{" "}
                        {formatMinorCurrency(
                          order.refundedAmountMinor,
                          order.payCurrency,
                        )}
                      </div>
                    ) : null}
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">
                    {order.creditMicros > 0
                      ? formatMicroUsd(order.creditMicros)
                      : "资源包"}
                  </TableCell>
                  <TableCell>
                    <StatusDot
                      tone={
                        order.status === "PAID"
                          ? "success"
                          : order.status === "PENDING"
                            ? "warning"
                            : order.status === "REFUNDED"
                              ? "info"
                              : "muted"
                      }
                      label={ORDER_STATUS_LABEL[order.status]}
                    />
                  </TableCell>
                  {canTopupRefund ? (
                    <TableCell className="text-right">
                      {order.status === "PAID" ? (
                        <Button
                          size="xs"
                          variant="outline"
                          onClick={() => {
                            setRefundOrder(order);
                            setRefundMajor(
                              ((order.payAmountMinor - order.refundedAmountMinor) / 100).toFixed(2),
                            );
                            setRefundNote("");
                            setRefundError(null);
                          }}
                        >
                          <RotateCcw className="size-3" />
                          <span>登记退款</span>
                        </Button>
                      ) : null}
                    </TableCell>
                  ) : null}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </section>

      {/* Manual Top-up Dialog */}
      <Dialog open={topupOpen} onOpenChange={setTopupOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>人工入账 / 发放赠金</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleTopup} className="flex flex-col gap-4">
            <ApiErrorAlert error={topupError} />
            <div className="flex flex-col gap-1.5">
              <Label>入账类型</Label>
              <Select
                value={topupType}
                onValueChange={(v) => setTopupType((v as TopupType) ?? "CASH")}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="CASH">现金余额</SelectItem>
                  <SelectItem value="BONUS">赠金余额</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="admin-topup-amt">入账金额</Label>
              <div className="relative">
                <Input
                  id="admin-topup-amt"
                  required
                  inputMode="decimal"
                  value={topupUsd}
                  onChange={(e) => setTopupUsd(e.target.value)}
                  className="pr-11 font-mono tabular-nums"
                />
                <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                  USD
                </span>
              </div>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="admin-topup-note">备注说明</Label>
              <Input
                id="admin-topup-note"
                value={topupNote}
                onChange={(e) => setTopupNote(e.target.value)}
                placeholder="例如：大客户线下对公转账入账"
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setTopupOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={submittingTopup}>
                {submittingTopup ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>提交入账</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Refund Dialog */}
      <Dialog
        open={Boolean(refundOrder)}
        onOpenChange={(open) => {
          if (!open) setRefundOrder(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>登记订单退款</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleRefund} className="flex flex-col gap-4">
            <ApiErrorAlert error={refundError} />
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="ref-amt">退款金额</Label>
              <div className="relative">
                <Input
                  id="ref-amt"
                  required
                  inputMode="decimal"
                  value={refundMajor}
                  onChange={(e) => setRefundMajor(e.target.value)}
                  className="pr-12 font-mono tabular-nums"
                />
                <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                  {refundOrder?.payCurrency}
                </span>
              </div>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="ref-note">核销备注</Label>
              <Input
                id="ref-note"
                required
                value={refundNote}
                onChange={(e) => setRefundNote(e.target.value)}
                placeholder="填写退款渠道凭证号与核对结论"
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setRefundOrder(null)}
              >
                取消
              </Button>
              <Button type="submit" variant="destructive" disabled={refunding}>
                {refunding ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>确认退款</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Suspend / Restore Org AlertDialog */}
      <AlertDialog open={statusDialogOpen} onOpenChange={setStatusDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {org?.status === "ACTIVE"
                ? `停用租户「${org?.name}」？`
                : `恢复租户「${org?.name}」？`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {org?.status === "ACTIVE"
                ? "停用后该租户下所有 API 请求将被网关立即拦截。"
                : "恢复后该租户可立即继续发起 API 调用。"}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              variant={org?.status === "ACTIVE" ? "destructive" : "default"}
              disabled={updatingStatus}
              onClick={handleToggleStatus}
            >
              {updatingStatus ? (
                <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
              ) : null}
              <span>确认</span>
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

export default function AdminTenantDetailPage({
  params,
}: {
  params: Promise<{ orgId: string }>;
}) {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-4">
          <Skeleton className="h-7 w-48" />
          <Skeleton className="h-44 w-full" />
        </div>
      }
    >
      <TenantDetailContent params={params} />
    </Suspense>
  );
}
