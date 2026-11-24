"use client";

import { useMemo, useState, type FormEvent } from "react";
import {
  Activity,
  Loader2,
  Pencil,
  PlugZap,
  Plus,
  ShieldCheck,
  Trash2,
} from "lucide-react";
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
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { EmptyState } from "@/components/common/empty-state";
import { StatusDot, type StatusTone } from "@/components/common/status-dot";
import { useAdminAuth } from "@/components/providers/admin-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminChannelApi } from "@/lib/api/endpoints";
import type {
  ChannelDto,
  ChannelProbeDto,
  ChannelStatus,
  ProviderDto,
} from "@/lib/api/types";
import { formatCompactNumber, formatLatencyMs } from "@/lib/format/micro-usd";
import {
  canOpsWrite,
  CHANNEL_STATUS_LABEL,
  formatBpsPercent,
  PROTOCOL_LABEL,
  WIRE_PROTOCOL_LABEL,
} from "@/lib/format/labels";
import { cn } from "@/lib/utils";

const CHANNEL_TONE: Record<ChannelStatus, StatusTone> = {
  ACTIVE: "success",
  DISABLED: "muted",
  CIRCUIT_OPEN: "danger",
  EXHAUSTED: "warning",
};

interface ChannelFormState {
  providerId: string;
  name: string;
  region: string;
  baseUrlOverride: string;
  apiKey: string;
  discountPercent: string;
  rpmLimit: string;
  tpmLimit: string;
  maxConcurrency: string;
  healthCheckModel: string;
  status: ChannelStatus;
}

const EMPTY_FORM: ChannelFormState = {
  providerId: "",
  name: "",
  region: "",
  baseUrlOverride: "",
  apiKey: "",
  discountPercent: "100",
  rpmLimit: "",
  tpmLimit: "",
  maxConcurrency: "",
  healthCheckModel: "",
  status: "ACTIVE",
};

function parsePosInt(v: string): number | null {
  const t = v.trim();
  if (!t) return null;
  const n = Number(t);
  return Number.isInteger(n) && n > 0 ? n : null;
}

