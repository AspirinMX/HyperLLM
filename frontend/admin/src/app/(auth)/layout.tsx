import type { ReactNode } from "react";
import { Shield } from "lucide-react";
import { Badge } from "@/components/ui/badge";

export default function AdminAuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center bg-background px-4 py-10">
      <div className="flex w-full max-w-[400px] flex-col gap-6">
        <div className="mx-auto flex items-center gap-2 text-sm font-semibold tracking-tight">
          <span className="flex size-7 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <Shield className="size-3.5" />
          </span>
          <span>HyperLLM</span>
          <Badge variant="outline" className="font-mono text-[10px]">
            ADMIN
          </Badge>
        </div>

        <div className="squircle-lg rounded-2xl border border-border bg-card p-6">
          {children}
        </div>
      </div>
    </div>
  );
}
