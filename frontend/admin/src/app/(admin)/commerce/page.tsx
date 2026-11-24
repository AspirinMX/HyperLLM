"use client";

import { useState, type FormEvent } from "react";
import {
  AlertTriangle,
  Download,
  Loader2,
  Package,
  Pencil,
  Plus,
  Ticket,
} from "lucide-react";
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { CopyButton } from "@/components/common/copy-button";
import { EmptyState } from "@/components/common/empty-state";
import { StatusDot } from "@/components/common/status-dot";
import { useAdminAuth } from "@/components/providers/admin-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminCommerceApi } from "@/lib/api/endpoints";
import type {
  CodeBatchResponse,
  PackSkuAdminDto,
  PackSkuStatus,
  PackUnitType,
  VoucherAdminDto,
  VoucherStatus,
  VoucherType,
} from "@/lib/api/types";
import {
  formatCompactNumber,
  formatDateTime,
  formatMicroUsd,
  MICROS_PER_USD,
  parseUsdToMicros,
} from "@/lib/format/micro-usd";
import {
  canCommerceWrite,
  PACK_SKU_STATUS_LABEL,
  PACK_UNIT_LABEL,
  VOUCHER_STATUS_LABEL,
  VOUCHER_TYPE_LABEL,
} from "@/lib/format/labels";

interface SkuFormState {
  code: string;
  name: string;
  unitType: PackUnitType;
  applicableModelsCsv: string;
  totalUnits: string;
  priceUsd: string;
  validDays: string;
  status: PackSkuStatus;
}

const EMPTY_SKU_FORM: SkuFormState = {
  code: "",
  name: "",
  unitType: "TOKENS",
  applicableModelsCsv: "",
  totalUnits: "10000000",
  priceUsd: "19.90",
  validDays: "30",
  status: "ACTIVE",
};

interface VoucherFormState {
  name: string;
  voucherType: VoucherType;
  amountUsd: string;
  applicableModelsCsv: string;
  validDays: string;
  maxTotal: string;
  maxPerOrg: string;
}

const EMPTY_VOUCHER_FORM: VoucherFormState = {
  name: "",
  voucherType: "PROMO_CODE",
  amountUsd: "10.00",
  applicableModelsCsv: "",
  validDays: "30",
  maxTotal: "100",
  maxPerOrg: "1",
};