export default function AdminChannelsPage() {
  const { profile } = useAdminAuth();
  const canEdit = canOpsWrite(profile?.role);

  const [page, setPage] = useState(1);
  const pageSize = 20;

  const providersState = useAsyncData(
    (signal) => adminChannelApi.listProviders(signal),
    [],
  );
  const channelsState = useAsyncData(
    (signal) => adminChannelApi.listChannels(page, pageSize, signal),
    [page],
  );

  const [providerFilter, setProviderFilter] = useState<string>("ALL");
  const [probingId, setProbingId] = useState<string | null>(null);
  const [probeMap, setProbeMap] = useState<Record<string, ChannelProbeDto>>({});

  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<ChannelDto | null>(null);
  const [form, setForm] = useState<ChannelFormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<unknown>(null);

  const [deleteTarget, setDeleteTarget] = useState<ChannelDto | null>(null);
  const [deleting, setDeleting] = useState(false);

  const providerMap = useMemo(() => {
    const map = new Map<string, ProviderDto>();
    for (const p of providersState.data ?? []) {
      map.set(p.id, p);
    }
    return map;
  }, [providersState.data]);

  const filtered = useMemo(() => {
    const items = channelsState.data?.items ?? [];
    if (providerFilter === "ALL") return items;
    return items.filter((c) => c.providerId === providerFilter);
  }, [channelsState.data, providerFilter]);

  function openCreate() {
    setEditing(null);
    setForm({
      ...EMPTY_FORM,
      providerId: providersState.data?.[0]?.id ?? "",
    });
    setFormError(null);
    setSheetOpen(true);
  }

  function openEdit(ch: ChannelDto) {
    setEditing(ch);
    setForm({
      providerId: ch.providerId,
      name: ch.name,
      region: ch.region ?? "",
      baseUrlOverride: ch.baseUrlOverride ?? "",
      apiKey: "",
      discountPercent: String(ch.defaultCostDiscountBps / 100),
      rpmLimit: ch.rpmLimit ? String(ch.rpmLimit) : "",
      tpmLimit: ch.tpmLimit ? String(ch.tpmLimit) : "",
      maxConcurrency: ch.maxConcurrency ? String(ch.maxConcurrency) : "",
      healthCheckModel: ch.healthCheckModel ?? "",
      status: ch.status,
    });
    setFormError(null);
    setSheetOpen(true);
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    const bps = Math.round((Number(form.discountPercent) || 100) * 100);
    if (bps <= 0) {
      toast.error("采购折扣须大于 0");
      return;
    }

    setSaving(true);
    try {
      if (editing) {
        await adminChannelApi.updateChannel(editing.id, {
          name: form.name.trim(),
          region: form.region.trim() || null,
          baseUrlOverride: form.baseUrlOverride.trim() || null,
          apiKey: form.apiKey.trim() || null,
          defaultCostDiscountBps: bps,
          rpmLimit: parsePosInt(form.rpmLimit),
          tpmLimit: parsePosInt(form.tpmLimit),
          maxConcurrency: parsePosInt(form.maxConcurrency),
          healthCheckModel: form.healthCheckModel.trim() || null,
          status: form.status,
          version: editing.version,
        });
        toast.success("渠道配置已更新");
      } else {
        await adminChannelApi.createChannel({
          providerId: form.providerId,
          name: form.name.trim(),
          region: form.region.trim() || null,
          baseUrlOverride: form.baseUrlOverride.trim() || null,
          apiKey: form.apiKey.trim(),
          defaultCostDiscountBps: bps,
          rpmLimit: parsePosInt(form.rpmLimit),
          tpmLimit: parsePosInt(form.tpmLimit),
          maxConcurrency: parsePosInt(form.maxConcurrency),
          healthCheckModel: form.healthCheckModel.trim() || null,
        });
        toast.success("渠道已新增");
      }
      setSheetOpen(false);
      channelsState.reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setSaving(false);
    }
  }

  async function handleProbe(channel: ChannelDto) {
    setProbingId(channel.id);
    try {
      const result = await adminChannelApi.probeChannel(channel.id);
      setProbeMap((prev) => ({ ...prev, [channel.id]: result }));
      if (result.healthy) {
        toast.success(
          `「${channel.name}」连通正常 (${formatLatencyMs(result.latencyMs)})`,
        );
      } else {
        toast.error(`「${channel.name}」探测异常：${result.message}`);
      }
    } catch {
      toast.error("探测请求失败");
    } finally {
      setProbingId(null);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await adminChannelApi.deleteChannel(deleteTarget.id, deleteTarget.version);
      toast.success(`已删除渠道「${deleteTarget.name}」`);
      setDeleteTarget(null);
      channelsState.reload();
    } catch {
      toast.error("删除失败，请检查是否仍被模型路由引用");
    } finally {
      setDeleting(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-1.5">
          <Button
            type="button"
            size="sm"
            variant={providerFilter === "ALL" ? "secondary" : "outline"}
            onClick={() => setProviderFilter("ALL")}
          >
            全部供应商
          </Button>
          {(providersState.data ?? []).map((p) => (
            <Button
              key={p.id}
              type="button"
              size="sm"
              variant={providerFilter === p.id ? "secondary" : "outline"}
              onClick={() => setProviderFilter(p.id)}
              className="gap-1.5"
            >
              <span>{p.displayName}</span>
              <span className="text-[10px] text-muted-foreground">
                {PROTOCOL_LABEL[p.protocolFamily]}
              </span>
            </Button>
          ))}
        </div>

        {canEdit ? (
          <Button size="sm" onClick={openCreate}>
            <Plus className="size-3.5" />
            <span>新增渠道</span>
          </Button>
        ) : null}
      </div>

      <ApiErrorAlert
        error={channelsState.error ?? providersState.error}
        onRetry={channelsState.reload}
      />

      <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        {channelsState.loading ? (
          <div className="flex flex-col gap-3 p-5">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-11 w-full" />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={<PlugZap className="size-4" />}
            title="暂无上游渠道"
            action={
              canEdit ? (
                <Button size="sm" variant="outline" onClick={openCreate}>
                  新增渠道
                </Button>
              ) : null
            }
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/35 hover:bg-muted/35">
                <TableHead>渠道名称</TableHead>
                <TableHead>供应商 / 区域</TableHead>
                <TableHead className="text-right">采购折扣</TableHead>
                <TableHead>容量上限</TableHead>
                <TableHead>状态与探针</TableHead>
                {canEdit ? <TableHead className="text-right">操作</TableHead> : null}
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((ch) => {
                const provider = providerMap.get(ch.providerId);
                const probe = probeMap[ch.id];
                return (
                  <TableRow key={ch.id}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{ch.name}</span>
                        {ch.hasCredentials ? (
                          <span title="上游凭据已加密存储">
                            <ShieldCheck className="size-3.5 text-emerald-600 dark:text-emerald-400" />
                          </span>
                        ) : null}
                      </div>
                      {ch.baseUrlOverride ? (
                        <div className="max-w-52 truncate font-mono text-[11px] text-muted-foreground">
                          {ch.baseUrlOverride}
                        </div>
                      ) : null}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-col gap-1">
                        <div className="flex items-center gap-1.5 text-xs">
                          <span>{provider?.displayName ?? "—"}</span>
                          {ch.region ? (
                            <Badge variant="outline" className="font-mono text-[10px]">
                              {ch.region}
                            </Badge>
                          ) : null}
                        </div>
                        {provider?.supportedProtocols?.length ? (
                          <div className="flex flex-wrap gap-1">
                            {provider.supportedProtocols.map((wp) => (
                              <Badge
                                key={wp}
                                variant="secondary"
                                className="h-4 px-1 font-mono text-[10px]"
                              >
                                {WIRE_PROTOCOL_LABEL[wp] ?? wp}
                              </Badge>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">
                      {formatBpsPercent(ch.defaultCostDiscountBps)}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1 font-mono text-[11px] text-muted-foreground tabular-nums">
                        {ch.rpmLimit ? (
                          <Badge variant="secondary">
                            {formatCompactNumber(ch.rpmLimit)} RPM
                          </Badge>
                        ) : null}
                        {ch.tpmLimit ? (
                          <Badge variant="secondary">
                            {formatCompactNumber(ch.tpmLimit)} TPM
                          </Badge>
                        ) : null}
                        {ch.maxConcurrency ? (
                          <Badge variant="secondary">
                            {ch.maxConcurrency} 并发
                          </Badge>
                        ) : null}
                        {!ch.rpmLimit && !ch.tpmLimit && !ch.maxConcurrency ? (
                          <span>不限</span>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-2">
                        <StatusDot
                          tone={CHANNEL_TONE[ch.status]}
                          label={CHANNEL_STATUS_LABEL[ch.status]}
                        />
                        {probe ? (
                          <Badge
                            variant="outline"
                            className={cn(
                              "h-5 font-mono text-[10px] tabular-nums",
                              probe.healthy
                                ? "border-emerald-500/40 text-emerald-700 dark:text-emerald-400"
                                : "border-rose-500/40 text-rose-700 dark:text-rose-400",
                            )}
                          >
                            {probe.healthy
                              ? `正常 · ${formatLatencyMs(probe.latencyMs)}`
                              : "探测异常"}
                          </Badge>
                        ) : null}
                      </div>
                    </TableCell>
                    {canEdit ? (
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-1">
                          <Button
                            size="xs"
                            variant="ghost"
                            disabled={probingId === ch.id}
                            onClick={() => handleProbe(ch)}
                          >
                            {probingId === ch.id ? (
                              <Loader2 className="size-3 animate-spin motion-reduce:animate-none" />
                            ) : (
                              <Activity className="size-3" />
                            )}
                            <span>探测</span>
                          </Button>
                          <Button
                            size="icon-xs"
                            variant="ghost"
                            aria-label="编辑渠道"
                            onClick={() => openEdit(ch)}
                          >
                            <Pencil className="size-3.5" />
                          </Button>
                          <Button
                            size="icon-xs"
                            variant="ghost"
                            aria-label="删除渠道"
                            className="text-destructive hover:text-destructive"
                            onClick={() => setDeleteTarget(ch)}
                          >
                            <Trash2 className="size-3.5" />
                          </Button>
                        </div>
                      </TableCell>
                    ) : null}
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </div>

      {(channelsState.data?.total ?? 0) > pageSize ? (
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <span className="tabular-nums">
            第 {page} / {Math.max(1, Math.ceil((channelsState.data?.total ?? 0) / pageSize))} 页 · 共 {channelsState.data?.total ?? 0} 条渠道
          </span>
          <div className="flex items-center gap-1.5">
            <Button
              size="xs"
              variant="outline"
              disabled={page <= 1}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
            >
              上一页
            </Button>
            <Button
              size="xs"
              variant="outline"
              disabled={page * pageSize >= (channelsState.data?.total ?? 0)}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      ) : null}

      {/* Create / Edit Channel Dialog */}
      <Dialog open={sheetOpen} onOpenChange={setSheetOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editing ? "编辑渠道" : "新增上游渠道"}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <ApiErrorAlert error={formError} />

            {!editing ? (
              <div className="flex flex-col gap-1.5">
                <Label>供应商</Label>
                <Select
                  value={form.providerId}
                  onValueChange={(v) =>
                    setForm({ ...form, providerId: v ?? "" })
                  }
                >
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="选择供应商" />
                  </SelectTrigger>
                  <SelectContent>
                    {(providersState.data ?? []).map((p) => (
                      <SelectItem key={p.id} value={p.id}>
                        {p.displayName} ({PROTOCOL_LABEL[p.protocolFamily]})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            ) : null}

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="ch-name">渠道名称</Label>
                <Input
                  id="ch-name"
                  required
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="openai-us-main"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="ch-region">区域</Label>
                <Input
                  id="ch-region"
                  value={form.region}
                  onChange={(e) => setForm({ ...form, region: e.target.value })}
                  placeholder="us-east-1"
                  className="font-mono text-xs"
                />
              </div>
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="ch-key">
                上游 API Key{editing ? "（留空保持原密钥）" : ""}
              </Label>
              <Input
                id="ch-key"
                type="password"
                required={!editing}
                value={form.apiKey}
                onChange={(e) => setForm({ ...form, apiKey: e.target.value })}
                placeholder={editing ? "••••••••••••" : "sk-..."}
                className="font-mono text-xs"
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="ch-url">自定义 Base URL（可选）</Label>
              <Input
                id="ch-url"
                value={form.baseUrlOverride}
                onChange={(e) =>
                  setForm({ ...form, baseUrlOverride: e.target.value })
                }
                placeholder="留空使用供应商默认地址"
                className="font-mono text-xs"
              />
            </div>

            <Separator />

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="ch-discount">采购折扣</Label>
                <div className="relative">
                  <Input
                    id="ch-discount"
                    required
                    inputMode="decimal"
                    value={form.discountPercent}
                    onChange={(e) =>
                      setForm({ ...form, discountPercent: e.target.value })
                    }
                    className="pr-8 font-mono tabular-nums"
                  />
                  <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                    %
                  </span>
                </div>
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="ch-hc">探针模型</Label>
                <Input
                  id="ch-hc"
                  value={form.healthCheckModel}
                  onChange={(e) =>
                    setForm({ ...form, healthCheckModel: e.target.value })
                  }
                  placeholder="gpt-4o-mini"
                  className="font-mono text-xs"
                />
              </div>
            </div>

            <div className="grid grid-cols-3 gap-2.5">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="ch-rpm">RPM</Label>
                <Input
                  id="ch-rpm"
                  inputMode="numeric"
                  value={form.rpmLimit}
                  onChange={(e) =>
                    setForm({ ...form, rpmLimit: e.target.value })
                  }
                  placeholder="不限"
                  className="font-mono tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="ch-tpm">TPM</Label>
                <Input
                  id="ch-tpm"
                  inputMode="numeric"
                  value={form.tpmLimit}
                  onChange={(e) =>
                    setForm({ ...form, tpmLimit: e.target.value })
                  }
                  placeholder="不限"
                  className="font-mono tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="ch-conc">并发</Label>
                <Input
                  id="ch-conc"
                  inputMode="numeric"
                  value={form.maxConcurrency}
                  onChange={(e) =>
                    setForm({ ...form, maxConcurrency: e.target.value })
                  }
                  placeholder="不限"
                  className="font-mono tabular-nums"
                />
              </div>
            </div>

            {editing ? (
              <div className="flex flex-col gap-1.5">
                <Label>状态</Label>
                <Select
                  value={form.status}
                  onValueChange={(v) =>
                    setForm({
                      ...form,
                      status: (v as ChannelStatus) ?? "ACTIVE",
                    })
                  }
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ACTIVE">运行中</SelectItem>
                    <SelectItem value="DISABLED">已停用</SelectItem>
                    <SelectItem value="CIRCUIT_OPEN">熔断隔离</SelectItem>
                    <SelectItem value="EXHAUSTED">额度耗尽</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            ) : null}

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setSheetOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={saving}>
                {saving ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>{editing ? "保存渠道" : "创建渠道"}</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <AlertDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除渠道「{deleteTarget?.name}」？</AlertDialogTitle>
            <AlertDialogDescription>
              删除前请确认该渠道已从所有虚拟模型路由中解绑。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={deleting}
              onClick={handleDelete}
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
