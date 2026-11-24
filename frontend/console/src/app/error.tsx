"use client";

import { useEffect } from "react";
import Link from "next/link";
import { AlertTriangle, Home, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function ConsoleError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("Console route error:", error);
  }, [error]);

  return (
    <div className="flex min-h-[70vh] flex-1 items-center justify-center p-6">
      <div className="flex w-full max-w-md flex-col items-center gap-4 rounded-xl border border-border bg-card p-6 text-center shadow-sm">
        <div className="flex size-11 items-center justify-center rounded-xl bg-destructive/10 text-destructive">
          <AlertTriangle className="size-5" />
        </div>
        <div className="flex flex-col gap-1">
          <h1 className="text-base font-semibold tracking-tight">
            页面加载遇到异常
          </h1>
          <p className="text-sm text-muted-foreground">
            {error.message || "发生未预期的运行时错误，请重试或返回首页。"}
          </p>
          {error.digest ? (
            <code className="mt-1 font-mono text-[11px] text-muted-foreground">
              错误编号：{error.digest}
            </code>
          ) : null}
        </div>
        <div className="flex items-center gap-2 pt-1">
          <Button size="sm" onClick={reset}>
            <RotateCcw className="size-3.5" />
            <span>重新尝试</span>
          </Button>
          <Button size="sm" variant="outline" render={<Link href="/" />}>
            <Home className="size-3.5" />
            <span>返回首页</span>
          </Button>
        </div>
      </div>
    </div>
  );
}
