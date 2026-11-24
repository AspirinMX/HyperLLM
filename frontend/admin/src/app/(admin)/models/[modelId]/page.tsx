"use client";

import { Suspense, use, useMemo, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ArrowLeft,
  CalendarClock,
  Layers,
  Loader2,
  Plus,
  Save,
  Trash2,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Sheet,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
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
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { EmptyState } from "@/components/common/empty-state";
import { ProportionBar } from "@/components/common/proportion-bar";
import { StatusDot } from "@/components/common/status-dot";
import { useAdminAuth } from "@/components/providers/admin-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminChannelApi, adminModelApi } from "@/lib/api/endpoints";
import type {
  BillingMode,
  BindingItemRequest,
  BindingStatus,
  ModelStatus,
  ModelVisibility,
  PricingRuleDto,
  RoutingStrategy,
  TranslationPolicy,
  VirtualModelDto,
  WireProtocol,
} from "@/lib/api/types";
import {
  formatFullDateTime,
  formatLocalDateTimeInput,
  formatMicroUsd,
  formatPerMillionUsd,
  parseUsdToMicros,
} from "@/lib/format/micro-usd";
import {
  BILLING_MODE_LABEL,
  canCommerceWrite,
  canOpsWrite,
  MODEL_STATUS_LABEL,
  ROUTING_STRATEGY_LABEL,
} from "@/lib/format/labels";
import { cn } from "@/lib/utils";

const TIER_COLORS = [
  "bg-[var(--chart-1)]",
  "bg-[var(--chart-2)]",
  "bg-[var(--chart-3)]",
  "bg-[var(--chart-4)]",
];

interface DraftBinding {
  key: string;
  channelId: string;
  upstreamModelName: string;
  upstreamProtocol: WireProtocol | null;
  priorityTier: number;
  weight: number;
  discountPercent: string;
  status: BindingStatus;
}

function classifyRuleStatus(rule: PricingRuleDto): "ACTIVE" | "SCHEDULED" | "EXPIRED" {
  const now = Date.now();
  const from = new Date(rule.effectiveFrom).getTime();
  const to = rule.effectiveTo ? new Date(rule.effectiveTo).getTime() : null;
  if (from > now) return "SCHEDULED";
  if (to !== null && to <= now) return "EXPIRED";
  return "ACTIVE";
}

function ModelWorkbenchContent({
  params,
}: {
  params: Promise<{ modelId: string }>;
}) {
  const { modelId } = use(params);
  const router = useRouter();
  const { profile } = useAdminAuth();
  const canEditOps = canOpsWrite(profile?.role);
  const canEditPricing = canCommerceWrite(profile?.role);

  const modelState = useAsyncData(
    (signal) => adminModelApi.getModel(modelId, signal),
    [modelId],
  );

  const channelsState = useAsyncData(
    (signal) => adminChannelApi.listChannels(1, 200, signal),
    [],
  );

  const bindingsState = useAsyncData(
    (signal) => adminModelApi.listBindings(modelId, signal),
    [modelId],
  );

  const pricingState = useAsyncData(
    (signal) => adminModelApi.listPricingRules(modelId, signal),
    [modelId],
  );

  // Routing draft state (null = synced with server)
  const [draftBindings, setDraftBindings] = useState<DraftBinding[] | null>(null);
  const [savingBindings, setSavingBindings] = useState(false);
  const [bindingError, setBindingError] = useState<unknown>(null);

  // Pricing sheet state
  const [priceSheetOpen, setPriceSheetOpen] = useState(false);
  const [billingMode, setBillingMode] = useState<BillingMode>("TOKEN");
  const [inPrice, setInPrice] = useState("3.00");
  const [outPrice, setOutPrice] = useState("15.00");
  const [cacheReadPrice, setCacheReadPrice] = useState("0.30");
  const [cacheWritePrice, setCacheWritePrice] = useState("3.75");
  const [reasonPrice, setReasonPrice] = useState("0");
  const [perCallPrice, setPerCallPrice] = useState("0");
  const [effectiveFromLocal, setEffectiveFromLocal] = useState(() =>
    formatLocalDateTimeInput(new Date(Date.now() + 60_000)),
  );
  const [effectiveToLocal, setEffectiveToLocal] = useState("");
  const [publishingPrice, setPublishingPrice] = useState(false);
  const [priceError, setPriceError] = useState<unknown>(null);

  // Base config draft
  const [baseDraft, setBaseDraft] = useState<VirtualModelDto | null>(null);
  const [savingBase, setSavingBase] = useState(false);
  const [baseError, setBaseError] = useState<unknown>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const model = baseDraft ?? modelState.data;

  const activeBindings: DraftBinding[] = useMemo(() => {
    if (draftBindings !== null) return draftBindings;
    return (bindingsState.data ?? []).map((b) => ({
      key: b.id,
      channelId: b.channelId,
      upstreamModelName: b.upstreamModelName,
      upstreamProtocol: b.upstreamProtocol ?? null,
      priorityTier: b.priorityTier,
      weight: b.weight,
      discountPercent:
        b.costDiscountBpsOverride !== null
          ? String(b.costDiscountBpsOverride / 100)
          : "",
      status: b.status,
    }));
  }, [draftBindings, bindingsState.data]);

  const channelNameMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const c of channelsState.data?.items ?? []) {
      map.set(c.id, c.name);
    }
    return map;
  }, [channelsState.data]);

  const tiers = useMemo(() => {
    const grouped = new Map<number, DraftBinding[]>();
    for (const b of activeBindings) {
      const list = grouped.get(b.priorityTier) ?? [];
      list.push(b);
      grouped.set(b.priorityTier, list);
    }
    return Array.from(grouped.entries()).sort((a, b) => a[0] - b[0]);
  }, [activeBindings]);

  function mutateBindings(updater: (prev: DraftBinding[]) => DraftBinding[]) {
    setDraftBindings((prev) => updater(prev ?? activeBindings));
  }

  function addBindingRow(tier = 1) {
    const defaultChannel = channelsState.data?.items[0]?.id ?? "";
    mutateBindings((prev) => [
      ...prev,
      {
        key: `new-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        channelId: defaultChannel,
        upstreamModelName: model?.modelCode ?? "",
        upstreamProtocol: null,
        priorityTier: tier,
        weight: 100,
        discountPercent: "",
        status: "ACTIVE",
      },
    ]);
  }

  async function handleSaveBindings() {
    setBindingError(null);
    const payload: BindingItemRequest[] = activeBindings.map((b) => ({
      channelId: b.channelId,
      upstreamModelName: b.upstreamModelName.trim(),
      upstreamProtocol: b.upstreamProtocol,
      priorityTier: Math.max(1, b.priorityTier),
      weight: Math.max(1, b.weight),
      costDiscountBpsOverride: b.discountPercent.trim()
        ? Math.round(Number(b.discountPercent) * 100)
        : null,
      status: b.status,
    }));

    setSavingBindings(true);
    try {
      await adminModelApi.replaceBindings(modelId, payload);
      setDraftBindings(null);
      toast.success("路由编排已保存并同步");
      bindingsState.reload();
    } catch (err) {
      setBindingError(err);
    } finally {
      setSavingBindings(false);
    }
  }

  async function handleClearBindings() {
    setSavingBindings(true);
    try {
      await adminModelApi.clearBindings(modelId);
      setDraftBindings(null);
      toast.success("已清空路由绑定");
      bindingsState.reload();
    } catch (err) {
      setBindingError(err);
    } finally {
      setSavingBindings(false);
    }
  }

  async function handlePublishPricing(e: FormEvent) {
    e.preventDefault();
    setPriceError(null);

    const inputMicros = parseUsdToMicros(inPrice) ?? 0;
    const outputMicros = parseUsdToMicros(outPrice) ?? 0;
    const cacheReadMicros = parseUsdToMicros(cacheReadPrice) ?? 0;
    const cacheWriteMicros = parseUsdToMicros(cacheWritePrice) ?? 0;
    const reasoningMicros = parseUsdToMicros(reasonPrice) ?? 0;
    const perCallMicros = parseUsdToMicros(perCallPrice) ?? 0;

    setPublishingPrice(true);
    try {
      await adminModelApi.publishPricingRule(modelId, {
        billingMode,
        inputPriceMicros1m: inputMicros,
        outputPriceMicros1m: outputMicros,
        cacheReadPriceMicros1m: cacheReadMicros,
        cacheWritePriceMicros1m: cacheWriteMicros,
        reasoningPriceMicros1m: reasoningMicros,
        perCallMicros,
        effectiveFrom: new Date(effectiveFromLocal).toISOString(),
        effectiveTo: effectiveToLocal
          ? new Date(effectiveToLocal).toISOString()
          : null,
      });
      toast.success("新定价版本已发布");
      setPriceSheetOpen(false);
      pricingState.reload();
    } catch (err) {
      setPriceError(err);
    } finally {
      setPublishingPrice(false);
    }
  }

  async function handleSaveBase(e: FormEvent) {
    e.preventDefault();
    if (!model) return;
    setBaseError(null);
    setSavingBase(true);
    try {
      await adminModelApi.updateModel(model.id, {
        displayName: model.displayName.trim(),
        providerBadge: model.providerBadge?.trim() || null,
        contextWindow: model.contextWindow,
        maxOutputTokens: model.maxOutputTokens,
        supportsTools: model.supportsTools,
        supportsVision: model.supportsVision,
        supportsThinking: model.supportsThinking,
        supportsPromptCache: model.supportsPromptCache,
        routingStrategy: model.routingStrategy,
        stickyCacheEnabled: model.stickyCacheEnabled,
        stickyTtlSeconds: model.stickyTtlSeconds,
        visibility: model.visibility,
        fallbackModelCode: model.fallbackModelCode?.trim() || null,
        status: model.status,
        allowProtocolTranslation: model.allowProtocolTranslation,
        translationPolicy: model.translationPolicy,
        version: model.version,
      });
      setBaseDraft(null);
      toast.success("模型基础配置已保存");
      modelState.reload();
    } catch (err) {
      setBaseError(err);
    } finally {
      setSavingBase(false);
    }
  }

  async function handleDeleteModel() {
    if (!model) return;
    setDeleting(true);
    try {
      await adminModelApi.deleteModel(model.id, model.version);
      toast.success(`已删除模型「${model.displayName}」`);
      router.replace("/models");
    } catch {
      toast.error("删除失败，请刷新重试");
      setDeleting(false);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon-sm"
            render={<Link href="/models" />}
            aria-label="返回模型目录"
          >
            <ArrowLeft className="size-4" />
          </Button>
          {model ? (
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-lg font-semibold tracking-tight">
                {model.displayName}
              </h1>
              <code className="rounded-md bg-muted px-2 py-0.5 font-mono text-xs text-muted-foreground">
                {model.modelCode}
              </code>
              <Badge variant="secondary">
                {ROUTING_STRATEGY_LABEL[model.routingStrategy]}
              </Badge>
              <StatusDot
                tone={model.status === "ACTIVE" ? "success" : "warning"}
                label={MODEL_STATUS_LABEL[model.status]}
              />
            </div>
          ) : (
            <Skeleton className="h-6 w-48" />
          )}
        </div>
      </div>

      <Tabs defaultValue="routing" className="gap-4">
        <TabsList>
          <TabsTrigger value="routing">
            路由编排（{activeBindings.length}）
          </TabsTrigger>
          <TabsTrigger value="pricing">
            版本定价（{pricingState.data?.length ?? 0}）
          </TabsTrigger>
          <TabsTrigger value="settings">基础配置</TabsTrigger>
        </TabsList>

        {/* Tab 1: Visual Tier Routing Topology */}
        <TabsContent value="routing" className="flex flex-col gap-4">
          <ApiErrorAlert error={bindingError ?? bindingsState.error} />

          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              {canEditOps ? (
                <>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => addBindingRow(1)}
                  >
                    <Plus className="size-3.5" />
                    <span>添加主力池绑定</span>
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() =>
                      addBindingRow(
                        tiers.length > 0 ? tiers[tiers.length - 1][0] + 1 : 2,
                      )
                    }
                  >
                    <Layers className="size-3.5" />
                    <span>新增备援层级</span>
                  </Button>
                </>
              ) : null}
            </div>

            {canEditOps ? (
              <div className="flex items-center gap-2">
                {activeBindings.length > 0 ? (
                  <Button
                    size="sm"
                    variant="ghost"
                    className="text-destructive hover:text-destructive"
                    disabled={savingBindings}
                    onClick={handleClearBindings}
                  >
                    清空
                  </Button>
                ) : null}
                <Button
                  size="sm"
                  disabled={savingBindings || draftBindings === null}
                  onClick={handleSaveBindings}
                >
                  {savingBindings ? (
                    <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                  ) : (
                    <Save className="size-3.5" />
                  )}
                  <span>保存编排</span>
                </Button>
              </div>
            ) : null}
          </div>

          {tiers.length === 0 ? (
            <div className="squircle-md rounded-xl border border-border bg-card">
              <EmptyState
                icon={<Layers className="size-4" />}
                title="暂未绑定上游渠道"
                action={
                  canEditOps ? (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => addBindingRow(1)}
                    >
                      添加首个渠道绑定
                    </Button>
                  ) : null
                }
              />
            </div>
          ) : (
            <div className="flex flex-col gap-4">
              {tiers.map(([tierNum, items]) => {
                const activeInTier = items.filter((i) => i.status === "ACTIVE");
                const weightSum = activeInTier.reduce(
                  (s, i) => s + Math.max(0, i.weight),
                  0,
                );
                return (
                  <div
                    key={tierNum}
                    className="squircle-md overflow-hidden rounded-xl border border-border bg-card"
                  >
                    {/* Tier Header with live traffic-weight proportion bar */}
                    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border bg-muted/30 px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Badge variant="outline" className="font-mono text-xs">
                          Tier {tierNum}
                        </Badge>
                        <span className="text-xs font-medium">
                          {tierNum === 1 ? "主力路由池" : "备援降级池"}
                        </span>
                      </div>

                      <div className="flex items-center gap-3">
                        <ProportionBar
                          className="w-40"
                          heightClass="h-2"
                          total={weightSum}
                          segments={activeInTier.map((b, idx) => {
                            const pct =
                              weightSum > 0
                                ? ((b.weight / weightSum) * 100).toFixed(0)
                                : "0";
                            return {
                              key: b.key,
                              label:
                                channelNameMap.get(b.channelId) ?? "未选择渠道",
                              value: b.weight,
                              displayValue: `${pct}%`,
                              colorClass: TIER_COLORS[idx % TIER_COLORS.length],
                            };
                          })}
                        />
                        {canEditOps ? (
                          <Button
                            size="xs"
                            variant="ghost"
                            onClick={() => addBindingRow(tierNum)}
                          >
                            <Plus className="size-3" />
                            <span>添加</span>
                          </Button>
                        ) : null}
                      </div>
                    </div>

                    <div className="divide-y divide-border">
                      {items.map((item) => (
                        <div
                          key={item.key}
                          className="grid items-center gap-3 px-4 py-3 sm:grid-cols-12"
                        >
                          <div className="sm:col-span-3">
                            <Select
                              disabled={!canEditOps}
                              value={item.channelId}
                              onValueChange={(v) =>
                                mutateBindings((prev) =>
                                  prev.map((row) =>
                                    row.key === item.key
                                      ? { ...row, channelId: v ?? "" }
                                      : row,
                                  ),
                                )
                              }
                            >
                              <SelectTrigger className="w-full">
                                <SelectValue placeholder="选择上游渠道" />
                              </SelectTrigger>
                              <SelectContent>
                                {(channelsState.data?.items ?? []).map((c) => (
                                  <SelectItem key={c.id} value={c.id}>
                                    {c.name}
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                          </div>

                          <div className="sm:col-span-3">
                            <Input
                              disabled={!canEditOps}
                              value={item.upstreamModelName}
                              onChange={(e) =>
                                mutateBindings((prev) =>
                                  prev.map((row) =>
                                    row.key === item.key
                                      ? {
                                          ...row,
                                          upstreamModelName: e.target.value,
                                        }
                                      : row,
                                  ),
                                )
                              }
                              placeholder="上游真实模型名"
                              className="font-mono text-xs"
                            />
                          </div>

                          <div className="sm:col-span-2">
                            <Select
                              disabled={!canEditOps}
                              value={item.upstreamProtocol ?? "AUTO"}
                              onValueChange={(v) =>
                                mutateBindings((prev) =>
                                  prev.map((row) =>
                                    row.key === item.key
                                      ? {
                                          ...row,
                                          upstreamProtocol:
                                            v === "AUTO" || !v
                                              ? null
                                              : (v as WireProtocol),
                                        }
                                      : row,
                                  ),
                                )
                              }
                            >
                              <SelectTrigger className="w-full text-xs">
                                <SelectValue placeholder="协议自动" />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="AUTO">自动（默认）</SelectItem>
                                <SelectItem value="OPENAI_CHAT">OpenAI Chat</SelectItem>
                                <SelectItem value="OPENAI_RESPONSES">Responses</SelectItem>
                                <SelectItem value="ANTHROPIC_MESSAGES">Anthropic</SelectItem>
                                <SelectItem value="GEMINI_GENERATE_CONTENT">Gemini</SelectItem>
                              </SelectContent>
                            </Select>
                          </div>

                          <div className="flex items-center gap-1.5 sm:col-span-2">
                            <span className="text-[11px] text-muted-foreground">
                              层级
                            </span>
                            <Input
                              disabled={!canEditOps}
                              type="number"
                              min={1}
                              value={item.priorityTier}
                              onChange={(e) =>
                                mutateBindings((prev) =>
                                  prev.map((row) =>
                                    row.key === item.key
                                      ? {
                                          ...row,
                                          priorityTier: Math.max(
                                            1,
                                            Number(e.target.value) || 1,
                                          ),
                                        }
                                      : row,
                                  ),
                                )
                              }
                              className="font-mono text-xs tabular-nums"
                            />
                          </div>

                          <div className="flex items-center gap-1.5 sm:col-span-1">
                            <span className="text-[11px] text-muted-foreground">
                              权重
                            </span>
                            <Input
                              disabled={!canEditOps}
                              type="number"
                              min={1}
                              value={item.weight}
                              onChange={(e) =>
                                mutateBindings((prev) =>
                                  prev.map((row) =>
                                    row.key === item.key
                                      ? {
                                          ...row,
                                          weight: Math.max(
                                            1,
                                            Number(e.target.value) || 1,
                                          ),
                                        }
                                      : row,
                                  ),
                                )
                              }
                              className="font-mono text-xs tabular-nums"
                            />
                          </div>

                          <div className="flex items-center justify-end gap-2 sm:col-span-1">
                            <Switch
                              disabled={!canEditOps}
                              checked={item.status === "ACTIVE"}
                              onCheckedChange={(checked) =>
                                mutateBindings((prev) =>
                                  prev.map((row) =>
                                    row.key === item.key
                                      ? {
                                          ...row,
                                          status: checked
                                            ? "ACTIVE"
                                            : "DISABLED",
                                        }
                                      : row,
                                  ),
                                )
                              }
                            />
                            {canEditOps ? (
                              <Button
                                type="button"
                                variant="ghost"
                                size="icon-xs"
                                aria-label="移除绑定"
                                className="text-destructive hover:text-destructive"
                                onClick={() =>
                                  mutateBindings((prev) =>
                                    prev.filter((row) => row.key !== item.key),
                                  )
                                }
                              >
                                <Trash2 className="size-3.5" />
                              </Button>
                            ) : null}
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </TabsContent>

        {/* Tab 2: Versioned Pricing Timeline */}
        <TabsContent value="pricing" className="flex flex-col gap-4">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">定价版本时间轴</span>
            {canEditPricing ? (
              <Button
                size="sm"
                onClick={() => {
                  setPriceError(null);
                  setPriceSheetOpen(true);
                }}
              >
                <Plus className="size-3.5" />
                <span>发布新定价</span>
              </Button>
            ) : null}
          </div>

          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {(pricingState.data ?? []).length === 0 ? (
              <EmptyState
                icon={<CalendarClock className="size-4" />}
                title="尚未配置生效定价规则"
                action={
                  canEditPricing ? (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setPriceSheetOpen(true)}
                    >
                      发布首个定价
                    </Button>
                  ) : null
                }
              />
            ) : (
              <div className="divide-y divide-border">
                {(pricingState.data ?? []).map((rule) => {
                  const status = classifyRuleStatus(rule);
                  return (
                    <div
                      key={rule.id}
                      className={cn(
                        "flex flex-col gap-3 p-4",
                        status === "ACTIVE" && "border-l-2 border-l-emerald-500",
                      )}
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                        <div className="flex items-center gap-2">
                          <StatusDot
                            tone={
                              status === "ACTIVE"
                                ? "success"
                                : status === "SCHEDULED"
                                  ? "info"
                                  : "muted"
                            }
                            label={
                              status === "ACTIVE"
                                ? "当前生效"
                                : status === "SCHEDULED"
                                  ? "计划生效"
                                  : "已失效"
                            }
                          />
                          <Badge variant="outline">
                            {BILLING_MODE_LABEL[rule.billingMode]}
                          </Badge>
                        </div>
                        <span className="font-mono text-muted-foreground tabular-nums">
                          {formatFullDateTime(rule.effectiveFrom)} →{" "}
                          {rule.effectiveTo
                            ? formatFullDateTime(rule.effectiveTo)
                            : "长期有效"}
                        </span>
                      </div>

                      <div className="grid grid-cols-2 gap-3 text-xs sm:grid-cols-6">
                        <div>
                          <div className="text-muted-foreground">输入 / 1M</div>
                          <div className="mt-0.5 font-mono font-medium tabular-nums">
                            {formatPerMillionUsd(rule.inputPriceMicros1m)}
                          </div>
                        </div>
                        <div>
                          <div className="text-muted-foreground">输出 / 1M</div>
                          <div className="mt-0.5 font-mono font-medium tabular-nums">
                            {formatPerMillionUsd(rule.outputPriceMicros1m)}
                          </div>
                        </div>
                        <div>
                          <div className="text-muted-foreground">缓存读 / 1M</div>
                          <div className="mt-0.5 font-mono font-medium tabular-nums">
                            {formatPerMillionUsd(rule.cacheReadPriceMicros1m)}
                          </div>
                        </div>
                        <div>
                          <div className="text-muted-foreground">缓存写 / 1M</div>
                          <div className="mt-0.5 font-mono font-medium tabular-nums">
                            {formatPerMillionUsd(rule.cacheWritePriceMicros1m)}
                          </div>
                        </div>
                        <div>
                          <div className="text-muted-foreground">思考链 / 1M</div>
                          <div className="mt-0.5 font-mono font-medium tabular-nums">
                            {formatPerMillionUsd(rule.reasoningPriceMicros1m)}
                          </div>
                        </div>
                        <div>
                          <div className="text-muted-foreground">按次单价</div>
                          <div className="mt-0.5 font-mono font-medium tabular-nums">
                            {formatMicroUsd(rule.perCallMicros)}
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </TabsContent>

        {/* Tab 3: Base Config */}
        <TabsContent value="settings">
          {model ? (
            <form
              onSubmit={handleSaveBase}
              className="squircle-md flex max-w-2xl flex-col gap-5 rounded-xl border border-border bg-card p-5"
            >
              <ApiErrorAlert error={baseError} />
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="flex flex-col gap-1.5">
                  <Label>展示名称</Label>
                  <Input
                    disabled={!canEditOps}
                    value={model.displayName}
                    onChange={(e) =>
                      setBaseDraft({ ...model, displayName: e.target.value })
                    }
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label>厂商徽标</Label>
                  <Input
                    disabled={!canEditOps}
                    value={model.providerBadge ?? ""}
                    onChange={(e) =>
                      setBaseDraft({ ...model, providerBadge: e.target.value })
                    }
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label>路由策略</Label>
                  <Select
                    disabled={!canEditOps}
                    value={model.routingStrategy}
                    onValueChange={(v) =>
                      setBaseDraft({
                        ...model,
                        routingStrategy:
                          (v as RoutingStrategy) ?? model.routingStrategy,
                      })
                    }
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="PRIORITY_WEIGHTED">优先级加权</SelectItem>
                      <SelectItem value="LOWEST_COST">成本最优</SelectItem>
                      <SelectItem value="TTFT_AWARE">延迟感知</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label>模型状态</Label>
                  <Select
                    disabled={!canEditOps}
                    value={model.status}
                    onValueChange={(v) =>
                      setBaseDraft({
                        ...model,
                        status: (v as ModelStatus) ?? model.status,
                      })
                    }
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ACTIVE">已上架</SelectItem>
                      <SelectItem value="DEPRECATED">即将下线</SelectItem>
                      <SelectItem value="DISABLED">已停用</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label>可见性</Label>
                  <Select
                    disabled={!canEditOps}
                    value={model.visibility}
                    onValueChange={(v) =>
                      setBaseDraft({
                        ...model,
                        visibility: (v as ModelVisibility) ?? model.visibility,
                      })
                    }
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="PUBLIC">公开</SelectItem>
                      <SelectItem value="RESTRICTED">受限</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label>回退模型标识</Label>
                  <Input
                    disabled={!canEditOps}
                    value={model.fallbackModelCode ?? ""}
                    onChange={(e) =>
                      setBaseDraft({
                        ...model,
                        fallbackModelCode: e.target.value,
                      })
                    }
                    className="font-mono text-xs"
                  />
                </div>
                <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2 text-xs">
                  <span>允许跨协议转译</span>
                  <Switch
                    disabled={!canEditOps}
                    checked={model.allowProtocolTranslation}
                    onCheckedChange={(checked) =>
                      setBaseDraft({
                        ...model,
                        allowProtocolTranslation: checked,
                      })
                    }
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label>跨协议转译策略</Label>
                  <Select
                    disabled={!canEditOps || !model.allowProtocolTranslation}
                    value={model.translationPolicy}
                    onValueChange={(v) =>
                      setBaseDraft({
                        ...model,
                        translationPolicy:
                          (v as TranslationPolicy) ?? model.translationPolicy,
                      })
                    }
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="BEST_EFFORT">尽力兼容 (Best-Effort)</SelectItem>
                      <SelectItem value="STRICT">严格校验 (Strict)</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>

              {canEditOps ? (
                <div className="flex items-center justify-between border-t border-border pt-4">
                  <Button
                    type="button"
                    variant="destructive"
                    size="sm"
                    onClick={() => setDeleteOpen(true)}
                  >
                    <Trash2 className="size-3.5" />
                    <span>删除模型</span>
                  </Button>
                  <Button type="submit" size="sm" disabled={savingBase}>
                    {savingBase ? (
                      <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                    ) : (
                      <Save className="size-3.5" />
                    )}
                    <span>保存配置</span>
                  </Button>
                </div>
              ) : null}
            </form>
          ) : null}
        </TabsContent>
      </Tabs>

      {/* Publish Pricing Rule Sheet */}
      <Sheet open={priceSheetOpen} onOpenChange={setPriceSheetOpen}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle>发布新定价版本</SheetTitle>
          </SheetHeader>
          <form
            onSubmit={handlePublishPricing}
            className="flex flex-1 flex-col gap-4 px-6 pb-6"
          >
            <ApiErrorAlert error={priceError} />

            <div className="flex flex-col gap-1.5">
              <Label>计费模式</Label>
              <Select
                value={billingMode}
                onValueChange={(v) =>
                  setBillingMode((v as BillingMode) ?? "TOKEN")
                }
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="TOKEN">按 Token</SelectItem>
                  <SelectItem value="PER_CALL">按次</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {billingMode === "TOKEN" ? (
              <div className="grid grid-cols-2 gap-3">
                {[
                  ["输入 / 1M", inPrice, setInPrice],
                  ["输出 / 1M", outPrice, setOutPrice],
                  ["缓存读 / 1M", cacheReadPrice, setCacheReadPrice],
                  ["缓存写 / 1M", cacheWritePrice, setCacheWritePrice],
                  ["思考链 / 1M", reasonPrice, setReasonPrice],
                ].map(([label, value, setter]) => (
                  <div key={label as string} className="flex flex-col gap-1.5">
                    <Label>{label as string}</Label>
                    <div className="relative">
                      <Input
                        inputMode="decimal"
                        value={value as string}
                        onChange={(e) =>
                          (setter as (v: string) => void)(e.target.value)
                        }
                        className="pr-11 font-mono tabular-nums"
                      />
                      <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                        USD
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                <Label>每次调用单价</Label>
                <div className="relative">
                  <Input
                    inputMode="decimal"
                    value={perCallPrice}
                    onChange={(e) => setPerCallPrice(e.target.value)}
                    className="pr-11 font-mono tabular-nums"
                  />
                  <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                    USD
                  </span>
                </div>
              </div>
            )}

            <Separator />

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="pr-from">生效时间</Label>
                <Input
                  id="pr-from"
                  type="datetime-local"
                  required
                  value={effectiveFromLocal}
                  onChange={(e) => setEffectiveFromLocal(e.target.value)}
                  className="text-xs tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="pr-to">失效时间（留空长期）</Label>
                <Input
                  id="pr-to"
                  type="datetime-local"
                  value={effectiveToLocal}
                  onChange={(e) => setEffectiveToLocal(e.target.value)}
                  className="text-xs tabular-nums"
                />
              </div>
            </div>

            <SheetFooter className="px-0 pt-2">
              <Button
                type="submit"
                disabled={publishingPrice}
                className="w-full"
              >
                {publishingPrice ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>发布定价</span>
              </Button>
            </SheetFooter>
          </form>
        </SheetContent>
      </Sheet>

      {/* Delete Model Confirmation */}
      <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除虚拟模型「{model?.displayName}」？</AlertDialogTitle>
            <AlertDialogDescription>
              删除后网关将无法再路由该模型标识，此操作不可撤销。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={deleting}
              onClick={handleDeleteModel}
            >
              {deleting ? (
                <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
              ) : null}
              <span>确认删除</span>
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

export default function AdminModelDetailPage({
  params,
}: {
  params: Promise<{ modelId: string }>;
}) {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-4">
          <Skeleton className="h-7 w-48" />
          <Skeleton className="h-64 w-full" />
        </div>
      }
    >
      <ModelWorkbenchContent params={params} />
    </Suspense>
  );
}
