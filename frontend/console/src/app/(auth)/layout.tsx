import type { ReactNode } from "react";
import Link from "next/link";
import { Zap } from "lucide-react";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center bg-background px-4 py-10">
      <div className="flex w-full max-w-[380px] flex-col gap-6">
        <Link
          href="/"
          className="mx-auto flex items-center gap-2 text-sm font-semibold tracking-tight"
        >
          <span className="flex size-7 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <Zap className="size-3.5" />
          </span>
          <span>HyperLLM</span>
        </Link>

        <div className="squircle-lg rounded-2xl border border-border bg-card p-6">
          {children}
        </div>
      </div>
    </div>
  );
}
