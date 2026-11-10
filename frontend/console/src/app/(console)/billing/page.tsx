"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import { QRCodeSVG } from "qrcode.react";
import {
  CheckCircle2,
  CreditCard,
  ExternalLink,
  Gift,
  HelpCircle,
  Loader2,
  Package,
  RefreshCw,
  Ticket,
  Wallet,
} from "lucide-react";
import { Bar, BarChart, XAxis, YAxis } from "recharts";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { EmptyState } from "@/components/common/empty-state";
import { ProportionBar } from "@/components/common/proportion-bar";
import { StatusDot, type StatusTone } from "@/components/common/status-dot";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { billingApi } from "@/lib/api/endpoints";
import { createIdempotencyKey } from "@/lib/api/client";
import type {
  OrderStatus,
  PackSkuDto,
  PaymentChannel,
  PaymentInstructionDto,
  PaymentOrderDto,
  RedemptionDto,
} from "@/lib/api/types";
import {
  formatCompactNumber,
  formatDateTime,
  formatMicroUsd,
  formatMinorCurrency,
  parseMajorToMinor,
} from "@/lib/format/micro-usd";
import {
  canPayOrRedeem,
  canViewWallet,
  FULFILLMENT_STATUS_LABEL,
  LEDGER_TYPE_LABEL,
  ORDER_STATUS_LABEL,
  ORDER_TYPE_LABEL,
  PACK_UNIT_LABEL,
  PAYMENT_CHANNEL_LABEL,
} from "@/lib/format/labels";
import { cn } from "@/lib/utils";

const LIFETIME_CHART_CONFIG: ChartConfig = {
  amountUsd: { label: "金额 (USD)", color: "var(--chart-1)" },
};

const ORDER_TONE: Record<OrderStatus, StatusTone> = {
  PENDING: "warning",
  PAID: "success",
  FAILED: "danger",
  CLOSED: "muted",
  REFUNDED: "info",
};

function submitFormInstruction(instruction: PaymentInstructionDto) {
  if (!instruction.action) return;
  const form = document.createElement("form");
  form.method = instruction.method || "POST";
  form.action = instruction.action;
  form.target = "_blank";
  for (const [k, v] of Object.entries(instruction.fields ?? {})) {
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = k;
    input.value = v;
    form.appendChild(input);
  }
  document.body.appendChild(form);
  form.submit();
  document.body.removeChild(form);
}

