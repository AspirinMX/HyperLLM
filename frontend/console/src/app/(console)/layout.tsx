import { Suspense, type ReactNode } from "react";
import { ConsoleAuthGuard } from "@/components/providers/console-auth-guard";
import { ConsoleShell } from "@/components/layout/console-shell";
import { Skeleton } from "@/components/ui/skeleton";

export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return (
    <Suspense
      fallback={
        <div className="flex h-svh w-full items-center justify-center bg-background p-6">
          <div className="flex w-full max-w-sm flex-col gap-3">
            <Skeleton className="h-5 w-32" />
            <Skeleton className="h-32 w-full" />
          </div>
        </div>
      }
    >
      <ConsoleAuthGuard>
        <ConsoleShell>{children}</ConsoleShell>
      </ConsoleAuthGuard>
    </Suspense>
  );
}
