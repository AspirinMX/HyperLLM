import type { ReactNode } from "react";
import { ConsoleAuthGuard } from "@/components/providers/console-auth-guard";
import { ConsoleShell } from "@/components/layout/console-shell";

export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return (
    <ConsoleAuthGuard>
      <ConsoleShell>{children}</ConsoleShell>
    </ConsoleAuthGuard>
  );
}
