"use client";

import { Suspense, useMemo, useState, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import {
  AlertTriangle,
  Globe,
  KeyRound,
  Layers,
  Loader2,
  Pencil,
  Plus,
  Trash2,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
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
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { CopyButton } from "@/components/common/copy-button";
import { EmptyState } from "@/components/common/empty-state";
import { StatusDot } from "@/components/common/status-dot";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { apiKeyApi, projectApi } from "@/lib/api/endpoints";
import type { ApiKeyDto, ApiKeyStatus } from "@/lib/api/types";
import {
  formatCompactNumber,
  formatDateTime,
  formatMicroUsd,
  MICROS_PER_USD,
  parseUsdToMicros,
} from "@/lib/format/micro-usd";
import { API_KEY_STATUS_LABEL } from "@/lib/format/labels";

interface KeyFormState {
  name: string;
  quotaUsd: string;
  rpmLimit: string;
  tpmLimit: string;
  maxConcurrency: string;
  allowedModelsCsv: string;
  ipWhitelistCsv: string;
  expiresAtLocal: string;
}

const EMPTY_FORM: KeyFormState = {
  name: "",
  quotaUsd: "",
  rpmLimit: "",
  tpmLimit: "",
  maxConcurrency: "",
  allowedModelsCsv: "",
  ipWhitelistCsv: "",
  expiresAtLocal: "",
};

function parsePositiveInt(raw: string): number | null {
  const t = raw.trim();
  if (!t) return null;
  const n = Number(t);
  return Number.isInteger(n) && n > 0 ? n : null;
}

function parseCsv(raw: string): string[] | null {
  const arr = raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  return arr.length > 0 ? arr : null;
}

function ApiKeysContent() {
  const searchParams = useSearchParams();
  const initialProjectId = searchParams.get("projectId") ?? "";
  const { currentOrg } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;

  const projectsState = useAsyncData(
    (signal) => projectApi.list(orgId!, 1, 50, signal),
    [orgId],
    Boolean(orgId),
  );

  const [selectedProjectId, setSelectedProjectId] = useState(initialProjectId);
  const [statusFilter, setStatusFilter] = useState<"ALL" | ApiKeyStatus>("ALL");

  const activeProjectId = useMemo(() => {
    const items = projectsState.data?.items ?? [];
    if (selectedProjectId && items.some((p) => p.id === selectedProjectId)) {
      return selectedProjectId;
    }
    return items[0]?.id ?? "";
  }, [projectsState.data, selectedProjectId]);

  const keysState = useAsyncData(
    (signal) => apiKeyApi.list(activeProjectId, 1, 50, signal),
    [activeProjectId],
    Boolean(activeProjectId),
  );

  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<ApiKeyDto | null>(null);
  const [form, setForm] = useState<KeyFormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<unknown>(null);

  const [createdRawKey, setCreatedRawKey] = useState<string | null>(null);
  const [revokeTarget, setRevokeTarget] = useState<ApiKeyDto | null>(null);
  const [revoking, setRevoking] = useState(false);

  const filtered = useMemo(() => {
    const items = keysState.data?.items ?? [];
    if (statusFilter === "ALL") return items;
    return items.filter((k) => k.status === statusFilter);
  }, [keysState.data, statusFilter]);

  function openCreate() {
    setEditing(null);
    setForm(EMPTY_FORM);
    setFormError(null);
    setSheetOpen(true);
  }

  function openEdit(k: ApiKeyDto) {
    setEditing(k);
    setForm({
      name: k.name,
      quotaUsd:
        k.quotaLimitMicros !== null
          ? String(k.quotaLimitMicros / MICROS_PER_USD)
          : "",
      rpmLimit: k.rpmLimit ? String(k.rpmLimit) : "",
      tpmLimit: k.tpmLimit ? String(k.tpmLimit) : "",
      maxConcurrency: k.maxConcurrency ? String(k.maxConcurrency) : "",
      allowedModelsCsv: (k.allowedModels ?? []).join(", "),
      ipWhitelistCsv: (k.ipWhitelist ?? []).join(", "),
      expiresAtLocal: k.expiresAt ? k.expiresAt.slice(0, 16) : "",
    });
    setFormError(null);
    setSheetOpen(true);
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!activeProjectId) return;
    setFormError(null);

    const quotaLimitMicros = form.quotaUsd.trim()
      ? parseUsdToMicros(form.quotaUsd)
      : null;
    if (form.quotaUsd.trim() && quotaLimitMicros === null) {
      toast.error("累计配额格式无效");
      return;
    }

    const expiresAt = form.expiresAtLocal
      ? new Date(form.expiresAtLocal).toISOString()
      : null;

    setSaving(true);
    try {
      if (editing) {
        await apiKeyApi.update(editing.id, {
          name: form.name.trim(),
          allowedModels: parseCsv(form.allowedModelsCsv),
          ipWhitelist: parseCsv(form.ipWhitelistCsv),
          rpmLimit: parsePositiveInt(form.rpmLimit),
          tpmLimit: parsePositiveInt(form.tpmLimit),
          maxConcurrency: parsePositiveInt(form.maxConcurrency),
          quotaLimitMicros,
          expiresAt,
          status: editing.status,
          version: editing.version,
        });
        setSheetOpen(false);
        toast.success("密钥配置已更新");
      } else {
        const res = await apiKeyApi.create(activeProjectId, {
          name: form.name.trim(),
          allowedModels: parseCsv(form.allowedModelsCsv),
          ipWhitelist: parseCsv(form.ipWhitelistCsv),
          rpmLimit: parsePositiveInt(form.rpmLimit),
          tpmLimit: parsePositiveInt(form.tpmLimit),
          maxConcurrency: parsePositiveInt(form.maxConcurrency),
          quotaLimitMicros,
          expiresAt,
        });
        setSheetOpen(false);
        setCreatedRawKey(res.rawKey);
      }
      keysState.reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setSaving(false);
    }
  }

  async function handleToggleStatus(key: ApiKeyDto) {
    if (key.status === "REVOKED") return;
    const nextStatus: ApiKeyStatus =
      key.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
    try {
      await apiKeyApi.update(key.id, {
        name: key.name,
        allowedModels: key.allowedModels,
        ipWhitelist: key.ipWhitelist,
        rpmLimit: key.rpmLimit,
        tpmLimit: key.tpmLimit,
        maxConcurrency: key.maxConcurrency,
        quotaLimitMicros: key.quotaLimitMicros,
        expiresAt: key.expiresAt,
        status: nextStatus,
        version: key.version,
      });
      toast.success(nextStatus === "ACTIVE" ? "已启用密钥" : "已停用密钥");
      keysState.reload();
    } catch {
      toast.error("状态更新失败，请刷新重试");
    }
  }

  async function handleRevoke() {
    if (!revokeTarget) return;
    setRevoking(true);
    try {
      await apiKeyApi.revoke(revokeTarget.id, revokeTarget.version);
      toast.success(`已吊销「${revokeTarget.name}」`);
      setRevokeTarget(null);
      keysState.reload();
    } catch {
      toast.error("吊销失败，请刷新重试");
    } finally {
      setRevoking(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-lg font-semibold tracking-tight">API 密钥</h1>
          <Select
            value={activeProjectId}
            onValueChange={(v) => setSelectedProjectId(v ?? "")}
          >
            <SelectTrigger className="w-48">
              <SelectValue placeholder="选择项目" />
            </SelectTrigger>
            <SelectContent>
              {(projectsState.data?.items ?? []).map((p) => (
                <SelectItem key={p.id} value={p.id}>
                  {p.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Tabs
            value={statusFilter}
            onValueChange={(v) => setStatusFilter(v as "ALL" | ApiKeyStatus)}
          >
            <TabsList>
              <TabsTrigger value="ALL">全部</TabsTrigger>
              <TabsTrigger value="ACTIVE">启用中</TabsTrigger>
              <TabsTrigger value="DISABLED">已停用</TabsTrigger>
              <TabsTrigger value="REVOKED">已吊销</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        <Button size="sm" disabled={!activeProjectId} onClick={openCreate}>
          <Plus className="size-3.5" />
          <span>创建密钥</span>
        </Button>
      </div>

      <ApiErrorAlert error={keysState.error} onRetry={keysState.reload} />

      <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        {keysState.loading || projectsState.loading ? (
          <div className="flex flex-col gap-3 p-5">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-11 w-full" />
            ))}
          </div>
        ) : !activeProjectId ? (
          <EmptyState
            icon={<KeyRound className="size-4" />}
            title="请先创建项目后再签发密钥"
          />
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={<KeyRound className="size-4" />}
            title="当前筛选下暂无 API 密钥"
            action={
              <Button size="sm" variant="outline" onClick={openCreate}>
                创建密钥
              </Button>
            }
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/35 hover:bg-muted/35">
                <TableHead>名称与前缀</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>配额与限流</TableHead>
                <TableHead>安全策略</TableHead>
                <TableHead>最近使用 / 到期</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((key) => {
                const revoked = key.status === "REVOKED";
                return (
                  <TableRow key={key.id} className={revoked ? "opacity-60" : undefined}>
                    <TableCell>
                      <div className="flex flex-col gap-0.5">
                        <span className="font-medium">{key.name}</span>
                        <code className="font-mono text-xs text-muted-foreground">
                          {key.keyPrefix}••••••••
                        </code>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2.5">
                        {!revoked ? (
                          <Switch
                            checked={key.status === "ACTIVE"}
                            onCheckedChange={() => handleToggleStatus(key)}
                            aria-label="启停密钥"
                          />
                        ) : null}
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
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-1.5 font-mono text-[11px] tabular-nums">
                        {key.quotaLimitMicros !== null ? (
                          <Badge variant="outline">
                            限额 {formatMicroUsd(key.quotaLimitMicros)}
                          </Badge>
                        ) : null}
                        {key.rpmLimit ? (
                          <Badge variant="secondary">
                            {formatCompactNumber(key.rpmLimit)} RPM
                          </Badge>
                        ) : null}
                        {key.tpmLimit ? (
                          <Badge variant="secondary">
                            {formatCompactNumber(key.tpmLimit)} TPM
                          </Badge>
                        ) : null}
                        {key.maxConcurrency ? (
                          <Badge variant="secondary">
                            {key.maxConcurrency} 并发
                          </Badge>
                        ) : null}
                        {key.quotaLimitMicros === null &&
                        !key.rpmLimit &&
                        !key.tpmLimit &&
                        !key.maxConcurrency ? (
                          <span className="text-xs text-muted-foreground">
                            继承项目配置
                          </span>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2 text-xs text-muted-foreground">
                        {key.allowedModels && key.allowedModels.length > 0 ? (
                          <Tooltip>
                            <TooltipTrigger
                              render={
                                <span className="inline-flex items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 tabular-nums" />
                              }
                            >
                              <Layers className="size-3" />
                              {key.allowedModels.length}
                            </TooltipTrigger>
                            <TooltipContent>
                              {key.allowedModels.join(", ")}
                            </TooltipContent>
                          </Tooltip>
                        ) : null}
                        {key.ipWhitelist && key.ipWhitelist.length > 0 ? (
                          <Tooltip>
                            <TooltipTrigger
                              render={
                                <span className="inline-flex items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 tabular-nums" />
                              }
                            >
                              <Globe className="size-3" />
                              {key.ipWhitelist.length}
                            </TooltipTrigger>
                            <TooltipContent>
                              {key.ipWhitelist.join(", ")}
                            </TooltipContent>
                          </Tooltip>
                        ) : null}
                        {(!key.allowedModels || key.allowedModels.length === 0) &&
                        (!key.ipWhitelist || key.ipWhitelist.length === 0) ? (
                          <span>不限</span>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground tabular-nums">
                      <div>使用：{formatDateTime(key.lastUsedAt)}</div>
                      <div>到期：{key.expiresAt ? formatDateTime(key.expiresAt) : "永久"}</div>
                    </TableCell>
                    <TableCell className="text-right">
                      {!revoked ? (
                        <div className="flex items-center justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="icon-xs"
                            aria-label="编辑密钥"
                            onClick={() => openEdit(key)}
                          >
                            <Pencil className="size-3.5" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon-xs"
                            aria-label="吊销密钥"
                            className="text-destructive hover:text-destructive"
                            onClick={() => setRevokeTarget(key)}
                          >
                            <Trash2 className="size-3.5" />
                          </Button>
                        </div>
                      ) : null}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </div>

      {/* Create / Edit Key Sheet */}
      <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle>{editing ? "编辑密钥" : "创建 API 密钥"}</SheetTitle>
          </SheetHeader>
          <form
            onSubmit={handleSubmit}
            className="flex flex-1 flex-col gap-5 px-6 pb-6"
          >
            <ApiErrorAlert error={formError} />

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="key-name">密钥名称</Label>
              <Input
                id="key-name"
                required
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="prod-backend-worker"
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="key-quota">累计消费上限</Label>
                <div className="relative">
                  <Input
                    id="key-quota"
                    inputMode="decimal"
                    value={form.quotaUsd}
                    onChange={(e) =>
                      setForm({ ...form, quotaUsd: e.target.value })
                    }
                    placeholder="不限"
                    className="pr-11 font-mono tabular-nums"
                  />
                  <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                    USD
                  </span>
                </div>
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="key-exp">过期时间</Label>
                <Input
                  id="key-exp"
                  type="datetime-local"
                  value={form.expiresAtLocal}
                  onChange={(e) =>
                    setForm({ ...form, expiresAtLocal: e.target.value })
                  }
                  className="text-xs tabular-nums"
                />
              </div>
            </div>

            <Separator />

            <div className="grid grid-cols-3 gap-2.5">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="key-rpm">RPM</Label>
                <Input
                  id="key-rpm"
                  inputMode="numeric"
                  value={form.rpmLimit}
                  onChange={(e) =>
                    setForm({ ...form, rpmLimit: e.target.value })
                  }
                  placeholder="默认"
                  className="font-mono tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="key-tpm">TPM</Label>
                <Input
                  id="key-tpm"
                  inputMode="numeric"
                  value={form.tpmLimit}
                  onChange={(e) =>
                    setForm({ ...form, tpmLimit: e.target.value })
                  }
                  placeholder="默认"
                  className="font-mono tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="key-conc">并发</Label>
                <Input
                  id="key-conc"
                  inputMode="numeric"
                  value={form.maxConcurrency}
                  onChange={(e) =>
                    setForm({ ...form, maxConcurrency: e.target.value })
                  }
                  placeholder="默认"
                  className="font-mono tabular-nums"
                />
              </div>
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="key-models">授权模型子集（逗号分隔）</Label>
              <Input
                id="key-models"
                value={form.allowedModelsCsv}
                onChange={(e) =>
                  setForm({ ...form, allowedModelsCsv: e.target.value })
                }
                placeholder="留空继承项目权限"
                className="font-mono text-xs"
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="key-ips">IP 白名单（逗号分隔）</Label>
              <Input
                id="key-ips"
                value={form.ipWhitelistCsv}
                onChange={(e) =>
                  setForm({ ...form, ipWhitelistCsv: e.target.value })
                }
                placeholder="203.0.113.10, 198.51.100.0/24"
                className="font-mono text-xs"
              />
            </div>

            <SheetFooter className="px-0 pt-2">
              <Button type="submit" disabled={saving} className="w-full">
                {saving ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>{editing ? "保存修改" : "签发密钥"}</span>
              </Button>
            </SheetFooter>
          </form>
        </SheetContent>
      </Sheet>

      {/* One-Time Raw Key Reveal Dialog */}
      <Dialog
        open={Boolean(createdRawKey)}
        onOpenChange={(open) => {
          if (!open) setCreatedRawKey(null);
        }}
      >
        <DialogContent showCloseButton={false} className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="size-4 text-amber-500" />
              <span>请立即保存密钥</span>
            </DialogTitle>
          </DialogHeader>
          <p className="text-xs text-muted-foreground">
            完整密钥仅在此时展示一次，关闭弹窗后无法再次查看。
          </p>
          <div className="flex items-center justify-between gap-2 rounded-lg border border-border bg-muted/45 px-3 py-2.5">
            <code className="break-all font-mono text-xs">
              {createdRawKey}
            </code>
            {createdRawKey ? (
              <CopyButton
                value={createdRawKey}
                label="复制"
                variant="outline"
                copiedToast="已复制 API 密钥"
              />
            ) : null}
          </div>
          <DialogFooter>
            <Button onClick={() => setCreatedRawKey(null)}>我已保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Revoke Confirmation */}
      <AlertDialog
        open={Boolean(revokeTarget)}
        onOpenChange={(open) => {
          if (!open) setRevokeTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>吊销密钥「{revokeTarget?.name}」？</AlertDialogTitle>
            <AlertDialogDescription>
              吊销后立即生效且不可恢复，使用该密钥的服务将收到认证拒绝。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={revoking}
              onClick={handleRevoke}
            >
              {revoking ? (
                <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
              ) : null}
              <span>确认吊销</span>
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

export default function ApiKeysPage() {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-4">
          <Skeleton className="h-8 w-48" />
          <Skeleton className="h-64 w-full" />
        </div>
      }
    >
      <ApiKeysContent />
    </Suspense>
  );
}
