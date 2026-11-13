import { Suspense, type ReactNode } from "react";
import { PublicShell } from "@/components/layout/public-shell";

export default function PublicLayout({ children }: { children: ReactNode }) {
  return (
    <Suspense fallback={<div className="min-h-svh bg-background" />}>
      <PublicShell>{children}</PublicShell>
    </Suspense>
  );
}
