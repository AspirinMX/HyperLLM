import { cn } from "@/lib/utils";

export type StatusTone = "success" | "warning" | "danger" | "info" | "muted";

const DOT_STYLES: Record<StatusTone, string> = {
  success: "bg-emerald-500",
  warning: "bg-amber-500",
  danger: "bg-rose-500",
  info: "bg-blue-500",
  muted: "bg-muted-foreground/45",
};

const TEXT_STYLES: Record<StatusTone, string> = {
  success: "text-foreground",
  warning: "text-amber-700 dark:text-amber-400",
  danger: "text-rose-700 dark:text-rose-400",
  info: "text-blue-700 dark:text-blue-400",
  muted: "text-muted-foreground",
};

interface StatusDotProps {
  tone: StatusTone;
  label: string;
  pulse?: boolean;
  className?: string;
}

export function StatusDot({ tone, label, pulse = false, className }: StatusDotProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 text-xs font-medium whitespace-nowrap",
        TEXT_STYLES[tone],
        className,
      )}
    >
      <span className="relative flex size-2 shrink-0">
        {pulse ? (
          <span
            className={cn(
              "absolute inline-flex size-full animate-ping rounded-full opacity-60 motion-reduce:animate-none",
              DOT_STYLES[tone],
            )}
          />
        ) : null}
        <span className={cn("relative inline-flex size-2 rounded-full", DOT_STYLES[tone])} />
      </span>
      <span>{label}</span>
    </span>
  );
}
