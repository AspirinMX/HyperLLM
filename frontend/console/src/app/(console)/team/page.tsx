"use client";

import { useState, type FormEvent } from "react";
import {
  Check,
  Loader2,
  Save,
  Trash2,
  UserPlus,
  Users,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
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
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { CopyButton } from "@/components/common/copy-button";
import { EmptyState } from "@/components/common/empty-state";
import { StatusDot } from "@/components/common/status-dot";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { orgApi, projectApi } from "@/lib/api/endpoints";
import type {
  InvitationResponseDto,
  OrgMemberDto,
  OrgRole,
} from "@/lib/api/types";
import { formatDateTime } from "@/lib/format/micro-usd";
import {
  canManageOrg,
  ORG_ROLE_LABEL,
  ORG_STATUS_LABEL,
} from "@/lib/format/labels";

export default function TeamPage() {
  const { currentOrg, user, refreshProfile } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;
  const canEdit = canManageOrg(currentOrg?.role);

  const orgState = useAsyncData(
    (signal) => orgApi.get(orgId!, signal),
    [orgId],
    Boolean(orgId),
  );

  const membersState = useAsyncData(
    (signal) => orgApi.listMembers(orgId!, signal),
    [orgId],
    Boolean(orgId),
  );

  const projectsState = useAsyncData(
    (signal) => projectApi.list(orgId!, 1, 50, signal),
    [orgId],
    Boolean(orgId) && canEdit,
  );

  const [orgNameDraft, setOrgNameDraft] = useState<string | null>(null);
  const [savingOrg, setSavingOrg] = useState(false);
  const [orgError, setOrgError] = useState<unknown>(null);

  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<OrgRole>("DEVELOPER");
  const [inviteProjectIds, setInviteProjectIds] = useState<string[]>([]);
  const [inviting, setInviting] = useState(false);
  const [inviteError, setInviteError] = useState<unknown>(null);
  const [createdInvite, setCreatedInvite] =
    useState<InvitationResponseDto | null>(null);

  const [removeTarget, setRemoveTarget] = useState<OrgMemberDto | null>(null);
  const [removing, setRemoving] = useState(false);

  const org = orgState.data;
  const displayedOrgName = orgNameDraft ?? org?.name ?? "";

  async function handleSaveOrg(e: FormEvent) {
    e.preventDefault();
    if (!org || !displayedOrgName.trim()) return;
    setOrgError(null);
    setSavingOrg(true);
    try {
      await orgApi.update(org.id, {
        name: displayedOrgName.trim(),
        version: org.version,
      });
      setOrgNameDraft(null);
      toast.success("组织名称已更新");
      orgState.reload();
      await refreshProfile();
    } catch (err) {
      setOrgError(err);
    } finally {
      setSavingOrg(false);
    }
  }

  async function handleRoleChange(member: OrgMemberDto, role: OrgRole) {
    if (!orgId) return;
    try {
      await orgApi.updateMemberRole(orgId, member.memberId, role);
      toast.success(`已调整 ${member.displayName} 为${ORG_ROLE_LABEL[role]}`);
      membersState.reload();
    } catch {
      toast.error("角色修改失败");
    }
  }

  async function handleRemoveMember() {
    if (!orgId || !removeTarget) return;
    setRemoving(true);
    try {
      await orgApi.removeMember(orgId, removeTarget.memberId);
      toast.success(`已移除成员「${removeTarget.displayName}」`);
      setRemoveTarget(null);
      membersState.reload();
    } catch {
      toast.error("移除成员失败");
    } finally {
      setRemoving(false);
    }
  }

  async function handleInvite(e: FormEvent) {
    e.preventDefault();
    if (!orgId) return;
    setInviteError(null);
    setInviting(true);
    try {
      const res = await orgApi.createInvitation(orgId, {
        email: inviteEmail.trim(),
        role: inviteRole,
        projectIds: inviteProjectIds.length > 0 ? inviteProjectIds : undefined,
      });
      setCreatedInvite(res);
      setInviteEmail("");
      setInviteProjectIds([]);
      toast.success("邀请已生成");
    } catch (err) {
      setInviteError(err);
    } finally {
      setInviting(false);
    }
  }

  const inviteLink =
    createdInvite && typeof window !== "undefined"
      ? `${window.location.origin}/invitations/accept?token=${encodeURIComponent(createdInvite.invitationToken)}`
      : createdInvite?.invitationToken ?? "";

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-lg font-semibold tracking-tight">团队与设置</h1>

      <ApiErrorAlert
        error={orgState.error ?? membersState.error ?? orgError}
        onRetry={() => {
          orgState.reload();
          membersState.reload();
        }}
      />

      {/* Organization Info Panel */}
      <section className="squircle-md rounded-xl border border-border bg-card p-5">
        {orgState.loading ? (
          <Skeleton className="h-20 w-full" />
        ) : org ? (
          <form
            onSubmit={handleSaveOrg}
            className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between"
          >
            <div className="flex flex-1 flex-col gap-3 sm:max-w-md">
              <div className="flex items-center gap-2">
                <Label htmlFor="org-name-input">工作空间名称</Label>
                <StatusDot
                  tone={org.status === "ACTIVE" ? "success" : "danger"}
                  label={ORG_STATUS_LABEL[org.status]}
                />
                {org.personal ? <Badge variant="secondary">个人</Badge> : null}
              </div>
              <div className="flex items-center gap-2">
                <Input
                  id="org-name-input"
                  disabled={!canEdit}
                  value={displayedOrgName}
                  onChange={(e) => setOrgNameDraft(e.target.value)}
                />
                {canEdit ? (
                  <Button
                    type="submit"
                    size="sm"
                    disabled={savingOrg || displayedOrgName.trim() === org.name}
                  >
                    {savingOrg ? (
                      <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                    ) : (
                      <Save className="size-3.5" />
                    )}
                    <span>保存</span>
                  </Button>
                ) : null}
              </div>
            </div>

            <div className="flex items-center gap-6 text-xs text-muted-foreground tabular-nums">
              <div>
                <div>组织标识</div>
                <div className="mt-0.5 font-mono text-foreground">{org.slug}</div>
              </div>
              <div>
                <div>并发上限</div>
                <div className="mt-0.5 font-mono text-foreground">
                  {org.maxConcurrency}
                </div>
              </div>
            </div>
          </form>
        ) : null}
      </section>

      {/* Members Table */}
      <section className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium">组织成员</span>
            <Badge variant="secondary" className="tabular-nums">
              {membersState.data?.length ?? 0}
            </Badge>
          </div>
          {canEdit ? (
            <Button
              size="sm"
              onClick={() => {
                setCreatedInvite(null);
                setInviteError(null);
                setInviteOpen(true);
              }}
            >
              <UserPlus className="size-3.5" />
              <span>邀请成员</span>
            </Button>
          ) : null}
        </div>

        {membersState.loading ? (
          <div className="flex flex-col gap-3 p-5">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : (membersState.data ?? []).length === 0 ? (
          <EmptyState icon={<Users className="size-4" />} title="暂无成员" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/35 hover:bg-muted/35">
                <TableHead>成员</TableHead>
                <TableHead>角色</TableHead>
                <TableHead>加入时间</TableHead>
                {canEdit ? <TableHead className="text-right">操作</TableHead> : null}
              </TableRow>
            </TableHeader>
            <TableBody>
              {(membersState.data ?? []).map((member) => {
                const isOwner = member.role === "OWNER";
                const isSelf = member.userId === user?.id;
                const editableRow = canEdit && !isOwner && !isSelf;

                return (
                  <TableRow key={member.memberId}>
                    <TableCell>
                      <div className="flex items-center gap-2.5">
                        <Avatar className="size-7 rounded-lg">
                          <AvatarFallback className="rounded-lg text-xs">
                            {member.displayName.slice(0, 2).toUpperCase()}
                          </AvatarFallback>
                        </Avatar>
                        <div className="flex flex-col">
                          <span className="font-medium">
                            {member.displayName}
                            {isSelf ? (
                              <span className="ml-1.5 text-xs text-muted-foreground">
                                (当前)
                              </span>
                            ) : null}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {member.email}
                          </span>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      {editableRow ? (
                        <Select
                          value={member.role}
                          onValueChange={(v) =>
                            v && handleRoleChange(member, v as OrgRole)
                          }
                        >
                          <SelectTrigger size="sm" className="w-28">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="ADMIN">管理员</SelectItem>
                            <SelectItem value="DEVELOPER">开发者</SelectItem>
                            <SelectItem value="BILLING">财务</SelectItem>
                          </SelectContent>
                        </Select>
                      ) : (
                        <Badge variant="outline">
                          {ORG_ROLE_LABEL[member.role]}
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground tabular-nums">
                      {formatDateTime(member.joinedAt)}
                    </TableCell>
                    {canEdit ? (
                      <TableCell className="text-right">
                        {editableRow ? (
                          <Button
                            variant="ghost"
                            size="icon-xs"
                            aria-label="移除成员"
                            className="text-destructive hover:text-destructive"
                            onClick={() => setRemoveTarget(member)}
                          >
                            <Trash2 className="size-3.5" />
                          </Button>
                        ) : null}
                      </TableCell>
                    ) : null}
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </section>

      {/* Invite Member Dialog */}
      <Dialog open={inviteOpen} onOpenChange={setInviteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {createdInvite ? "邀请链接已生成" : "邀请成员加入"}
            </DialogTitle>
          </DialogHeader>

          {!createdInvite ? (
            <form onSubmit={handleInvite} className="flex flex-col gap-4">
              <ApiErrorAlert error={inviteError} />
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="inv-email">受邀邮箱</Label>
                <Input
                  id="inv-email"
                  type="email"
                  required
                  value={inviteEmail}
                  onChange={(e) => setInviteEmail(e.target.value)}
                  placeholder="teammate@company.com"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <Label>角色</Label>
                <Select
                  value={inviteRole}
                  onValueChange={(v) =>
                    setInviteRole((v as OrgRole) ?? "DEVELOPER")
                  }
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ADMIN">管理员</SelectItem>
                    <SelectItem value="DEVELOPER">开发者</SelectItem>
                    <SelectItem value="BILLING">财务</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {inviteRole === "DEVELOPER" &&
              (projectsState.data?.items ?? []).length > 0 ? (
                <div className="flex flex-col gap-2">
                  <Label>初始授权项目（可选）</Label>
                  <div className="flex max-h-32 flex-col gap-2 overflow-y-auto rounded-lg border border-border p-2.5">
                    {(projectsState.data?.items ?? []).map((p) => (
                      <label
                        key={p.id}
                        className="flex cursor-pointer items-center gap-2 text-xs"
                      >
                        <Checkbox
                          checked={inviteProjectIds.includes(p.id)}
                          onCheckedChange={() =>
                            setInviteProjectIds((prev) =>
                              prev.includes(p.id)
                                ? prev.filter((id) => id !== p.id)
                                : [...prev, p.id],
                            )
                          }
                        />
                        <span>{p.name}</span>
                      </label>
                    ))}
                  </div>
                </div>
              ) : null}

              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setInviteOpen(false)}
                >
                  取消
                </Button>
                <Button type="submit" disabled={inviting}>
                  {inviting ? (
                    <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                  ) : null}
                  <span>生成邀请</span>
                </Button>
              </DialogFooter>
            </form>
          ) : (
            <div className="flex flex-col gap-4">
              <div className="flex items-center gap-2 text-xs text-emerald-700 dark:text-emerald-400">
                <Check className="size-4" />
                <span>
                  发送以下链接给 {createdInvite.email}，有效期至{" "}
                  {formatDateTime(createdInvite.expiresAt)}
                </span>
              </div>
              <div className="flex items-center justify-between gap-2 rounded-lg border border-border bg-muted/45 px-3 py-2.5">
                <code className="break-all font-mono text-xs">{inviteLink}</code>
                <CopyButton
                  value={inviteLink}
                  label="复制"
                  variant="outline"
                  copiedToast="已复制邀请链接"
                />
              </div>
              <DialogFooter>
                <Button onClick={() => setInviteOpen(false)}>完成</Button>
              </DialogFooter>
            </div>
          )}
        </DialogContent>
      </Dialog>

      {/* Remove Member Confirmation */}
      <AlertDialog
        open={Boolean(removeTarget)}
        onOpenChange={(open) => {
          if (!open) setRemoveTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              移除成员「{removeTarget?.displayName}」？
            </AlertDialogTitle>
            <AlertDialogDescription>
              移除后该成员将立即失去对当前组织及所有项目的访问权限。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={removing}
              onClick={handleRemoveMember}
            >
              {removing ? (
                <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
              ) : null}
              <span>确认移除</span>
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
