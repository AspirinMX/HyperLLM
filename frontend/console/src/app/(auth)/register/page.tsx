"use client";

import { Suspense, useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { authApi } from "@/lib/api/endpoints";

function RegisterForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const redirect = searchParams.get("redirect") || "/overview";
  const { register } = useConsoleAuth();

  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [emailCode, setEmailCode] = useState("");
  const [password, setPassword] = useState("");
  const [cooldown, setCooldown] = useState(0);
  const [sendingCode, setSendingCode] = useState(false);
  const [debugCode, setDebugCode] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (cooldown <= 0) return;
    const id = window.setInterval(() => {
      setCooldown((c) => (c <= 1 ? 0 : c - 1));
    }, 1000);
    return () => window.clearInterval(id);
  }, [cooldown]);

  async function handleSendCode() {
    const trimmed = email.trim();
    if (!trimmed) {
      toast.error("请先输入邮箱");
      return;
    }
    setError(null);
    setSendingCode(true);
    try {
      const res = await authApi.sendEmailCode({
        email: trimmed,
        purpose: "REGISTER",
      });
      setCooldown(60);
      setDebugCode(res.debugCode);
      toast.success("验证码已发送");
    } catch (err) {
      setError(err);
    } finally {
      setSendingCode(false);
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register({
        email: email.trim(),
        displayName: displayName.trim(),
        emailCode: emailCode.trim(),
        password,
        locale: "zh-CN",
      });
      toast.success("注册成功，已自动创建个人工作空间");
      router.replace(redirect.startsWith("/") ? redirect : "/overview");
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1 text-center">
        <h1 className="text-base font-semibold tracking-tight">创建账号</h1>
      </div>

      <ApiErrorAlert error={error} />

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="reg-name">称呼</Label>
        <Input
          id="reg-name"
          required
          maxLength={100}
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          placeholder="用于团队内展示"
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="reg-email">邮箱</Label>
        <Input
          id="reg-email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="name@company.com"
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="reg-code">邮箱验证码</Label>
        <div className="flex items-center gap-2">
          <Input
            id="reg-code"
            required
            value={emailCode}
            onChange={(e) => setEmailCode(e.target.value)}
            placeholder="6 位验证码"
            className="font-mono tabular-nums"
          />
          <Button
            type="button"
            variant="outline"
            disabled={sendingCode || cooldown > 0}
            onClick={handleSendCode}
            className="shrink-0 tabular-nums"
          >
            {sendingCode ? (
              <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
            ) : cooldown > 0 ? (
              `${cooldown}s`
            ) : (
              "获取验证码"
            )}
          </Button>
        </div>
        {debugCode ? (
          <button
            type="button"
            onClick={() => setEmailCode(debugCode)}
            className="w-fit text-left font-mono text-xs text-muted-foreground underline-offset-4 hover:underline"
          >
            调试验证码：{debugCode}（点击填入）
          </button>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="reg-password">密码</Label>
        <Input
          id="reg-password"
          type="password"
          autoComplete="new-password"
          required
          minLength={8}
          maxLength={128}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="至少 8 位字符"
        />
      </div>

      <Button type="submit" disabled={submitting} className="mt-1 w-full">
        {submitting ? (
          <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
        ) : (
          <UserPlus className="size-4" />
        )}
        <span>注册并进入</span>
      </Button>

      <div className="text-center text-xs text-muted-foreground">
        已有账号？{" "}
        <Link
          href={
            redirect !== "/overview"
              ? `/login?redirect=${encodeURIComponent(redirect)}`
              : "/login"
          }
          className="font-medium text-foreground underline-offset-4 hover:underline"
        >
          返回登录
        </Link>
      </div>
    </form>
  );
}

export default function RegisterPage() {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-3">
          <Skeleton className="mx-auto h-5 w-24" />
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      }
    >
      <RegisterForm />
    </Suspense>
  );
}
