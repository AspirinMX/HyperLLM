import { Suspense, type ReactNode } from "react";
import { AdminAuthGuard } from "@/components/providers/admin-auth-guard";
import { AdminShell } from "@/components/layout/admin-shell";
import { Skeleton } from "@/components/ui/skeleton";

export default function AdminWorkspaceLayout({
  children,
}: {
  children: ReactNode;
}) {
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
      <AdminAuthGuard>
        <AdminShell>{children}</AdminShell>
      </AdminAuthGuard>
    </Suspense>
  );
}
