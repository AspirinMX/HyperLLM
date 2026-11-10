"use client";

import { useEffect, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useConsoleAuth } from "./console-auth-provider";
import { Skeleton } from "@/components/ui/skeleton";

export function ConsoleAuthGuard({ children }: { children: ReactNode }) {
  const { status } = useConsoleAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (status === "unauthenticated") {
      const next = pathname && pathname !== "/login" ? `?redirect=${encodeURIComponent(pathname)}` : "";
      router.replace(`/login${next}`);
    }
  }, [status, router, pathname]);

  if (status === "loading") {
    return (
      <div className="flex h-svh w-full items-center justify-center bg-background p-6">
        <div className="flex w-full max-w-sm flex-col gap-3">
          <Skeleton className="h-5 w-32" />
          <Skeleton className="h-32 w-full" />
        </div>
      </div>
    );
  }

  if (status === "unauthenticated") {
    return null;
  }

  return <>{children}</>;
}
