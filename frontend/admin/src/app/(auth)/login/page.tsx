"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { QRCodeSVG } from "qrcode.react";
import { ArrowLeft, KeyRound, Loader2, LogIn, ShieldCheck } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  InputOTP,
  InputOTPGroup,
  InputOTPSlot,
} from "@/components/ui/input-otp";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { CopyButton } from "@/components/common/copy-button";
import { useAdminAuth } from "@/components/providers/admin-auth-provider";
import { adminAuthApi } from "@/lib/api/endpoints";

type LoginStep = "CREDENTIALS" | "MFA_SETUP" | "MFA_VERIFY";

function AdminLoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const redirect = searchParams.get("redirect") || "/overview";
  const { completeAuth } = useAdminAuth();

  const [step, setStep] = useState<LoginStep>("CREDENTIALS");
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [challengeToken, setChallengeToken] = useState("");
  const [setupSecret, setSetupSecret] = useState("");
  const [otpauthUri, setOtpauthUri] = useState("");
  const [totpCode, setTotpCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function handleCredentials(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const res = await adminAuthApi.login({
        login: loginId.trim(),
        password,
      });

      if (!res.mfaRequired && res.accessToken) {
        completeAuth(res);
        router.replace(redirect.startsWith("/") ? redirect : "/overview");
        return;
      }

      if (!res.challengeToken) {
        throw new Error("Missing challenge token");
      }
      setChallengeToken(res.challengeToken);

      if (res.mfaAction === "MFA_SETUP") {
        const setup = await adminAuthApi.initMfaSetup(res.challengeToken);
        setSetupSecret(setup.secret);
        setOtpauthUri(setup.otpauthUri);
        setTotpCode("");
        setStep("MFA_SETUP");
      } else {
        setTotpCode("");
        setStep("MFA_VERIFY");
      }
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  }

  async function submitTotp(codeValue: string) {
    if (codeValue.length !== 6 || submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      const res =
        step === "MFA_SETUP"
          ? await adminAuthApi.completeMfaSetup(
              challengeToken,
              setupSecret,
              codeValue,
            )
          : await adminAuthApi.verifyMfa(challengeToken, codeValue);

      completeAuth(res);
      if (step === "MFA_SETUP") {
        toast.success("双因素认证已绑定");
      }
      router.replace(redirect.startsWith("/") ? redirect : "/overview");
    } catch (err) {
      setError(err);
      setTotpCode("");
    } finally {
      setSubmitting(false);
    }
  }

  if (step === "CREDENTIALS") {
    return (
      <form onSubmit={handleCredentials} className="flex flex-col gap-4">
        <div className="text-center">
          <h1 className="text-base font-semibold tracking-tight">
            运营后台登录
          </h1>
        </div>

        <ApiErrorAlert error={error} />

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="admin-login">管理员账号 / 邮箱</Label>
          <Input
            id="admin-login"
            required
            autoComplete="username"
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
            placeholder="admin@hyperllm.dev"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="admin-password">密码</Label>
          <Input
            id="admin-password"
            type="password"
            required
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="输入密码"
          />
        </div>

        <Button type="submit" disabled={submitting} className="mt-1 w-full">
          {submitting ? (
            <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
          ) : (
            <LogIn className="size-4" />
          )}
          <span>继续</span>
        </Button>
      </form>
    );
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void submitTotp(totpCode);
      }}
      className="flex flex-col gap-4"
    >
      <div className="flex items-center justify-between">
        <Button
          type="button"
          variant="ghost"
          size="xs"
          onClick={() => {
            setStep("CREDENTIALS");
            setError(null);
            setTotpCode("");
          }}
        >
          <ArrowLeft className="size-3.5" />
          <span>返回</span>
        </Button>
        <span className="flex items-center gap-1.5 text-sm font-medium">
          <ShieldCheck className="size-4" />
          {step === "MFA_SETUP" ? "绑定验证器" : "输入动态验证码"}
        </span>
        <span className="w-12" />
      </div>

      <ApiErrorAlert error={error} />

      {step === "MFA_SETUP" && otpauthUri ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-border bg-muted/35 p-4">
          <div className="rounded-lg bg-white p-2.5">
            <QRCodeSVG value={otpauthUri} size={156} />
          </div>
          <div className="flex w-full items-center justify-between gap-2 rounded-lg bg-background px-2.5 py-1.5">
            <code className="truncate font-mono text-[11px] text-muted-foreground">
              {setupSecret}
            </code>
            <CopyButton value={setupSecret} copiedToast="已复制 TOTP 密钥" />
          </div>
        </div>
      ) : null}

      <div className="flex flex-col items-center gap-2 py-1">
        <Label className="text-xs text-muted-foreground">
          6 位动态验证码
        </Label>
        <InputOTP
          maxLength={6}
          value={totpCode}
          onChange={(v) => {
            setTotpCode(v);
            if (v.length === 6) {
              void submitTotp(v);
            }
          }}
        >
          <InputOTPGroup>
            <InputOTPSlot index={0} />
            <InputOTPSlot index={1} />
            <InputOTPSlot index={2} />
            <InputOTPSlot index={3} />
            <InputOTPSlot index={4} />
            <InputOTPSlot index={5} />
          </InputOTPGroup>
        </InputOTP>
      </div>

      <Button
        type="submit"
        disabled={submitting || totpCode.length !== 6}
        className="w-full"
      >
        {submitting ? (
          <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
        ) : (
          <KeyRound className="size-4" />
        )}
        <span>{step === "MFA_SETUP" ? "完成绑定并登录" : "验证登录"}</span>
      </Button>
    </form>
  );
}

export default function AdminLoginPage() {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-3">
          <Skeleton className="mx-auto h-5 w-28" />
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      }
    >
      <AdminLoginForm />
    </Suspense>
  );
}
