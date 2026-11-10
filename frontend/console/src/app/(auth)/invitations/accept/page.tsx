"use client";

import { Suspense, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Check, Loader2, LogIn, Users } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { orgApi } from "@/lib/api/endpoints";

function AcceptInvitationForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const initialToken = searchParams.get("token") ?? "";
  const { status, refreshProfile, switchOrg } = useConsoleAuth();

  const [token, setToken] = useState(initialToken);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const returnPath = token
    ? `/invitations/accept?token=${encodeURIComponent(token)}`
    : "/invitations/accept";

  async function handleAccept(e: FormEvent) {
    e.preventDefault();
    if (!token.trim()) return;
    setError(null);
    setSubmitting(true);
    try {
      const org = await orgApi.acceptInvitation(token.trim());
      await refreshProfile();
      switchOrg(org.id);
      toast.success(`已加入「${org.name}」`);
      router.replace("/overview");
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  }

  if (status === "loading") {
    return (
      <div className="flex flex-col gap-3">
        <Skeleton className="h-5 w-32" />
        <Skeleton className="h-9 w-full" />
      </div>
    );
  }

  if (status === "unauthenticated") {
    return (
      <div className="flex flex-col items-center gap-4 text-center">
        <div className="flex size-10 items-center justify-center rounded-xl bg-muted text-foreground">
          <Users className="size-4" />
        </div>
        <h1 className="text-base font-semibold">加入团队工作空间</h1>
        <div className="flex w-full flex-col gap-2">
          <Button
            render={
              <Link href={`/login?redirect=${encodeURIComponent(returnPath)}`} />
            }
          >
            <LogIn className="size-4" />
            <span>登录后接受邀请</span>
          </Button>
          <Button
            variant="outline"
            render={
              <Link
                href={`/register?redirect=${encodeURIComponent(returnPath)}`}
              />
            }
          >
            注册新账号
          </Button>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={handleAccept} className="flex flex-col gap-4">
      <div className="flex flex-col items-center gap-2 text-center">
        <div className="flex size-10 items-center justify-center rounded-xl bg-muted text-foreground">
          <Users className="size-4" />
        </div>
        <h1 className="text-base font-semibold">接受组织邀请</h1>
      </div>

      <ApiErrorAlert error={error} />

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="invite-token">邀请令牌</Label>
        <Input
          id="invite-token"
          required
          value={token}
          onChange={(e) => setToken(e.target.value)}
          placeholder="粘贴邀请令牌"
          className="font-mono text-xs"
        />
      </div>

      <Button type="submit" disabled={submitting} className="w-full">
        {submitting ? (
          <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
        ) : (
          <Check className="size-4" />
        )}
        <span>确认加入</span>
      </Button>
    </form>
  );
}

export default function AcceptInvitationPage() {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col gap-3">
          <Skeleton className="h-5 w-32" />
          <Skeleton className="h-9 w-full" />
        </div>
      }
    >
      <AcceptInvitationForm />
    </Suspense>
  );
}
