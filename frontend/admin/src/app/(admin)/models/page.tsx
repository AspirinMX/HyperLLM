"use client";

import { useMemo, useState, type FormEvent } from "react";
import Link from "next/link";
import {
  ArrowRight,
  Brain,
  Cpu,
  Database,
  Eye,
  Loader2,
  Plus,
  Search,
  Wrench,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
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
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { EmptyState } from "@/components/common/empty-state";
import { StatusDot } from "@/components/common/status-dot";
import { useAdminAuth } from "@/components/providers/admin-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminModelApi } from "@/lib/api/endpoints";
import type {
  ModelCapability,
  ModelVisibility,
  ProtocolFamily,
  RoutingStrategy,
  TranslationPolicy,
} from "@/lib/api/types";
import { formatCompactNumber } from "@/lib/format/micro-usd";
import {
  canOpsWrite,
  MODEL_STATUS_LABEL,
  MODEL_VISIBILITY_LABEL,
  PROTOCOL_LABEL,
  ROUTING_STRATEGY_LABEL,
  TRANSLATION_POLICY_LABEL,
} from "@/lib/format/labels";

interface CreateModelForm {
  modelCode: string;
  displayName: string;
  providerBadge: string;
  capabilityType: ModelCapability;
  nativeProtocol: ProtocolFamily;
  contextWindow: string;
  maxOutputTokens: string;
  supportsTools: boolean;
  supportsVision: boolean;
  supportsThinking: boolean;
  supportsPromptCache: boolean;
  routingStrategy: RoutingStrategy;
  stickyCacheEnabled: boolean;
  stickyTtlSeconds: string;
  visibility: ModelVisibility;
  fallbackModelCode: string;
  allowProtocolTranslation: boolean;
  translationPolicy: TranslationPolicy;
}

const DEFAULT_CREATE_FORM: CreateModelForm = {
  modelCode: "",
  displayName: "",
  providerBadge: "",
  capabilityType: "CHAT",
  nativeProtocol: "OPENAI",
  contextWindow: "128000",
  maxOutputTokens: "16384",
  supportsTools: true,
  supportsVision: false,
  supportsThinking: false,
  supportsPromptCache: false,
  routingStrategy: "PRIORITY_WEIGHTED",
  stickyCacheEnabled: false,
  stickyTtlSeconds: "300",
  visibility: "PUBLIC",
  fallbackModelCode: "",
  allowProtocolTranslation: true,
  translationPolicy: "BEST_EFFORT",
};

export default function AdminModelsPage() {
  const { profile } = useAdminAuth();
  const canEdit = canOpsWrite(profile?.role);

  const [page, setPage] = useState(1);
  const pageSize = 20;

  const modelsState = useAsyncData(
    (signal) => adminModelApi.listModels(page, pageSize, signal),
    [page],
  );

  const [query, setQuery] = useState("");
  const [sheetOpen, setSheetOpen] = useState(false);
  const [form, setForm] = useState<CreateModelForm>(DEFAULT_CREATE_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<unknown>(null);

  const filtered = useMemo(() => {
    const items = modelsState.data?.items ?? [];
    const q = query.trim().toLowerCase();
    if (!q) return items;
    return items.filter(
      (m) =>
        m.modelCode.toLowerCase().includes(q) ||
        m.displayName.toLowerCase().includes(q) ||
        (m.providerBadge ?? "").toLowerCase().includes(q),
    );
  }, [modelsState.data, query]);

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    const contextWindow = Number(form.contextWindow);
    if (!Number.isInteger(contextWindow) || contextWindow <= 0) {
      toast.error("请输入有效的上下文窗口");
      return;
    }

    setSaving(true);
    try {
      await adminModelApi.createModel({
        modelCode: form.modelCode.trim(),
        displayName: form.displayName.trim(),
        providerBadge: form.providerBadge.trim() || null,
        capabilityType: form.capabilityType,
        nativeProtocol: form.nativeProtocol,
        contextWindow,
        maxOutputTokens: form.maxOutputTokens.trim()
          ? Number(form.maxOutputTokens)
          : null,
        supportsTools: form.supportsTools,
        supportsVision: form.supportsVision,
        supportsThinking: form.supportsThinking,
        supportsPromptCache: form.supportsPromptCache,
        routingStrategy: form.routingStrategy,
        stickyCacheEnabled: form.stickyCacheEnabled,
        stickyTtlSeconds: Math.max(0, Number(form.stickyTtlSeconds) || 0),
        visibility: form.visibility,
        fallbackModelCode: form.fallbackModelCode.trim() || null,
        allowProtocolTranslation: form.allowProtocolTranslation,
        translationPolicy: form.translationPolicy,
      });
      toast.success("虚拟模型已创建");
      setSheetOpen(false);
      setForm(DEFAULT_CREATE_FORM);
      modelsState.reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="relative w-full sm:w-64">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索模型标识或名称"
            className="pl-8"
          />
        </div>

        {canEdit ? (
          <Button
            size="sm"
            onClick={() => {
              setFormError(null);
              setSheetOpen(true);
            }}
          >
            <Plus className="size-3.5" />
            <span>新建虚拟模型</span>
          </Button>
        ) : null}
      </div>

      <ApiErrorAlert error={modelsState.error} onRetry={modelsState.reload} />

      <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        {modelsState.loading ? (
          <div className="flex flex-col gap-3 p-5">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-11 w-full" />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={<Cpu className="size-4" />}
            title="暂无虚拟模型"
            action={
              canEdit ? (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setSheetOpen(true)}
                >
                  新建虚拟模型
                </Button>
              ) : null
            }
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/35 hover:bg-muted/35">
                <TableHead>模型</TableHead>
                <TableHead>协议 / 能力</TableHead>
                <TableHead className="text-right">上下文</TableHead>
                <TableHead>路由策略</TableHead>
                <TableHead>可见性 / 状态</TableHead>
                <TableHead className="text-right">配置</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((m) => (
                <TableRow key={m.id}>
                  <TableCell>
                    <Link
                      href={`/models/${m.id}`}
                      className="flex flex-col gap-0.5 hover:underline"
                    >
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{m.displayName}</span>
                        {m.providerBadge ? (
                          <Badge variant="secondary" className="text-[10px]">
                            {m.providerBadge}
                          </Badge>
                        ) : null}
                      </div>
                      <code className="font-mono text-xs text-muted-foreground">
                        {m.modelCode}
                      </code>
                    </Link>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Badge variant="outline">
                        {PROTOCOL_LABEL[m.nativeProtocol]}
                      </Badge>
                      <div className="flex items-center gap-1 text-muted-foreground">
                        {m.supportsTools ? <Wrench className="size-3.5" /> : null}
                        {m.supportsVision ? <Eye className="size-3.5" /> : null}
                        {m.supportsThinking ? <Brain className="size-3.5" /> : null}
                        {m.supportsPromptCache ? (
                          <Database className="size-3.5" />
                        ) : null}
                      </div>
                    </div>
                  </TableCell>
                  <TableCell className="text-right font-mono text-xs tabular-nums">
                    {formatCompactNumber(m.contextWindow)}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1.5">
                      <Badge variant="secondary">
                        {ROUTING_STRATEGY_LABEL[m.routingStrategy]}
                      </Badge>
                      {m.stickyCacheEnabled ? (
                        <Badge variant="outline" className="text-[10px] tabular-nums">
                          粘性 {m.stickyTtlSeconds}s
                        </Badge>
                      ) : null}
                      {m.allowProtocolTranslation ? (
                        <Badge
                          variant="outline"
                          className="font-mono text-[10px]"
                          title={TRANSLATION_POLICY_LABEL[m.translationPolicy]}
                        >
                          {m.translationPolicy === "STRICT" ? "严格转译" : "跨协议"}
                        </Badge>
                      ) : (
                        <Badge variant="outline" className="text-[10px] text-muted-foreground">
                          仅同构
                        </Badge>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Badge variant="outline" className="text-[11px]">
                        {MODEL_VISIBILITY_LABEL[m.visibility]}
                      </Badge>
                      <StatusDot
                        tone={
                          m.status === "ACTIVE"
                            ? "success"
                            : m.status === "DEPRECATED"
                              ? "warning"
                              : "muted"
                        }
                        label={MODEL_STATUS_LABEL[m.status]}
                      />
                    </div>
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="ghost"
                      size="xs"
                      render={<Link href={`/models/${m.id}`} />}
                    >
                      <span>路由与定价</span>
                      <ArrowRight className="size-3" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </div>

      {(modelsState.data?.total ?? 0) > pageSize ? (
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <span className="tabular-nums">
            第 {page} / {Math.max(1, Math.ceil((modelsState.data?.total ?? 0) / pageSize))} 页 · 共 {modelsState.data?.total ?? 0} 个模型
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
              disabled={page * pageSize >= (modelsState.data?.total ?? 0)}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        </div>
      ) : null}

      {/* Create Model Dialog */}
      <Dialog open={sheetOpen} onOpenChange={setSheetOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>新建虚拟模型</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreate} className="flex flex-col gap-4">
            <ApiErrorAlert error={formError} />

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="m-code">模型标识</Label>
                <Input
                  id="m-code"
                  required
                  value={form.modelCode}
                  onChange={(e) =>
                    setForm({ ...form, modelCode: e.target.value })
                  }
                  placeholder="claude-sonnet-4-5"
                  className="font-mono text-xs"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="m-name">展示名称</Label>
                <Input
                  id="m-name"
                  required
                  value={form.displayName}
                  onChange={(e) =>
                    setForm({ ...form, displayName: e.target.value })
                  }
                  placeholder="Claude Sonnet 4.5"
                />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label>原生协议</Label>
                <Select
                  value={form.nativeProtocol}
                  onValueChange={(v) =>
                    setForm({
                      ...form,
                      nativeProtocol: (v as ProtocolFamily) ?? "OPENAI",
                    })
                  }
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="OPENAI">OpenAI</SelectItem>
                    <SelectItem value="ANTHROPIC">Anthropic</SelectItem>
                    <SelectItem value="GEMINI">Gemini</SelectItem>
                    <SelectItem value="COHERE_RERANK">Rerank</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <Label>能力类型</Label>
                <Select
                  value={form.capabilityType}
                  onValueChange={(v) =>
                    setForm({
                      ...form,
                      capabilityType: (v as ModelCapability) ?? "CHAT",
                    })
                  }
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="CHAT">对话</SelectItem>
                    <SelectItem value="REASONING">推理</SelectItem>
                    <SelectItem value="EMBEDDING">向量</SelectItem>
                    <SelectItem value="IMAGE">图像</SelectItem>
                    <SelectItem value="RERANK">重排</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="m-ctx">上下文窗口</Label>
                <Input
                  id="m-ctx"
                  required
                  inputMode="numeric"
                  value={form.contextWindow}
                  onChange={(e) =>
                    setForm({ ...form, contextWindow: e.target.value })
                  }
                  className="font-mono tabular-nums"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="m-out">最大输出</Label>
                <Input
                  id="m-out"
                  inputMode="numeric"
                  value={form.maxOutputTokens}
                  onChange={(e) =>
                    setForm({ ...form, maxOutputTokens: e.target.value })
                  }
                  className="font-mono tabular-nums"
                />
              </div>
            </div>

            <Separator />

            <div className="grid grid-cols-2 gap-3 text-xs">
              {(
                [
                  ["supportsTools", "工具调用"],
                  ["supportsVision", "视觉理解"],
                  ["supportsThinking", "深度推理"],
                  ["supportsPromptCache", "提示词缓存"],
                ] as const
              ).map(([field, label]) => (
                <div
                  key={field}
                  className="flex items-center justify-between rounded-lg border border-border px-3 py-2"
                >
                  <span>{label}</span>
                  <Switch
                    checked={form[field]}
                    onCheckedChange={(v) => setForm({ ...form, [field]: v })}
                  />
                </div>
              ))}
            </div>

            <Separator />

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label>路由策略</Label>
                <Select
                  value={form.routingStrategy}
                  onValueChange={(v) =>
                    setForm({
                      ...form,
                      routingStrategy:
                        (v as RoutingStrategy) ?? "PRIORITY_WEIGHTED",
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
                <Label>可见性</Label>
                <Select
                  value={form.visibility}
                  onValueChange={(v) =>
                    setForm({
                      ...form,
                      visibility: (v as ModelVisibility) ?? "PUBLIC",
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
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="m-badge">厂商徽标</Label>
                <Input
                  id="m-badge"
                  value={form.providerBadge}
                  onChange={(e) =>
                    setForm({ ...form, providerBadge: e.target.value })
                  }
                  placeholder="Anthropic"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="m-fb">回退模型</Label>
                <Input
                  id="m-fb"
                  value={form.fallbackModelCode}
                  onChange={(e) =>
                    setForm({ ...form, fallbackModelCode: e.target.value })
                  }
                  placeholder="可选"
                  className="font-mono text-xs"
                />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2 text-xs">
                <span>允许跨协议转译</span>
                <Switch
                  checked={form.allowProtocolTranslation}
                  onCheckedChange={(v) =>
                    setForm({ ...form, allowProtocolTranslation: v })
                  }
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label>转译策略</Label>
                <Select
                  disabled={!form.allowProtocolTranslation}
                  value={form.translationPolicy}
                  onValueChange={(v) =>
                    setForm({
                      ...form,
                      translationPolicy:
                        (v as TranslationPolicy) ?? "BEST_EFFORT",
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
                <span>创建模型</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