export default function BillingPage() {
  const { currentOrg, refreshWallet } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;
  const allowedView = canViewWallet(currentOrg?.role);
  const allowedPay = canPayOrRedeem(currentOrg?.role);

  const [ledgerPage, setLedgerPage] = useState(1);
  const [ordersPage, setOrdersPage] = useState(1);

  const walletState = useAsyncData(
    (signal) => billingApi.getWallet(orgId!, signal),
    [orgId],
    Boolean(orgId) && allowedView,
  );

  const ledgerState = useAsyncData(
    (signal) => billingApi.listLedger(orgId!, ledgerPage, 20, signal),
    [orgId, ledgerPage],
    Boolean(orgId) && allowedView,
  );

  const ordersState = useAsyncData(
    (signal) => billingApi.listPaymentOrders(orgId!, ordersPage, 20, signal),
    [orgId, ordersPage],
    Boolean(orgId) && allowedView,
  );

  const skusState = useAsyncData(
    (signal) => billingApi.listPackSkus(orgId!, signal),
    [orgId],
    Boolean(orgId) && allowedView,
  );

  // Checkout Dialog state
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [checkoutSku, setCheckoutSku] = useState<PackSkuDto | null>(null);
  const [channel, setChannel] = useState<PaymentChannel>("STRIPE");
  const [amountMajor, setAmountMajor] = useState("50");
  const [creatingOrder, setCreatingOrder] = useState(false);
  const [checkoutError, setCheckoutError] = useState<unknown>(null);
  const [activeOrder, setActiveOrder] = useState<PaymentOrderDto | null>(null);

  // Voucher redeem state
  const [voucherCode, setVoucherCode] = useState("");
  const [redeeming, setRedeeming] = useState(false);
  const [redeemError, setRedeemError] = useState<unknown>(null);
  const [lastRedemption, setLastRedemption] = useState<RedemptionDto | null>(
    null,
  );

  // Poll active pending order until APPLIED or terminal
  useEffect(() => {
    if (!checkoutOpen || !activeOrder || !orgId) return;
    if (
      activeOrder.paymentStatus === "FAILED" ||
      activeOrder.paymentStatus === "CLOSED" ||
      activeOrder.fulfillmentStatus === "APPLIED"
    ) {
      return;
    }

    const timer = window.setInterval(() => {
      billingApi
        .getPaymentOrder(orgId, activeOrder.id)
        .then((latest) => {
          setActiveOrder(latest);
          if (latest.fulfillmentStatus === "APPLIED") {
            toast.success("支付成功，余额已到账");
            walletState.reload();
            ledgerState.reload();
            ordersState.reload();
            void refreshWallet();
          }
        })
        .catch(() => {});
    }, 3000);

    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [checkoutOpen, activeOrder, orgId]);

  const wallet = walletState.data;
  const lifetimeChartData = useMemo(() => {
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

  function openTopupDialog() {
    setCheckoutSku(null);
    setChannel("STRIPE");
    setAmountMajor("50");
    setActiveOrder(null);
    setCheckoutError(null);
    setCheckoutOpen(true);
  }

  function openPackCheckout(sku: PackSkuDto) {
    setCheckoutSku(sku);
    setChannel("STRIPE");
    setActiveOrder(null);
    setCheckoutError(null);
    setCheckoutOpen(true);
  }

  function inspectOrder(order: PaymentOrderDto) {
    setCheckoutSku(null);
    setActiveOrder(order);
    setCheckoutError(null);
    setCheckoutOpen(true);
  }

  async function handleCreateOrder(e: FormEvent) {
    e.preventDefault();
    if (!orgId) return;
    setCheckoutError(null);

    let payAmountMinor: number | undefined;
    if (!checkoutSku) {
      const parsed = parseMajorToMinor(amountMajor);
      if (!parsed) {
        toast.error("请输入有效充值金额");
        return;
      }
      payAmountMinor = parsed;
    }

    setCreatingOrder(true);
    try {
      const created = await billingApi.createPaymentOrder(
        orgId,
        checkoutSku
          ? {
              orderType: "RESOURCE_PACK_PURCHASE",
              paymentChannel: channel,
              packSkuId: checkoutSku.id,
            }
          : {
              orderType: "WALLET_TOPUP",
              paymentChannel: channel,
              payAmountMinor,
            },
        createIdempotencyKey(),
      );
      setActiveOrder(created);
      ordersState.reload();
    } catch (err) {
      setCheckoutError(err);
    } finally {
      setCreatingOrder(false);
    }
  }

  async function handleRedeem(e: FormEvent) {
    e.preventDefault();
    if (!orgId || !voucherCode.trim()) return;
    setRedeemError(null);
    setRedeeming(true);
    try {
      const res = await billingApi.redeemVoucher(orgId, voucherCode.trim());
      setLastRedemption(res);
      setVoucherCode("");
      toast.success(`兑换成功，赠金 ${formatMicroUsd(res.amountMicros)} 即将入账`);
      walletState.reload();
      ledgerState.reload();
      void refreshWallet();
    } catch (err) {
      setRedeemError(err);
    } finally {
      setRedeeming(false);
    }
  }

  if (!allowedView) {
    return (
      <EmptyState
        icon={<Wallet className="size-4" />}
        title="当前角色无权查看组织账务，请联系管理员"
      />
    );
  }

  const presetAmounts =
    channel === "STRIPE" ? ["10", "50", "200", "500"] : ["50", "200", "500", "1000"];

  const ledgerTotalPages = Math.max(
    1,
    Math.ceil((ledgerState.data?.total ?? 0) / 20),
  );
  const ordersTotalPages = Math.max(
    1,
    Math.ceil((ordersState.data?.total ?? 0) / 20),
  );

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-lg font-semibold tracking-tight">账务与充值</h1>
        <Button
          type="button"
          variant="outline"
          size="icon"
          aria-label="刷新账务"
          onClick={() => {
            walletState.reload();
            ledgerState.reload();
            ordersState.reload();
            void refreshWallet();
          }}
        >
          <RefreshCw
            className={
              walletState.refreshing
                ? "size-3.5 animate-spin motion-reduce:animate-none"
                : "size-3.5"
            }
          />
        </Button>
      </div>

      <ApiErrorAlert error={walletState.error} onRetry={walletState.reload} />

      {/* Top Visual Wallet & Lifetime Breakdown Panel */}
      <section className="squircle-md grid divide-y divide-border overflow-hidden rounded-xl border border-border bg-card lg:grid-cols-12 lg:divide-x lg:divide-y-0">
        {/* Left 7 cols: Live Balance Composition */}
        <div className="flex flex-col justify-between gap-5 p-5 lg:col-span-7">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="flex flex-col gap-1">
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <span>可用总额</span>
                {wallet ? (
                  <StatusDot
                    tone={wallet.ledgerConfirmed ? "muted" : "success"}
                    label={wallet.ledgerConfirmed ? "账本确认" : "实时快照"}
                  />
                ) : null}
                <Tooltip>
                  <TooltipTrigger
                    render={
                      <button
                        type="button"
                        className="text-muted-foreground hover:text-foreground"
                        aria-label="抵扣顺序说明"
                      />
                    }
                  >
                    <HelpCircle className="size-3.5" />
                  </TooltipTrigger>
                  <TooltipContent>
                    扣费顺位：定向资源包 → 未过期赠金 → 现金余额；请求进行中会临时预占估算额度。
                  </TooltipContent>
                </Tooltip>
              </div>
              <div className="text-3xl font-semibold tracking-tight tabular-nums">
                {walletState.loading
                  ? "…"
                  : formatMicroUsd(wallet?.availableMicros ?? 0)}
              </div>
            </div>

            {allowedPay ? (
              <div className="flex items-center gap-2">
                <Button size="sm" onClick={openTopupDialog}>
                  <CreditCard className="size-3.5" />
                  <span>立即充值</span>
                </Button>
              </div>
            ) : null}
          </div>

          <div className="flex flex-col gap-2.5">
            <ProportionBar
              heightClass="h-2.5"
              segments={
                wallet
                  ? [
                      {
                        key: "cash",
                        label: "现金余额",
                        value: Math.max(0, wallet.cashBalanceMicros),
                        displayValue: formatMicroUsd(wallet.cashBalanceMicros),
                        colorClass: "bg-[var(--chart-2)]",
                      },
                      {
                        key: "bonus",
                        label: "未过期赠金",
                        value: Math.max(0, wallet.bonusBalanceMicros),
                        displayValue: formatMicroUsd(wallet.bonusBalanceMicros),
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
              <div className="flex flex-col gap-0.5">
                <span className="flex items-center gap-1.5 text-muted-foreground">
                  <span className="size-2 rounded-full bg-[var(--chart-2)]" />
                  现金余额
                </span>
                <span className="font-mono font-medium">
                  {formatMicroUsd(wallet?.cashBalanceMicros ?? 0)}
                </span>
              </div>
              <div className="flex flex-col gap-0.5">
                <span className="flex items-center gap-1.5 text-muted-foreground">
                  <span className="size-2 rounded-full bg-[var(--chart-3)]" />
                  未过期赠金
                </span>
                <span className="font-mono font-medium">
                  {formatMicroUsd(wallet?.bonusBalanceMicros ?? 0)}
                </span>
              </div>
              <div className="flex flex-col gap-0.5">
                <span className="flex items-center gap-1.5 text-muted-foreground">
                  <span className="size-2 rounded-full bg-[var(--chart-4)]" />
                  在途预占
                </span>
                <span className="font-mono font-medium">
                  {formatMicroUsd(wallet?.heldMicros ?? 0)}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Right 5 cols: Lifetime Financial Structure Horizontal Bar Chart */}
        <div className="flex flex-col gap-2 p-5 lg:col-span-5">
          <span className="text-xs font-medium text-muted-foreground">
            历史收支结构
          </span>
          {walletState.loading ? (
            <Skeleton className="h-36 w-full" />
          ) : (
            <ChartContainer
              config={LIFETIME_CHART_CONFIG}
              className="h-36 w-full aspect-auto"
            >
              <BarChart
                data={lifetimeChartData}
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

      {/* Main Billing Tabs */}
      <Tabs defaultValue="ledger" className="gap-4">
        <TabsList>
          <TabsTrigger value="ledger">资金流水</TabsTrigger>
          <TabsTrigger value="orders">支付订单</TabsTrigger>
          <TabsTrigger value="packs">资源包商城</TabsTrigger>
          {allowedPay ? <TabsTrigger value="redeem">兑换码</TabsTrigger> : null}
        </TabsList>

        {/* Tab 1: Wallet Ledger */}
        <TabsContent value="ledger">
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {ledgerState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                {Array.from({ length: 5 }).map((_, i) => (
                  <Skeleton key={i} className="h-9 w-full" />
                ))}
              </div>
            ) : (ledgerState.data?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<Wallet className="size-4" />}
                title="暂无资金流水"
              />
            ) : (
              <>
                <Table>
                  <TableHeader>
                    <TableRow className="bg-muted/35 hover:bg-muted/35">
                      <TableHead>时间</TableHead>
                      <TableHead>类型</TableHead>
                      <TableHead className="text-right">现金变动</TableHead>
                      <TableHead className="text-right">赠金变动</TableHead>
                      <TableHead className="text-right">资源包变动</TableHead>
                      <TableHead className="text-right">变动后结余</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(ledgerState.data?.items ?? []).map((row) => (
                      <TableRow key={row.id}>
                        <TableCell className="text-xs text-muted-foreground tabular-nums">
                          {formatDateTime(row.createdAt)}
                        </TableCell>
                        <TableCell>
                          <div className="flex flex-col">
                            <span className="text-sm font-medium">
                              {LEDGER_TYPE_LABEL[row.ledgerType]}
                            </span>
                            {row.description ? (
                              <span className="text-xs text-muted-foreground">
                                {row.description}
                              </span>
                            ) : null}
                          </div>
                        </TableCell>
                        <TableCell
                          className={cn(
                            "text-right font-mono text-xs tabular-nums",
                            row.cashDeltaMicros > 0
                              ? "text-emerald-600 dark:text-emerald-400"
                              : row.cashDeltaMicros < 0
                                ? "text-foreground"
                                : "text-muted-foreground",
                          )}
                        >
                          {row.cashDeltaMicros === 0
                            ? "—"
                            : formatMicroUsd(row.cashDeltaMicros, {
                                signed: true,
                              })}
                        </TableCell>
                        <TableCell
                          className={cn(
                            "text-right font-mono text-xs tabular-nums",
                            row.bonusDeltaMicros > 0
                              ? "text-emerald-600 dark:text-emerald-400"
                              : row.bonusDeltaMicros < 0
                                ? "text-foreground"
                                : "text-muted-foreground",
                          )}
                        >
                          {row.bonusDeltaMicros === 0
                            ? "—"
                            : formatMicroUsd(row.bonusDeltaMicros, {
                                signed: true,
                              })}
                        </TableCell>
                        <TableCell className="text-right font-mono text-xs tabular-nums text-muted-foreground">
                          {row.packDeltaUnits === 0
                            ? "—"
                            : `${row.packDeltaUnits > 0 ? "+" : ""}${formatCompactNumber(row.packDeltaUnits)}`}
                        </TableCell>
                        <TableCell className="text-right font-mono text-xs text-muted-foreground tabular-nums">
                          {row.cashBalanceAfterMicros !== null
                            ? formatMicroUsd(
                                row.cashBalanceAfterMicros +
                                  (row.bonusBalanceAfterMicros ?? 0),
                              )
                            : "—"}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {ledgerTotalPages > 1 ? (
                  <div className="flex items-center justify-between border-t border-border px-4 py-2.5 text-xs text-muted-foreground">
                    <span className="tabular-nums">
                      第 {ledgerPage} / {ledgerTotalPages} 页
                    </span>
                    <div className="flex items-center gap-1.5">
                      <Button
                        size="xs"
                        variant="outline"
                        disabled={ledgerPage <= 1}
                        onClick={() => setLedgerPage((p) => p - 1)}
                      >
                        上一页
                      </Button>
                      <Button
                        size="xs"
                        variant="outline"
                        disabled={ledgerPage >= ledgerTotalPages}
                        onClick={() => setLedgerPage((p) => p + 1)}
                      >
                        下一页
                      </Button>
                    </div>
                  </div>
                ) : null}
              </>
            )}
          </div>
        </TabsContent>

        {/* Tab 2: Payment Orders */}
        <TabsContent value="orders">
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {ordersState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                {Array.from({ length: 4 }).map((_, i) => (
                  <Skeleton key={i} className="h-10 w-full" />
                ))}
              </div>
            ) : (ordersState.data?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<CreditCard className="size-4" />}
                title="暂无支付订单"
              />
            ) : (
              <>
                <Table>
                  <TableHeader>
                    <TableRow className="bg-muted/35 hover:bg-muted/35">
                      <TableHead>订单号 / 时间</TableHead>
                      <TableHead>类型与渠道</TableHead>
                      <TableHead className="text-right">支付金额</TableHead>
                      <TableHead className="text-right">入账额度</TableHead>
                      <TableHead>状态</TableHead>
                      <TableHead className="text-right">操作</TableHead>
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
                            <span className="text-sm font-medium">
                              {ORDER_TYPE_LABEL[order.orderType]}
                            </span>
                            <Badge variant="outline">
                              {PAYMENT_CHANNEL_LABEL[order.paymentChannel]}
                            </Badge>
                          </div>
                        </TableCell>
                        <TableCell className="text-right font-mono text-xs tabular-nums">
                          {formatMinorCurrency(
                            order.payAmountMinor,
                            order.payCurrency,
                          )}
                        </TableCell>
                        <TableCell className="text-right font-mono text-xs tabular-nums">
                          {order.creditMicros > 0
                            ? formatMicroUsd(order.creditMicros)
                            : "资源包"}
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-2.5">
                            <StatusDot
                              tone={ORDER_TONE[order.paymentStatus]}
                              label={ORDER_STATUS_LABEL[order.paymentStatus]}
                            />
                            <span className="text-[11px] text-muted-foreground">
                              {FULFILLMENT_STATUS_LABEL[order.fulfillmentStatus]}
                            </span>
                          </div>
                        </TableCell>
                        <TableCell className="text-right">
                          {order.paymentStatus === "PENDING" ? (
                            <Button
                              size="xs"
                              variant="outline"
                              onClick={() => inspectOrder(order)}
                            >
                              继续支付
                            </Button>
                          ) : null}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {ordersTotalPages > 1 ? (
                  <div className="flex items-center justify-between border-t border-border px-4 py-2.5 text-xs text-muted-foreground">
                    <span className="tabular-nums">
                      第 {ordersPage} / {ordersTotalPages} 页
                    </span>
                    <div className="flex items-center gap-1.5">
                      <Button
                        size="xs"
                        variant="outline"
                        disabled={ordersPage <= 1}
                        onClick={() => setOrdersPage((p) => p - 1)}
                      >
                        上一页
                      </Button>
                      <Button
                        size="xs"
                        variant="outline"
                        disabled={ordersPage >= ordersTotalPages}
                        onClick={() => setOrdersPage((p) => p + 1)}
                      >
                        下一页
                      </Button>
                    </div>
                  </div>
                ) : null}
              </>
            )}
          </div>
        </TabsContent>

        {/* Tab 3: Resource Pack SKUs */}
        <TabsContent value="packs">
          {skusState.loading ? (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {Array.from({ length: 3 }).map((_, i) => (
                <Skeleton key={i} className="h-44 w-full" />
              ))}
            </div>
          ) : (skusState.data ?? []).length === 0 ? (
            <div className="squircle-md rounded-xl border border-border bg-card">
              <EmptyState
                icon={<Package className="size-4" />}
                title="暂无在售资源包"
              />
            </div>
          ) : (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {(skusState.data ?? []).map((sku) => (
                <div
                  key={sku.id}
                  className="squircle-md flex flex-col justify-between gap-4 rounded-xl border border-border bg-card p-5"
                >
                  <div className="flex flex-col gap-2">
                    <div className="flex items-start justify-between gap-2">
                      <span className="font-medium">{sku.name}</span>
                      <Badge variant="secondary" className="tabular-nums">
                        {sku.validDays} 天
                      </Badge>
                    </div>
                    <div className="text-2xl font-semibold tabular-nums">
                      {formatCompactNumber(sku.totalUnits)}{" "}
                      <span className="text-xs font-normal text-muted-foreground">
                        {PACK_UNIT_LABEL[sku.unitType]}
                      </span>
                    </div>
                    <div className="flex flex-wrap gap-1 pt-1">
                      {sku.applicableModels.map((m) => (
                        <Badge
                          key={m}
                          variant="outline"
                          className="font-mono text-[11px]"
                        >
                          {m}
                        </Badge>
                      ))}
                    </div>
                  </div>

                  <div className="flex items-center justify-between border-t border-border pt-3">
                    <span className="font-mono text-base font-semibold tabular-nums">
                      {formatMicroUsd(sku.priceMicros)}
                    </span>
                    {allowedPay ? (
                      <Button
                        size="sm"
                        onClick={() => openPackCheckout(sku)}
                      >
                        购买
                      </Button>
                    ) : null}
                  </div>
                </div>
              ))}
            </div>
          )}
        </TabsContent>

        {/* Tab 4: Redeem Voucher */}
        {allowedPay ? (
          <TabsContent value="redeem">
            <div className="squircle-md flex max-w-xl flex-col gap-4 rounded-xl border border-border bg-card p-5">
              <div className="flex items-center gap-2 text-sm font-medium">
                <Ticket className="size-4" />
                <span>兑换码核销</span>
              </div>

              <ApiErrorAlert error={redeemError} />

              <form onSubmit={handleRedeem} className="flex items-center gap-2">
                <Input
                  required
                  value={voucherCode}
                  onChange={(e) => setVoucherCode(e.target.value)}
                  placeholder="输入活动兑换码"
                  className="font-mono"
                />
                <Button type="submit" disabled={redeeming}>
                  {redeeming ? (
                    <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                  ) : (
                    <Gift className="size-3.5" />
                  )}
                  <span>核销</span>
                </Button>
              </form>

              {lastRedemption ? (
                <div className="flex flex-col gap-1.5 rounded-lg border border-border bg-muted/40 p-3.5 text-xs">
                  <div className="flex items-center gap-1.5 font-medium text-emerald-700 dark:text-emerald-400">
                    <CheckCircle2 className="size-3.5" />
                    <span>
                      已领取赠金 {formatMicroUsd(lastRedemption.amountMicros)}
                    </span>
                  </div>
                  <div className="text-muted-foreground">
                    适用范围：
                    {lastRedemption.applicableModels.length > 0
                      ? lastRedemption.applicableModels.join(", ")
                      : "全平台模型"}
                    {lastRedemption.expiresAt
                      ? ` · 有效期至 ${formatDateTime(lastRedemption.expiresAt)}`
                      : ""}
                  </div>
                </div>
              ) : null}
            </div>
          </TabsContent>
        ) : null}
      </Tabs>

      {/* Checkout Dialog (Top-up & Resource Pack) */}
      <Dialog open={checkoutOpen} onOpenChange={setCheckoutOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>
              {activeOrder
                ? "完成支付"
                : checkoutSku
                  ? `购买「${checkoutSku.name}」`
                  : "钱包充值"}
            </DialogTitle>
          </DialogHeader>

          <ApiErrorAlert error={checkoutError} />

          {!activeOrder ? (
            <form onSubmit={handleCreateOrder} className="flex flex-col gap-4">
              <div className="flex flex-col gap-1.5">
                <Label>支付渠道</Label>
                <div className="grid grid-cols-3 gap-2">
                  {(["STRIPE", "ALIPAY", "WECHAT_PAY"] as const).map((ch) => (
                    <button
                      key={ch}
                      type="button"
                      onClick={() => {
                        setChannel(ch);
                        if (!checkoutSku) {
                          setAmountMajor(ch === "STRIPE" ? "50" : "200");
                        }
                      }}
                      className={cn(
                        "flex flex-col items-center gap-0.5 rounded-lg border px-2 py-2.5 text-xs font-medium transition-colors motion-reduce:transition-none",
                        channel === ch
                          ? "border-foreground bg-muted text-foreground"
                          : "border-border text-muted-foreground hover:bg-muted/50",
                      )}
                    >
                      <span>{PAYMENT_CHANNEL_LABEL[ch]}</span>
                      <span className="text-[10px] font-normal text-muted-foreground">
                        {ch === "STRIPE" ? "USD" : "CNY"}
                      </span>
                    </button>
                  ))}
                </div>
              </div>

              {!checkoutSku ? (
                <div className="flex flex-col gap-2">
                  <Label htmlFor="topup-amount">充值金额</Label>
                  <div className="grid grid-cols-4 gap-1.5">
                    {presetAmounts.map((amt) => (
                      <Button
                        key={amt}
                        type="button"
                        size="sm"
                        variant={amountMajor === amt ? "default" : "outline"}
                        onClick={() => setAmountMajor(amt)}
                        className="font-mono tabular-nums"
                      >
                        {channel === "STRIPE" ? `$${amt}` : `¥${amt}`}
                      </Button>
                    ))}
                  </div>
                  <div className="relative">
                    <Input
                      id="topup-amount"
                      required
                      inputMode="decimal"
                      value={amountMajor}
                      onChange={(e) => setAmountMajor(e.target.value)}
                      placeholder="输入金额"
                      className="pr-12 font-mono tabular-nums"
                    />
                    <span className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-xs text-muted-foreground">
                      {channel === "STRIPE" ? "USD" : "CNY"}
                    </span>
                  </div>
                </div>
              ) : (
                <div className="flex items-center justify-between rounded-lg bg-muted/45 px-3.5 py-2.5 text-xs">
                  <span className="text-muted-foreground">资源包定价</span>
                  <span className="font-mono text-sm font-semibold tabular-nums">
                    {formatMicroUsd(checkoutSku.priceMicros)}
                  </span>
                </div>
              )}

              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setCheckoutOpen(false)}
                >
                  取消
                </Button>
                <Button type="submit" disabled={creatingOrder}>
                  {creatingOrder ? (
                    <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                  ) : null}
                  <span>提交订单</span>
                </Button>
              </DialogFooter>
            </form>
          ) : (
            <div className="flex flex-col gap-4">
              <div className="flex items-center justify-between rounded-lg bg-muted/45 px-3.5 py-2.5 text-xs">
                <div className="flex flex-col gap-0.5">
                  <span className="font-mono text-muted-foreground">
                    {activeOrder.orderNo}
                  </span>
                  <StatusDot
                    tone={ORDER_TONE[activeOrder.paymentStatus]}
                    label={`${ORDER_STATUS_LABEL[activeOrder.paymentStatus]} · ${FULFILLMENT_STATUS_LABEL[activeOrder.fulfillmentStatus]}`}
                  />
                </div>
                <span className="font-mono text-base font-semibold tabular-nums">
                  {formatMinorCurrency(
                    activeOrder.payAmountMinor,
                    activeOrder.payCurrency,
                  )}
                </span>
              </div>

              {activeOrder.fulfillmentStatus === "APPLIED" ? (
                <div className="flex flex-col items-center gap-2 py-4 text-center">
                  <CheckCircle2 className="size-8 text-emerald-500" />
                  <p className="text-sm font-medium">已支付并完成入账</p>
                </div>
              ) : activeOrder.instruction?.codeUrl ? (
                <div className="flex flex-col items-center gap-3 py-2">
                  <div className="rounded-xl border border-border bg-white p-3">
                    <QRCodeSVG
                      value={activeOrder.instruction.codeUrl}
                      size={180}
                    />
                  </div>
                  <span className="text-xs text-muted-foreground">
                    请使用微信扫描二维码完成支付，页面将自动刷新到账状态
                  </span>
                </div>
              ) : activeOrder.instruction?.redirectUrl ? (
                <Button
                  className="w-full"
                  render={
                    <a
                      href={activeOrder.instruction.redirectUrl}
                      target="_blank"
                      rel="noreferrer"
                    />
                  }
                >
                  <span>前往收银台支付</span>
                  <ExternalLink className="size-3.5" />
                </Button>
              ) : activeOrder.instruction?.action ? (
                <Button
                  type="button"
                  className="w-full"
                  onClick={() =>
                    activeOrder.instruction &&
                    submitFormInstruction(activeOrder.instruction)
                  }
                >
                  <span>前往支付宝完成付款</span>
                  <ExternalLink className="size-3.5" />
                </Button>
              ) : null}

              <DialogFooter>
                <Button
                  variant="outline"
                  onClick={() => setCheckoutOpen(false)}
                >
                  关闭
                </Button>
              </DialogFooter>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