function downloadCsv(filename: string, codes: string[]) {
  const csv = ["code", ...codes].join("\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export default function AdminCommercePage() {
  const { profile } = useAdminAuth();
  const canEdit = canCommerceWrite(profile?.role);

  const [tab, setTab] = useState<"skus" | "vouchers">("skus");
  const [skuStatusFilter, setSkuStatusFilter] = useState<"ALL" | PackSkuStatus>(
    "ALL",
  );

  const skusState = useAsyncData(
    (signal) =>
      adminCommerceApi.listSkus(
        {
          status: skuStatusFilter === "ALL" ? undefined : skuStatusFilter,
          page: 1,
          pageSize: 50,
        },
        signal,
      ),
    [skuStatusFilter],
  );

  const vouchersState = useAsyncData(
    (signal) => adminCommerceApi.listVouchers(1, 50, signal),
    [],
  );

  // SKU Sheet
  const [skuSheetOpen, setSkuSheetOpen] = useState(false);
  const [editingSku, setEditingSku] = useState<PackSkuAdminDto | null>(null);
  const [skuForm, setSkuForm] = useState<SkuFormState>(EMPTY_SKU_FORM);
  const [savingSku, setSavingSku] = useState(false);
  const [skuError, setSkuError] = useState<unknown>(null);

  // Voucher Sheet
  const [voucherSheetOpen, setVoucherSheetOpen] = useState(false);
  const [voucherForm, setVoucherForm] =
    useState<VoucherFormState>(EMPTY_VOUCHER_FORM);
  const [savingVoucher, setSavingVoucher] = useState(false);
  const [voucherError, setVoucherError] = useState<unknown>(null);

  // Code Batch Dialog
  const [batchVoucher, setBatchVoucher] = useState<VoucherAdminDto | null>(null);
  const [batchQty, setBatchQty] = useState("20");
  const [generatingBatch, setGeneratingBatch] = useState(false);
  const [batchError, setBatchError] = useState<unknown>(null);
  const [batchResult, setBatchResult] = useState<CodeBatchResponse | null>(null);

  function openCreateSku() {
    setEditingSku(null);
    setSkuForm(EMPTY_SKU_FORM);
    setSkuError(null);
    setSkuSheetOpen(true);
  }

  function openEditSku(sku: PackSkuAdminDto) {
    setEditingSku(sku);
    setSkuForm({
      code: sku.code,
      name: sku.name,
      unitType: sku.unitType,
      applicableModelsCsv: sku.applicableModels.join(", "),
      totalUnits: String(sku.totalUnits),
      priceUsd: String(sku.priceMicros / MICROS_PER_USD),
      validDays: String(sku.validDays),
      status: sku.status,
    });
    setSkuError(null);
    setSkuSheetOpen(true);
  }

  async function handleSubmitSku(e: FormEvent) {
    e.preventDefault();
    setSkuError(null);

    const priceMicros = parseUsdToMicros(skuForm.priceUsd);
    const totalUnits = Number(skuForm.totalUnits);
    const validDays = Number(skuForm.validDays);
    const applicableModels = skuForm.applicableModelsCsv
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);

    if (!priceMicros || priceMicros <= 0) {
      toast.error("请输入有效售价");
      return;
    }
    if (applicableModels.length === 0) {
      toast.error("请至少填写一个适用模型标识");
      return;
    }

    setSavingSku(true);
    try {
      if (editingSku) {
        await adminCommerceApi.updateSku(editingSku.id, {
          name: skuForm.name.trim(),
          unitType: skuForm.unitType,
          applicableModels,
          totalUnits,
          priceMicros,
          validDays,
          status: skuForm.status,
          version: editingSku.version,
        });
        toast.success("资源包商品已更新");
      } else {
        await adminCommerceApi.createSku({
          code: skuForm.code.trim(),
          name: skuForm.name.trim(),
          unitType: skuForm.unitType,
          applicableModels,
          totalUnits,
          priceMicros,
          validDays,
          status: skuForm.status,
        });
        toast.success("资源包商品已创建");
      }
      setSkuSheetOpen(false);
      skusState.reload();
    } catch (err) {
      setSkuError(err);
    } finally {
      setSavingSku(false);
    }
  }

  async function handleCreateVoucher(e: FormEvent) {
    e.preventDefault();
    setVoucherError(null);
    const amountMicros = parseUsdToMicros(voucherForm.amountUsd);
    if (!amountMicros || amountMicros <= 0) {
      toast.error("请输入有效面额");
      return;
    }

    const models = voucherForm.applicableModelsCsv
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);

    setSavingVoucher(true);
    try {
      await adminCommerceApi.createVoucher({
        name: voucherForm.name.trim(),
        voucherType: voucherForm.voucherType,
        amountMicros,
        applicableModels: models.length > 0 ? models : null,
        validDays: voucherForm.validDays.trim()
          ? Number(voucherForm.validDays)
          : null,
        fixedExpiresAt: null,
        maxRedemptionsTotal: voucherForm.maxTotal.trim()
          ? Number(voucherForm.maxTotal)
          : null,
        maxRedemptionsPerOrg: Math.max(1, Number(voucherForm.maxPerOrg) || 1),
      });
      toast.success("代金券活动已创建");
      setVoucherSheetOpen(false);
      setVoucherForm(EMPTY_VOUCHER_FORM);
      vouchersState.reload();
    } catch (err) {
      setVoucherError(err);
    } finally {
      setSavingVoucher(false);
    }
  }

  async function handleVoucherStatus(
    voucher: VoucherAdminDto,
    status: VoucherStatus,
  ) {
    try {
      await adminCommerceApi.updateVoucherStatus(
        voucher.id,
        status,
        voucher.version,
      );
      toast.success(`活动状态已更新为「${VOUCHER_STATUS_LABEL[status]}」`);
      vouchersState.reload();
    } catch {
      toast.error("状态更新失败，请刷新重试");
    }
  }

  async function handleGenerateBatch(e: FormEvent) {
    e.preventDefault();
    if (!batchVoucher) return;
    setBatchError(null);
    const quantity = Math.min(10_000, Math.max(1, Number(batchQty) || 10));
    setGeneratingBatch(true);
    try {
      const res = await adminCommerceApi.createCodeBatch(
        batchVoucher.id,
        quantity,
      );
      setBatchResult(res);
      toast.success(`已生成 ${res.quantity} 个兑换码`);
    } catch (err) {
      setBatchError(err);
    } finally {
      setGeneratingBatch(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs
          value={tab}
          onValueChange={(v) => setTab(v as "skus" | "vouchers")}
        >
          <TabsList>
            <TabsTrigger value="skus">
              资源包 SKU（{skusState.data?.total ?? 0}）
            </TabsTrigger>
            <TabsTrigger value="vouchers">
              代金券与兑换码（{vouchersState.data?.total ?? 0}）
            </TabsTrigger>
          </TabsList>
        </Tabs>

        {canEdit ? (
          tab === "skus" ? (
            <Button size="sm" onClick={openCreateSku}>
              <Plus className="size-3.5" />
              <span>新增资源包</span>
            </Button>
          ) : (
            <Button
              size="sm"
              onClick={() => {
                setVoucherError(null);
                setVoucherSheetOpen(true);
              }}
            >
              <Plus className="size-3.5" />
              <span>新建代金券</span>
            </Button>
          )
        ) : null}
      </div>

      <ApiErrorAlert
        error={skusState.error ?? vouchersState.error}
        onRetry={() => {
          skusState.reload();
          vouchersState.reload();
        }}
      />

      <Tabs value={tab} className="gap-4">
        {/* SKUs */}
        <TabsContent value="skus" className="flex flex-col gap-4">
          <Tabs
            value={skuStatusFilter}
            onValueChange={(v) =>
              setSkuStatusFilter(v as "ALL" | PackSkuStatus)
            }
          >
            <TabsList>
              <TabsTrigger value="ALL">全部</TabsTrigger>
              <TabsTrigger value="ACTIVE">在售</TabsTrigger>
              <TabsTrigger value="DRAFT">草稿</TabsTrigger>
              <TabsTrigger value="RETIRED">已下架</TabsTrigger>
            </TabsList>
          </Tabs>

          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {skusState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : (skusState.data?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<Package className="size-4" />}
                title="暂无资源包商品"
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>商品名称 / 编码</TableHead>
                    <TableHead>适用模型</TableHead>
                    <TableHead className="text-right">额度 / 有效期</TableHead>
                    <TableHead className="text-right">售价</TableHead>
                    <TableHead>状态</TableHead>
                    {canEdit ? (
                      <TableHead className="text-right">操作</TableHead>
                    ) : null}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(skusState.data?.items ?? []).map((sku) => (
                    <TableRow key={sku.id}>
                      <TableCell>
                        <div className="font-medium">{sku.name}</div>
                        <code className="font-mono text-xs text-muted-foreground">
                          {sku.code}
                        </code>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {sku.applicableModels.map((m) => (
                            <Badge
                              key={m}
                              variant="outline"
                              className="font-mono text-[10px]"
                            >
                              {m}
                            </Badge>
                          ))}
                        </div>
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        <div>
                          {formatCompactNumber(sku.totalUnits)}{" "}
                          {PACK_UNIT_LABEL[sku.unitType]}
                        </div>
                        <div className="text-[11px] text-muted-foreground">
                          {sku.validDays} 天
                        </div>
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs font-medium tabular-nums">
                        {formatMicroUsd(sku.priceMicros)}
                      </TableCell>
                      <TableCell>
                        <StatusDot
                          tone={
                            sku.status === "ACTIVE"
                              ? "success"
                              : sku.status === "DRAFT"
                                ? "warning"
                                : "muted"
                          }
                          label={PACK_SKU_STATUS_LABEL[sku.status]}
                        />
                      </TableCell>
                      {canEdit ? (
                        <TableCell className="text-right">
                          <Button
                            size="icon-xs"
                            variant="ghost"
                            aria-label="编辑商品"
                            onClick={() => openEditSku(sku)}
                          >
                            <Pencil className="size-3.5" />
                          </Button>
                        </TableCell>
                      ) : null}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
        </TabsContent>

        {/* Vouchers */}
        <TabsContent value="vouchers">
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {vouchersState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : (vouchersState.data?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<Ticket className="size-4" />}
                title="暂无代金券活动"
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>活动名称 / 类型</TableHead>
                    <TableHead className="text-right">面额</TableHead>
                    <TableHead>适用范围 / 期限</TableHead>
                    <TableHead>状态</TableHead>
                    {canEdit ? (
                      <TableHead className="text-right">操作</TableHead>
                    ) : null}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(vouchersState.data?.items ?? []).map((v) => (
                    <TableRow key={v.id}>
                      <TableCell>
                        <div className="font-medium">{v.name}</div>
                        <div className="text-xs text-muted-foreground">
                          {VOUCHER_TYPE_LABEL[v.voucherType]} ·{" "}
                          {formatDateTime(v.createdAt)}
                        </div>
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs font-medium tabular-nums">
                        {formatMicroUsd(v.amountMicros)}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        <div>
                          {v.applicableModels.length > 0
                            ? v.applicableModels.join(", ")
                            : "全平台通用"}
                        </div>
                        <div className="tabular-nums">
                          {v.validDays ? `${v.validDays} 天` : "永久"} · 单组织限{" "}
                          {v.maxRedemptionsPerOrg} 次
                        </div>
                      </TableCell>
                      <TableCell>
                        {canEdit ? (
                          <Select
                            value={v.status}
                            onValueChange={(next) =>
                              next &&
                              handleVoucherStatus(v, next as VoucherStatus)
                            }
                          >
                            <SelectTrigger size="sm" className="w-28">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="ACTIVE">进行中</SelectItem>
                              <SelectItem value="PAUSED">已暂停</SelectItem>
                              <SelectItem value="ENDED">已结束</SelectItem>
                            </SelectContent>
                          </Select>
                        ) : (
                          <StatusDot
                            tone={v.status === "ACTIVE" ? "success" : "muted"}
                            label={VOUCHER_STATUS_LABEL[v.status]}
                          />
                        )}
                      </TableCell>
                      {canEdit ? (
                        <TableCell className="text-right">
                          <Button
                            size="xs"
                            variant="outline"
                            disabled={v.status !== "ACTIVE"}
                            onClick={() => {
                              setBatchVoucher(v);
                              setBatchQty("20");
                              setBatchResult(null);
                              setBatchError(null);
                            }}
                          >
                            <Ticket className="size-3" />
                            <span>生成兑换码</span>
                          </Button>
                        </TableCell>
                      ) : null}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
        </TabsContent>
      </Tabs>

      {/* SKU Create/Edit Dialog */}
      <Dialog open={skuSheetOpen} onOpenChange={setSkuSheetOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {editingSku ? "编辑资源包 SKU" : "新增资源包 SKU"}
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleSubmitSku} className="flex flex-col gap-4">
            <ApiErrorAlert error={skuError} />
            {!editingSku ? (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="sku-code">商品编码</Label>
                <Input
                  id="sku-code"
                  required
                  value={skuForm.code}
                  onChange={(e) =>
                    setSkuForm({ ...skuForm, code: e.target.value })
                  }
                  placeholder="pack-sonnet-10m"
                  className="font-mono text-xs"
                />
              </div>
            ) : null}
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="sku-name">商品名称</Label>
              <Input
                id="sku-name"
                required
                value={skuForm.name}
                onChange={(e) =>
                  setSkuForm({ ...skuForm, name: e.target.value })
                }
                placeholder="Claude Sonnet 1000 万 Token 包"
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label>计量单位</Label>
                <Select
                  value={skuForm.unitType}
                  onValueChange={(v) =>
                    setSkuForm({
                      ...skuForm,
                      unitType: (v as PackUnitType) ?? "TOKENS",
                    })
                  }
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="TOKENS">Token</SelectItem>
                    <SelectItem value="CALLS">调用次数</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="sku-units">包含总量</Label>
                <Input
                  id="sku-units"
                  required
                  inputMode="numeric"
                  value={skuForm.totalUnits}
                  onChange={(e) =>
                    setSkuForm({ ...skuForm, totalUnits: e.target.value })
                  }
                  className="font-mono tabular-nums"
                />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="sku-price">售价</Label>
                <div className="relative">
                  <Input
                    id="sku-price"
                    required
                    inputMode="decimal"
                    value={skuForm.priceUsd}
                    onChange={(e) =>
                      setSkuForm({ ...skuForm, priceUsd: e.target.value })
                    }
                    className="pr-11 font-mono tabular-nums"
                  />
                  <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                    USD
                  </span>
                </div>
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="sku-days">有效天数</Label>
                <div className="relative">
                  <Input
                    id="sku-days"
                    required
                    inputMode="numeric"
                    value={skuForm.validDays}
                    onChange={(e) =>
                      setSkuForm({ ...skuForm, validDays: e.target.value })
                    }
                    className="pr-9 font-mono tabular-nums"
                  />
                  <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                    天
                  </span>
                </div>
              </div>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="sku-models">适用模型标识（逗号分隔）</Label>
              <Input
                id="sku-models"
                required
                value={skuForm.applicableModelsCsv}
                onChange={(e) =>
                  setSkuForm({
                    ...skuForm,
                    applicableModelsCsv: e.target.value,
                  })
                }
                placeholder="claude-sonnet-4-5, gpt-4o"
                className="font-mono text-xs"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label>上架状态</Label>
              <Select
                value={skuForm.status}
                onValueChange={(v) =>
                  setSkuForm({
                    ...skuForm,
                    status: (v as PackSkuStatus) ?? "ACTIVE",
                  })
                }
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ACTIVE">在售</SelectItem>
                  <SelectItem value="DRAFT">草稿</SelectItem>
                  <SelectItem value="RETIRED">已下架</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setSkuSheetOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={savingSku}>
                {savingSku ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>{editingSku ? "保存商品" : "创建商品"}</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Voucher Create Dialog */}
      <Dialog open={voucherSheetOpen} onOpenChange={setVoucherSheetOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>新建代金券活动</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreateVoucher} className="flex flex-col gap-4">
            <ApiErrorAlert error={voucherError} />
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="v-name">活动名称</Label>
              <Input
                id="v-name"
                required
                value={voucherForm.name}
                onChange={(e) =>
                  setVoucherForm({ ...voucherForm, name: e.target.value })
                }
                placeholder="新客体验赠金 $10"
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label>活动类型</Label>
                <Select
                  value={voucherForm.voucherType}
                  onValueChange={(v) =>
                    setVoucherForm({
                      ...voucherForm,
                      voucherType: (v as VoucherType) ?? "PROMO_CODE",
                    })
                  }
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="PROMO_CODE">活动码</SelectItem>
                    <SelectItem value="CAMPAIGN">定向活动</SelectItem>
                    <SelectItem value="SIGNUP_BONUS">注册赠金</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="v-amount">单次面额</Label>
                <div className="relative">
                  <Input
                    id="v-amount"
                    required
                    inputMode="decimal"
                    value={voucherForm.amountUsd}
                    onChange={(e) =>
                      setVoucherForm({
                        ...voucherForm,
                        amountUsd: e.target.value,
                      })
                    }
                    className="pr-11 font-mono tabular-nums"
                  />
                  <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                    USD
                  </span>
                </div>
              </div>
            </div>
            <div className="grid grid-cols-3 gap-2.5">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="v-days">有效天数</Label>
                <Input
                  id="v-days"
                  inputMode="numeric"
                  value={voucherForm.validDays}
                  onChange={(e) =>
                    setVoucherForm({
                      ...voucherForm,
                      validDays: e.target.value,
                    })
                  }
                  className="font-mono tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="v-max">总核销上限</Label>
                <Input
                  id="v-max"
                  inputMode="numeric"
                  value={voucherForm.maxTotal}
                  onChange={(e) =>
                    setVoucherForm({
                      ...voucherForm,
                      maxTotal: e.target.value,
                    })
                  }
                  placeholder="不限"
                  className="font-mono tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="v-per-org">每组织限次</Label>
                <Input
                  id="v-per-org"
                  required
                  inputMode="numeric"
                  value={voucherForm.maxPerOrg}
                  onChange={(e) =>
                    setVoucherForm({
                      ...voucherForm,
                      maxPerOrg: e.target.value,
                    })
                  }
                  className="font-mono tabular-nums"
                />
              </div>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="v-models">限定模型（留空全通用）</Label>
              <Input
                id="v-models"
                value={voucherForm.applicableModelsCsv}
                onChange={(e) =>
                  setVoucherForm({
                    ...voucherForm,
                    applicableModelsCsv: e.target.value,
                  })
                }
                placeholder="gpt-4o, claude-sonnet-4-5"
                className="font-mono text-xs"
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setVoucherSheetOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={savingVoucher}>
                {savingVoucher ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>创建活动</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Batch Redemption Codes Dialog */}
      <Dialog
        open={Boolean(batchVoucher)}
        onOpenChange={(open) => {
          if (!open) {
            setBatchVoucher(null);
            setBatchResult(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {batchResult
                ? `已生成 ${batchResult.quantity} 个兑换码`
                : `为「${batchVoucher?.name}」生成兑换码`}
            </DialogTitle>
          </DialogHeader>

          {!batchResult ? (
            <form onSubmit={handleGenerateBatch} className="flex flex-col gap-4">
              <ApiErrorAlert error={batchError} />
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="batch-qty">生成数量（1–10,000）</Label>
                <Input
                  id="batch-qty"
                  type="number"
                  min={1}
                  max={10000}
                  required
                  value={batchQty}
                  onChange={(e) => setBatchQty(e.target.value)}
                  className="font-mono tabular-nums"
                />
              </div>
              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setBatchVoucher(null)}
                >
                  取消
                </Button>
                <Button type="submit" disabled={generatingBatch}>
                  {generatingBatch ? (
                    <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                  ) : null}
                  <span>生成批次</span>
                </Button>
              </DialogFooter>
            </form>
          ) : (
            <div className="flex flex-col gap-4">
              <div className="flex items-center gap-2 text-xs text-amber-700 dark:text-amber-400">
                <AlertTriangle className="size-4 shrink-0" />
                <span>兑换码明文仅在此时展示一次，请立即复制或导出 CSV。</span>
              </div>
              <pre className="max-h-56 overflow-auto rounded-lg border border-border bg-muted/45 p-3 font-mono text-xs leading-relaxed">
                {batchResult.codes.join("\n")}
              </pre>
              <DialogFooter className="sm:justify-between">
                <div className="flex items-center gap-2">
                  <CopyButton
                    value={batchResult.codes.join("\n")}
                    label="复制全部"
                    variant="outline"
                    copiedToast="已复制全部兑换码"
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="xs"
                    onClick={() =>
                      downloadCsv(
                        `voucher-codes-${batchResult.quantity}.csv`,
                        batchResult.codes,
                      )
                    }
                  >
                    <Download className="size-3.5" />
                    <span>导出 CSV</span>
                  </Button>
                </div>
                <Button
                  onClick={() => {
                    setBatchVoucher(null);
                    setBatchResult(null);
                  }}
                >
                  完成
                </Button>
              </DialogFooter>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
