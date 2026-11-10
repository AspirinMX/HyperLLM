"use client";

import { useMemo, useState, type FormEvent } from "react";
import Link from "next/link";
import {
  ArrowRight,
  FolderKanban,
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
  Sheet,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
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
import { ProportionBar } from "@/components/common/proportion-bar";
import { StatusDot } from "@/components/common/status-dot";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { projectApi } from "@/lib/api/endpoints";
import type {
  ProjectBudgetDto,
  ProjectDto,
  ProjectEnvironment,
  ProjectStatus,
} from "@/lib/api/types";
import {
  formatCompactNumber,
  formatMicroUsd,
  MICROS_PER_USD,
  parseUsdToMicros,
} from "@/lib/format/micro-usd";
import {
  canManageOrg,
  PROJECT_ENV_LABEL,
  PROJECT_STATUS_LABEL,
} from "@/lib/format/labels";

interface ProjectRowItem {
  project: ProjectDto;
  budget: ProjectBudgetDto | null;
}

interface ProjectFormState {
  name: string;
  environment: ProjectEnvironment;
  hardCapUsd: string;
  softCapUsd: string;
  rpmLimit: string;
  tpmLimit: string;
  maxConcurrency: string;
  allowedModelsCsv: string;
  status: ProjectStatus;
}

const EMPTY_FORM: ProjectFormState = {
  name: "",
  environment: "PRODUCTION",
  hardCapUsd: "",
  softCapUsd: "",
  rpmLimit: "",
  tpmLimit: "",
  maxConcurrency: "",
  allowedModelsCsv: "",
  status: "ACTIVE",
};

function microsToInputUsd(micros: number | null): string {
  if (micros === null || micros === undefined) return "";
  return String(micros / MICROS_PER_USD);
}

function parsePositiveInt(raw: string): number | null {
  const t = raw.trim();
  if (!t) return null;
  const n = Number(t);
  if (!Number.isInteger(n) || n <= 0) return null;
  return n;
}

export default function ProjectsPage() {
  const { currentOrg } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;
  const canEdit = canManageOrg(currentOrg?.role);

  const [envFilter, setEnvFilter] = useState<"ALL" | ProjectEnvironment>("ALL");
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editing, setEditing] = useState<ProjectDto | null>(null);
  const [form, setForm] = useState<ProjectFormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<unknown>(null);

  const [deleteTarget, setDeleteTarget] = useState<ProjectDto | null>(null);
  const [deleting, setDeleting] = useState(false);

  const state = useAsyncData(
    async (signal): Promise<ProjectRowItem[]> => {
      const res = await projectApi.list(orgId!, 1, 50, signal);
      const budgets = await Promise.all(
        res.items.map((p) =>
          projectApi.getBudget(p.id, signal).catch(() => null),
        ),
      );
      return res.items.map((project, i) => ({ project, budget: budgets[i] }));
    },
    [orgId],
    Boolean(orgId),
  );

  const filtered = useMemo(() => {
    const items = state.data ?? [];
    if (envFilter === "ALL") return items;
    return items.filter((item) => item.project.environment === envFilter);
  }, [state.data, envFilter]);

  function openCreate() {
    setEditing(null);
    setForm(EMPTY_FORM);
    setFormError(null);
    setSheetOpen(true);
  }

  function openEdit(p: ProjectDto) {
    setEditing(p);
    setForm({
      name: p.name,
      environment: p.environment,
      hardCapUsd: microsToInputUsd(p.monthlyHardCapMicros),
      softCapUsd: microsToInputUsd(p.monthlySoftCapMicros),
      rpmLimit: p.rpmLimit ? String(p.rpmLimit) : "",
      tpmLimit: p.tpmLimit ? String(p.tpmLimit) : "",
      maxConcurrency: p.maxConcurrency ? String(p.maxConcurrency) : "",
      allowedModelsCsv: (p.allowedModels ?? []).join(", "),
      status: p.status,
    });
    setFormError(null);
    setSheetOpen(true);
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!orgId) return;
    setFormError(null);

    const hardCapMicros = form.hardCapUsd.trim()
      ? parseUsdToMicros(form.hardCapUsd)
      : null;
    const softCapMicros = form.softCapUsd.trim()
      ? parseUsdToMicros(form.softCapUsd)
      : null;

    if (form.hardCapUsd.trim() && hardCapMicros === null) {
      toast.error("月度硬上限金额格式无效");
      return;
    }
    if (form.softCapUsd.trim() && softCapMicros === null) {
      toast.error("月度软上限金额格式无效");
      return;
    }

    const allowedModels = form.allowedModelsCsv
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);

    setSaving(true);
    try {
      if (editing) {
        await projectApi.update(editing.id, {
          name: form.name.trim(),
          monthlyHardCapMicros: hardCapMicros,
          monthlySoftCapMicros: softCapMicros,
          allowedModels: allowedModels.length > 0 ? allowedModels : null,
          rpmLimit: parsePositiveInt(form.rpmLimit),
          tpmLimit: parsePositiveInt(form.tpmLimit),
          maxConcurrency: parsePositiveInt(form.maxConcurrency),
          status: form.status,
          version: editing.version,
        });
        toast.success("项目配置已更新");
      } else {
        await projectApi.create(orgId, {
          name: form.name.trim(),
          environment: form.environment,
          monthlyHardCapMicros: hardCapMicros,
          monthlySoftCapMicros: softCapMicros,
          allowedModels: allowedModels.length > 0 ? allowedModels : null,
          rpmLimit: parsePositiveInt(form.rpmLimit),
          tpmLimit: parsePositiveInt(form.tpmLimit),
          maxConcurrency: parsePositiveInt(form.maxConcurrency),
        });
        toast.success("项目已创建");
      }
      setSheetOpen(false);
      state.reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await projectApi.remove(deleteTarget.id, deleteTarget.version);
      toast.success(`已删除「${deleteTarget.name}」`);
      setDeleteTarget(null);
      state.reload();
    } catch (err) {
      toast.error("删除失败，请刷新后重试");
      setFormError(err);
    } finally {
      setDeleting(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <h1 className="text-lg font-semibold tracking-tight">项目</h1>
          <Tabs
            value={envFilter}
            onValueChange={(v) => setEnvFilter(v as "ALL" | ProjectEnvironment)}
          >
            <TabsList>
              <TabsTrigger value="ALL">全部</TabsTrigger>
              <TabsTrigger value="PRODUCTION">生产</TabsTrigger>
              <TabsTrigger value="STAGING">预发</TabsTrigger>
              <TabsTrigger value="DEVELOPMENT">开发</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>

        {canEdit ? (
          <Button size="sm" onClick={openCreate}>
            <Plus className="size-3.5" />
            <span>新建项目</span>
          </Button>
        ) : null}
      </div>

      <ApiErrorAlert error={state.error} onRetry={state.reload} />

      <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        {state.loading ? (
          <div className="flex flex-col gap-3 p-5">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-11 w-full" />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={<FolderKanban className="size-4" />}
            title="暂无项目"
            action={
              canEdit ? (
                <Button size="sm" variant="outline" onClick={openCreate}>
                  新建项目
                </Button>
              ) : null
            }
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/35 hover:bg-muted/35">
                <TableHead>项目名称</TableHead>
                <TableHead>环境</TableHead>
                <TableHead className="w-64">当月预算水位</TableHead>
                <TableHead>速率限制</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map(({ project, budget }) => {
                const cap = budget?.hardCapMicros ?? project.monthlyHardCapMicros;
                const soft = budget?.softCapMicros ?? project.monthlySoftCapMicros;
                const used = budget?.usedMicros ?? 0;
                const held = budget?.heldMicros ?? 0;
                const softRatio =
                  cap && cap > 0 && soft && soft > 0 ? soft / cap : null;

                return (
                  <TableRow key={project.id}>
                    <TableCell>
                      <Link
                        href={`/projects/${project.id}`}
                        className="font-medium hover:underline"
                      >
                        {project.name}
                      </Link>
                      {project.allowedModels && project.allowedModels.length > 0 ? (
                        <div className="text-[11px] text-muted-foreground">
                          限定 {project.allowedModels.length} 个模型
                        </div>
                      ) : null}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">
                        {PROJECT_ENV_LABEL[project.environment]}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-col gap-1.5">
                        <div className="flex items-center justify-between text-xs tabular-nums">
                          <span>{formatMicroUsd(used + held)}</span>
                          <span className="text-muted-foreground">
                            {cap ? `/ ${formatMicroUsd(cap)}` : "无硬上限"}
                          </span>
                        </div>
                        {cap && cap > 0 ? (
                          <ProportionBar
                            total={cap}
                            heightClass="h-1.5"
                            markerRatio={softRatio}
                            markerLabel={
                              soft
                                ? `软预算 ${formatMicroUsd(soft)}`
                                : undefined
                            }
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
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-1.5 font-mono text-[11px] text-muted-foreground tabular-nums">
                        {project.rpmLimit ? (
                          <Badge variant="secondary">
                            {formatCompactNumber(project.rpmLimit)} RPM
                          </Badge>
                        ) : null}
                        {project.tpmLimit ? (
                          <Badge variant="secondary">
                            {formatCompactNumber(project.tpmLimit)} TPM
                          </Badge>
                        ) : null}
                        {project.maxConcurrency ? (
                          <Badge variant="secondary">
                            {project.maxConcurrency} 并发
                          </Badge>
                        ) : null}
                        {!project.rpmLimit &&
                        !project.tpmLimit &&
                        !project.maxConcurrency ? (
                          <span>默认</span>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusDot
                        tone={
                          project.status === "ACTIVE"
                            ? "success"
                            : project.status === "PAUSED"
                              ? "warning"
                              : "muted"
                        }
                        label={PROJECT_STATUS_LABEL[project.status]}
                      />
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="xs"
                          render={<Link href={`/projects/${project.id}`} />}
                        >
                          <span>详情</span>
                          <ArrowRight className="size-3" />
                        </Button>
                        {canEdit ? (
                          <>
                            <Button
                              variant="ghost"
                              size="icon-xs"
                              aria-label="编辑项目"
                              onClick={() => openEdit(project)}
                            >
                              <Pencil className="size-3.5" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon-xs"
                              aria-label="删除项目"
                              className="text-destructive hover:text-destructive"
                              onClick={() => setDeleteTarget(project)}
                            >
                              <Trash2 className="size-3.5" />
                            </Button>
                          </>
                        ) : null}
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </div>

      {/* Create / Edit Project Sheet */}
      <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle>{editing ? "编辑项目" : "新建项目"}</SheetTitle>
          </SheetHeader>

          <form
            onSubmit={handleSubmit}
            className="flex flex-1 flex-col gap-5 px-6 pb-6"
          >
            <ApiErrorAlert error={formError} />

            <div className="flex flex-col gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="proj-name">项目名称</Label>
                <Input
                  id="proj-name"
                  required
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="prod-chatbot"
                />
              </div>

              {!editing ? (
                <div className="flex flex-col gap-1.5">
                  <Label>环境</Label>
                  <Select
                    value={form.environment}
                    onValueChange={(v) =>
                      setForm({
                        ...form,
                        environment: (v as ProjectEnvironment) ?? "PRODUCTION",
                      })
                    }
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="PRODUCTION">生产</SelectItem>
                      <SelectItem value="STAGING">预发</SelectItem>
                      <SelectItem value="DEVELOPMENT">开发</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              ) : (
                <div className="flex flex-col gap-1.5">
                  <Label>状态</Label>
                  <Select
                    value={form.status}
                    onValueChange={(v) =>
                      setForm({
                        ...form,
                        status: (v as ProjectStatus) ?? "ACTIVE",
                      })
                    }
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ACTIVE">运行中</SelectItem>
                      <SelectItem value="PAUSED">已暂停</SelectItem>
                      <SelectItem value="ARCHIVED">已归档</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              )}
            </div>

            <Separator />

            <div className="flex flex-col gap-3">
              <span className="text-xs font-medium text-muted-foreground">
                月度预算（留空不限）
              </span>
              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="proj-soft">软预警上限</Label>
                  <div className="relative">
                    <Input
                      id="proj-soft"
                      inputMode="decimal"
                      value={form.softCapUsd}
                      onChange={(e) =>
                        setForm({ ...form, softCapUsd: e.target.value })
                      }
                      placeholder="80.00"
                      className="pr-11 font-mono tabular-nums"
                    />
                    <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                      USD
                    </span>
                  </div>
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="proj-hard">硬拦截上限</Label>
                  <div className="relative">
                    <Input
                      id="proj-hard"
                      inputMode="decimal"
                      value={form.hardCapUsd}
                      onChange={(e) =>
                        setForm({ ...form, hardCapUsd: e.target.value })
                      }
                      placeholder="100.00"
                      className="pr-11 font-mono tabular-nums"
                    />
                    <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                      USD
                    </span>
                  </div>
                </div>
              </div>
            </div>

            <Separator />

            <div className="flex flex-col gap-3">
              <span className="text-xs font-medium text-muted-foreground">
                速率与访问控制
              </span>
              <div className="grid grid-cols-3 gap-2.5">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="proj-rpm">RPM</Label>
                  <Input
                    id="proj-rpm"
                    inputMode="numeric"
                    value={form.rpmLimit}
                    onChange={(e) =>
                      setForm({ ...form, rpmLimit: e.target.value })
                    }
                    placeholder="600"
                    className="font-mono tabular-nums"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="proj-tpm">TPM</Label>
                  <Input
                    id="proj-tpm"
                    inputMode="numeric"
                    value={form.tpmLimit}
                    onChange={(e) =>
                      setForm({ ...form, tpmLimit: e.target.value })
                    }
                    placeholder="500000"
                    className="font-mono tabular-nums"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="proj-conc">并发</Label>
                  <Input
                    id="proj-conc"
                    inputMode="numeric"
                    value={form.maxConcurrency}
                    onChange={(e) =>
                      setForm({ ...form, maxConcurrency: e.target.value })
                    }
                    placeholder="20"
                    className="font-mono tabular-nums"
                  />
                </div>
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="proj-models">允许模型（逗号分隔，留空全开放）</Label>
                <Input
                  id="proj-models"
                  value={form.allowedModelsCsv}
                  onChange={(e) =>
                    setForm({ ...form, allowedModelsCsv: e.target.value })
                  }
                  placeholder="gpt-4o, claude-sonnet-4-5"
                  className="font-mono text-xs"
                />
              </div>
            </div>

            <SheetFooter className="px-0 pt-2">
              <Button type="submit" disabled={saving} className="w-full">
                {saving ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>{editing ? "保存修改" : "创建项目"}</span>
              </Button>
            </SheetFooter>
          </form>
        </SheetContent>
      </Sheet>

      {/* Delete Confirmation */}
      <AlertDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除项目「{deleteTarget?.name}」？</AlertDialogTitle>
            <AlertDialogDescription>
              删除后该项目下的 API 密钥将停止生效，此操作不可撤销。
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
