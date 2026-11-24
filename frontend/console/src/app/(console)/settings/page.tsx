"use client";

import { useState, type FormEvent } from "react";
import { KeyRound, Loader2, Save, User as UserIcon } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { authApi } from "@/lib/api/endpoints";

export default function AccountSettingsPage() {
  const { user, refreshProfile } = useConsoleAuth();

  const [displayNameDraft, setDisplayNameDraft] = useState<string | null>(null);
  const [localeDraft, setLocaleDraft] = useState<string | null>(null);
  const [currencyDraft, setCurrencyDraft] = useState<"USD" | "CNY" | null>(
    null,
  );
  const [savingProfile, setSavingProfile] = useState(false);
  const [profileError, setProfileError] = useState<unknown>(null);

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [savingPassword, setSavingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState<unknown>(null);

  const displayName = displayNameDraft ?? user?.displayName ?? "";
  const locale = localeDraft ?? user?.locale ?? "zh-CN";
  const currency: "USD" | "CNY" =
    currencyDraft ?? (user?.preferredCurrency === "CNY" ? "CNY" : "USD");

  async function handleSaveProfile(e: FormEvent) {
    e.preventDefault();
    if (!displayName.trim()) return;
    setProfileError(null);
    setSavingProfile(true);
    try {
      await authApi.updateProfile({
        displayName: displayName.trim(),
        locale,
        preferredCurrency: currency,
      });
      await refreshProfile();
      setDisplayNameDraft(null);
      setLocaleDraft(null);
      setCurrencyDraft(null);
      toast.success("个人资料与偏好已保存");
    } catch (err) {
      setProfileError(err);
    } finally {
      setSavingProfile(false);
    }
  }

  async function handleChangePassword(e: FormEvent) {
    e.preventDefault();
    setPasswordError(null);
    if (newPassword.length < 8) {
      toast.error("新密码长度至少为 8 位");
      return;
    }
    if (newPassword !== confirmPassword) {
      toast.error("两次输入的新密码不一致");
      return;
    }
    setSavingPassword(true);
    try {
      await authApi.changePassword({
        currentPassword,
        newPassword,
      });
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      toast.success("登录密码已更新");
    } catch (err) {
      setPasswordError(err);
    } finally {
      setSavingPassword(false);
    }
  }

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <h1 className="text-lg font-semibold tracking-tight">个人设置</h1>

      {/* Profile Section */}
      <form
        onSubmit={handleSaveProfile}
        className="squircle-md flex flex-col gap-5 rounded-xl border border-border bg-card p-5"
      >
        <div className="flex items-center gap-2 text-sm font-medium">
          <UserIcon className="size-4" />
          <span>基本资料与偏好</span>
        </div>

        <ApiErrorAlert error={profileError} />

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="acc-email">登录邮箱</Label>
            <Input
              id="acc-email"
              value={user?.email ?? ""}
              disabled
              className="font-mono text-xs"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="acc-name">展示称呼</Label>
            <Input
              id="acc-name"
              required
              minLength={1}
              maxLength={100}
              value={displayName}
              onChange={(e) => setDisplayNameDraft(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>界面语言偏好</Label>
            <Select
              value={locale}
              onValueChange={(v) => v && setLocaleDraft(v)}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="zh-CN">简体中文 (zh-CN)</SelectItem>
                <SelectItem value="en-US">English (en-US)</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>偏好展示币种</Label>
            <Select
              value={currency}
              onValueChange={(v) =>
                v && setCurrencyDraft(v as "USD" | "CNY")
              }
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="USD">美元 (USD)</SelectItem>
                <SelectItem value="CNY">人民币 (CNY)</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="flex justify-end border-t border-border pt-4">
          <Button type="submit" size="sm" disabled={savingProfile}>
            {savingProfile ? (
              <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
            ) : (
              <Save className="size-3.5" />
            )}
            <span>保存资料</span>
          </Button>
        </div>
      </form>

      {/* Password Section */}
      <form
        onSubmit={handleChangePassword}
        className="squircle-md flex flex-col gap-5 rounded-xl border border-border bg-card p-5"
      >
        <div className="flex items-center gap-2 text-sm font-medium">
          <KeyRound className="size-4" />
          <span>修改登录密码</span>
        </div>

        <ApiErrorAlert error={passwordError} />

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="pwd-current">当前密码</Label>
            <Input
              id="pwd-current"
              type="password"
              autoComplete="current-password"
              required
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="pwd-new">新密码</Label>
              <Input
                id="pwd-new"
                type="password"
                autoComplete="new-password"
                required
                minLength={8}
                maxLength={128}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                placeholder="至少 8 位字符"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="pwd-confirm">确认新密码</Label>
              <Input
                id="pwd-confirm"
                type="password"
                autoComplete="new-password"
                required
                minLength={8}
                maxLength={128}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
              />
            </div>
          </div>
        </div>

        <div className="flex justify-end border-t border-border pt-4">
          <Button type="submit" size="sm" disabled={savingPassword}>
            {savingPassword ? (
              <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
            ) : (
              <KeyRound className="size-3.5" />
            )}
            <span>更新密码</span>
          </Button>
        </div>
      </form>
    </div>
  );
}
