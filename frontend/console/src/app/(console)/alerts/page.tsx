"use client";

import { useMemo, useState, type FormEvent } from "react";
import {
  AlertTriangle,
  Bell,
  Loader2,
  Mail,
  Plus,
  Trash2,
  Webhook,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
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
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { alertApi, projectApi } from "@/lib/api/endpoints";
import type {
  AlertRuleDto,
  AlertType,
  EndpointDto,
  NotificationChannelType,
} from "@/lib/api/types";
import {
  formatDateTime,
  formatMicroUsd,
  parseUsdToMicros,
} from "@/lib/format/micro-usd";
import {
  ALERT_TYPE_LABEL,
  canManageOrg,
  NOTIFICATION_CHANNEL_LABEL,
  TOGGLE_STATUS_LABEL,
} from "@/lib/format/labels";

export default function AlertsPage() {
  const { currentOrg } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;
  const canEdit = canManageOrg(currentOrg?.role);

  const [activeTab, setActiveTab] = useState<"rules" | "endpoints">("rules");

  const rulesState = useAsyncData(
    (signal) => alertApi.listRules(orgId!, signal),
    [orgId],
    Boolean(orgId),
  );

  const endpointsState = useAsyncData(
    (signal) => alertApi.listEndpoints(orgId!, signal),
    [orgId],
    Boolean(orgId),
  );

  const projectsState = useAsyncData(
    (signal) => projectApi.list(orgId!, 1, 50, signal),
    [orgId],
    Boolean(orgId),
  );

  const projectMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of projectsState.data?.items ?? []) {
      map.set(p.id, p.name);
    }
    return map;
  }, [projectsState.data]);

  const endpointMap = useMemo(() => {
    const map = new Map<string, EndpointDto>();
    for (const ep of endpointsState.data ?? []) {
      map.set(ep.id, ep);
    }
    return map;
  }, [endpointsState.data]);

  // Create Endpoint Dialog
  const [epDialogOpen, setEpDialogOpen] = useState(false);
  const [epType, setEpType] = useState<NotificationChannelType>("WEBHOOK");
  const [epTarget, setEpTarget] = useState("");
  const [savingEp, setSavingEp] = useState(false);
  const [epError, setEpError] = useState<unknown>(null);
  const [signingSecret, setSigningSecret] = useState<string | null>(null);

  // Create Rule Dialog
  const [ruleDialogOpen, setRuleDialogOpen] = useState(false);
  const [ruleType, setRuleType] = useState<AlertType>("LOW_BALANCE");
  const [ruleProjectId, setRuleProjectId] = useState("");
  const [ruleThresholdUsd, setRuleThresholdUsd] = useState("10");
  const [ruleCooldown, setRuleCooldown] = useState("60");
  const [ruleEndpointIds, setRuleEndpointIds] = useState<string[]>([]);
  const [savingRule, setSavingRule] = useState(false);
  const [ruleError, setRuleError] = useState<unknown>(null);

  async function handleCreateEndpoint(e: FormEvent) {
    e.preventDefault();
    if (!orgId) return;
    setEpError(null);
    setSavingEp(true);
    try {
      const res = await alertApi.createEndpoint(orgId, {
        channelType: epType,
        target: epTarget.trim(),
      });
      setEpDialogOpen(false);
      setEpTarget("");
      endpointsState.reload();
      if (res.signingSecret) {
        setSigningSecret(res.signingSecret);
      } else {
        toast.success("已添加通知渠道");
      }
    } catch (err) {
      setEpError(err);
    } finally {
      setSavingEp(false);
    }
  }

  async function handleToggleEndpoint(ep: EndpointDto) {
    if (!orgId) return;
    const nextStatus = ep.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
    try {
      await alertApi.updateEndpoint(orgId, ep.id, {
        status: nextStatus,
        version: ep.version,
      });
      toast.success(nextStatus === "ACTIVE" ? "已启用渠道" : "已停用渠道");
      endpointsState.reload();
    } catch {
      toast.error("状态更新失败，请刷新重试");
    }
  }

  async function handleDeleteEndpoint(ep: EndpointDto) {
    if (!orgId) return;
    try {
      await alertApi.deleteEndpoint(orgId, ep.id, ep.version);
      toast.success("已删除通知渠道");
      endpointsState.reload();
    } catch (err) {
      setEpError(err);
      toast.error("删除失败：该渠道可能正被规则引用");
    }
  }

  async function handleCreateRule(e: FormEvent) {
    e.preventDefault();
    if (!orgId) return;
    setRuleError(null);

    if (ruleEndpointIds.length === 0) {
      toast.error("请至少选择一个通知渠道");
      return;
    }

    const isLowBalance = ruleType === "LOW_BALANCE";
    const thresholdMicros = isLowBalance
      ? parseUsdToMicros(ruleThresholdUsd)
      : 0;
    if (isLowBalance && thresholdMicros === null) {
      toast.error("请输入有效的低余额阈值");
      return;
    }

    if (!isLowBalance && !ruleProjectId) {
      toast.error("请选择关联项目");
      return;
    }

    setSavingRule(true);
    try {
      await alertApi.createRule(orgId, {
        alertType: ruleType,
        projectId: isLowBalance ? null : ruleProjectId,
        thresholdMicros: thresholdMicros ?? 0,
        endpointIds: ruleEndpointIds,
        cooldownMinutes: Math.min(1440, Math.max(1, Number(ruleCooldown) || 60)),
      });
      setRuleDialogOpen(false);
      setRuleEndpointIds([]);
      toast.success("已创建告警规则");
      rulesState.reload();
    } catch (err) {
      setRuleError(err);
    } finally {
      setSavingRule(false);
    }
  }

  async function handleDeleteRule(rule: AlertRuleDto) {
    if (!orgId) return;
    try {
      await alertApi.deleteRule(orgId, rule.id, rule.version);
      toast.success("已删除告警规则");
      rulesState.reload();
    } catch {
      toast.error("删除失败，请刷新重试");
    }
  }

  function toggleEndpointSelect(id: string) {
    setRuleEndpointIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id],
    );
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-lg font-semibold tracking-tight">告警与通知</h1>
        {canEdit ? (
          activeTab === "rules" ? (
            <Button
              size="sm"
              onClick={() => {
                setRuleError(null);
                setRuleDialogOpen(true);
              }}
            >
              <Plus className="size-3.5" />
              <span>新建规则</span>
            </Button>
          ) : (
            <Button
              size="sm"
              onClick={() => {
                setEpError(null);
                setEpDialogOpen(true);
              }}
            >
              <Plus className="size-3.5" />
              <span>添加渠道</span>
            </Button>
          )
        ) : null}
      </div>

      <ApiErrorAlert
        error={rulesState.error ?? endpointsState.error}
        onRetry={() => {
          rulesState.reload();
          endpointsState.reload();
        }}
      />

      <Tabs
        value={activeTab}
        onValueChange={(v) => setActiveTab(v as "rules" | "endpoints")}
        className="gap-4"
      >
        <TabsList>
          <TabsTrigger value="rules">
            告警规则（{rulesState.data?.length ?? 0}）
          </TabsTrigger>
          <TabsTrigger value="endpoints">
            通知渠道（{endpointsState.data?.length ?? 0}）
          </TabsTrigger>
        </TabsList>

        <TabsContent value="rules">
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {rulesState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : (rulesState.data ?? []).length === 0 ? (
              <EmptyState
                icon={<Bell className="size-4" />}
                title="暂无告警规则"
                action={
                  canEdit ? (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setRuleDialogOpen(true)}
                    >
                      新建规则
                    </Button>
                  ) : null
                }
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>规则类型</TableHead>
                    <TableHead>作用范围</TableHead>
                    <TableHead className="text-right">触发阈值</TableHead>
                    <TableHead>通知渠道</TableHead>
                    <TableHead className="text-right">冷却 / 最近触发</TableHead>
                    {canEdit ? <TableHead className="text-right">操作</TableHead> : null}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(rulesState.data ?? []).map((rule) => (
                    <TableRow key={rule.id}>
                      <TableCell className="font-medium">
                        {ALERT_TYPE_LABEL[rule.alertType]}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {rule.projectId
                          ? projectMap.get(rule.projectId) ?? "指定项目"
                          : "整个组织钱包"}
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {rule.alertType === "LOW_BALANCE"
                          ? `≤ ${formatMicroUsd(rule.thresholdMicros)}`
                          : "跟随项目预算"}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {rule.endpointIds.map((epId) => {
                            const ep = endpointMap.get(epId);
                            return (
                              <Badge
                                key={epId}
                                variant="secondary"
                                className="max-w-44 truncate text-[11px]"
                              >
                                {ep ? ep.target : "通知渠道"}
                              </Badge>
                            );
                          })}
                        </div>
                      </TableCell>
                      <TableCell className="text-right text-xs text-muted-foreground tabular-nums">
                        <div>{rule.cooldownMinutes} 分钟</div>
                        <div>{formatDateTime(rule.lastTriggeredAt)}</div>
                      </TableCell>
                      {canEdit ? (
                        <TableCell className="text-right">
                          <Button
                            variant="ghost"
                            size="icon-xs"
                            aria-label="删除规则"
                            className="text-destructive hover:text-destructive"
                            onClick={() => handleDeleteRule(rule)}
                          >
                            <Trash2 className="size-3.5" />
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

        <TabsContent value="endpoints">
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            {endpointsState.loading ? (
              <div className="flex flex-col gap-3 p-5">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : (endpointsState.data ?? []).length === 0 ? (
              <EmptyState
                icon={<Webhook className="size-4" />}
                title="暂无通知渠道"
                action={
                  canEdit ? (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setEpDialogOpen(true)}
                    >
                      添加渠道
                    </Button>
                  ) : null
                }
              />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className="bg-muted/35 hover:bg-muted/35">
                    <TableHead>类型</TableHead>
                    <TableHead>接收地址</TableHead>
                    <TableHead>状态</TableHead>
                    {canEdit ? <TableHead className="text-right">操作</TableHead> : null}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(endpointsState.data ?? []).map((ep) => (
                    <TableRow key={ep.id}>
                      <TableCell>
                        <div className="flex items-center gap-2 text-sm font-medium">
                          {ep.channelType === "WEBHOOK" ? (
                            <Webhook className="size-3.5 text-muted-foreground" />
                          ) : (
                            <Mail className="size-3.5 text-muted-foreground" />
                          )}
                          <span>{NOTIFICATION_CHANNEL_LABEL[ep.channelType]}</span>
                        </div>
                      </TableCell>
                      <TableCell className="font-mono text-xs">
                        {ep.target}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-2.5">
                          {canEdit ? (
                            <Switch
                              checked={ep.status === "ACTIVE"}
                              onCheckedChange={() => handleToggleEndpoint(ep)}
                              aria-label="启停渠道"
                            />
                          ) : null}
                          <StatusDot
                            tone={ep.status === "ACTIVE" ? "success" : "muted"}
                            label={TOGGLE_STATUS_LABEL[ep.status]}
                          />
                        </div>
                      </TableCell>
                      {canEdit ? (
                        <TableCell className="text-right">
                          <Button
                            variant="ghost"
                            size="icon-xs"
                            aria-label="删除渠道"
                            className="text-destructive hover:text-destructive"
                            onClick={() => handleDeleteEndpoint(ep)}
                          >
                            <Trash2 className="size-3.5" />
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

      {/* Add Endpoint Dialog */}
      <Dialog open={epDialogOpen} onOpenChange={setEpDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>添加通知渠道</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreateEndpoint} className="flex flex-col gap-4">
            <ApiErrorAlert error={epError} />
            <div className="flex flex-col gap-1.5">
              <Label>渠道类型</Label>
              <Select
                value={epType}
                onValueChange={(v) =>
                  setEpType((v as NotificationChannelType) ?? "WEBHOOK")
                }
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="WEBHOOK">Webhook (HTTPS)</SelectItem>
                  <SelectItem value="EMAIL">邮件</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="ep-target">
                {epType === "WEBHOOK" ? "Webhook 地址" : "收件邮箱"}
              </Label>
              <Input
                id="ep-target"
                required
                type={epType === "EMAIL" ? "email" : "url"}
                value={epTarget}
                onChange={(e) => setEpTarget(e.target.value)}
                placeholder={
                  epType === "WEBHOOK"
                    ? "https://hooks.example.com/hyperllm"
                    : "ops@company.com"
                }
                className="font-mono text-xs"
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setEpDialogOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={savingEp}>
                {savingEp ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>添加</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Create Rule Dialog */}
      <Dialog open={ruleDialogOpen} onOpenChange={setRuleDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>新建告警规则</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreateRule} className="flex flex-col gap-4">
            <ApiErrorAlert error={ruleError} />
            <div className="flex flex-col gap-1.5">
              <Label>告警类型</Label>
              <Select
                value={ruleType}
                onValueChange={(v) => setRuleType((v as AlertType) ?? "LOW_BALANCE")}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="LOW_BALANCE">低余额提醒</SelectItem>
                  <SelectItem value="PROJECT_SOFT_CAP">项目月度软预算</SelectItem>
                  <SelectItem value="PROJECT_HARD_CAP">项目月度硬预算</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {ruleType === "LOW_BALANCE" ? (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="rule-threshold">低余额阈值</Label>
                <div className="relative">
                  <Input
                    id="rule-threshold"
                    required
                    inputMode="decimal"
                    value={ruleThresholdUsd}
                    onChange={(e) => setRuleThresholdUsd(e.target.value)}
                    placeholder="10.00"
                    className="pr-11 font-mono tabular-nums"
                  />
                  <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                    USD
                  </span>
                </div>
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                <Label>关联项目</Label>
                <Select
                  value={ruleProjectId}
                  onValueChange={(v) => setRuleProjectId(v ?? "")}
                >
                  <SelectTrigger className="w-full">
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
              </div>
            )}

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="rule-cooldown">冷却时间</Label>
              <div className="relative">
                <Input
                  id="rule-cooldown"
                  type="number"
                  min={1}
                  max={1440}
                  value={ruleCooldown}
                  onChange={(e) => setRuleCooldown(e.target.value)}
                  className="pr-11 font-mono tabular-nums"
                />
                <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-muted-foreground">
                  分钟
                </span>
              </div>
            </div>

            <div className="flex flex-col gap-2">
              <Label>接收渠道</Label>
              {(endpointsState.data ?? []).length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  请先添加至少一个通知渠道
                </p>
              ) : (
                <div className="flex max-h-36 flex-col gap-2 overflow-y-auto rounded-lg border border-border p-2.5">
                  {(endpointsState.data ?? []).map((ep) => (
                    <label
                      key={ep.id}
                      className="flex cursor-pointer items-center gap-2.5 text-xs"
                    >
                      <Checkbox
                        checked={ruleEndpointIds.includes(ep.id)}
                        onCheckedChange={() => toggleEndpointSelect(ep.id)}
                      />
                      <span className="truncate font-mono">{ep.target}</span>
                    </label>
                  ))}
                </div>
              )}
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setRuleDialogOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={savingRule}>
                {savingRule ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>创建</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* One-Time Webhook Signing Secret Dialog */}
      <Dialog
        open={Boolean(signingSecret)}
        onOpenChange={(open) => {
          if (!open) setSigningSecret(null);
        }}
      >
        <DialogContent showCloseButton={false} className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <AlertTriangle className="size-4 text-amber-500" />
              <span>保存 Webhook 签名密钥</span>
            </DialogTitle>
          </DialogHeader>
          <p className="text-xs text-muted-foreground">
            签名密钥用于校验通知来源，仅在此时展示一次。
          </p>
          <div className="flex items-center justify-between gap-2 rounded-lg border border-border bg-muted/45 px-3 py-2.5">
            <code className="break-all font-mono text-xs">{signingSecret}</code>
            {signingSecret ? (
              <CopyButton
                value={signingSecret}
                label="复制"
                variant="outline"
                copiedToast="已复制签名密钥"
              />
            ) : null}
          </div>
          <DialogFooter>
            <Button onClick={() => setSigningSecret(null)}>我已保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
