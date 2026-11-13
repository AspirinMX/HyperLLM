import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

interface EmptyStateProps {
  icon: ReactNode;
  title: string;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({ icon, title, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-3 py-12 text-center",
        className,
      )}
    >
      <div className="flex size-10 items-center justify-center rounded-xl bg-muted text-muted-foreground">
        {icon}
      </div>
      <p className="text-sm font-medium text-muted-foreground">{title}</p>
      {action ? <div>{action}</div> : null}
    </div>
  );
}
