import { cn } from "@/lib/utils";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";

export interface ProportionSegment {
  key: string;
  label: string;
  value: number;
  displayValue?: string;
  colorClass: string;
}

interface ProportionBarProps {
  segments: ProportionSegment[];
  total?: number;
  markerRatio?: number | null;
  markerLabel?: string;
  heightClass?: string;
  className?: string;
}

export function ProportionBar({
  segments,
  total,
  markerRatio,
  markerLabel,
  heightClass = "h-2",
  className,
}: ProportionBarProps) {
  const sum = segments.reduce((acc, s) => acc + Math.max(0, s.value), 0);
  const denom = total && total > 0 ? total : sum;

  return (
    <div
      className={cn(
        "relative flex w-full overflow-hidden rounded-full bg-muted",
        heightClass,
        className,
      )}
    >
      {denom > 0
        ? segments.map((seg) => {
            const pct = Math.min(100, Math.max(0, (Math.max(0, seg.value) / denom) * 100));
            if (pct <= 0) return null;
            return (
              <Tooltip key={seg.key}>
                <TooltipTrigger
                  render={
                    <div
                      style={{ width: `${pct}%` }}
                      className={cn(
                        "h-full transition-[width] duration-300 motion-reduce:transition-none first:rounded-l-full last:rounded-r-full",
                        seg.colorClass,
                      )}
                    />
                  }
                />
                <TooltipContent>
                  <span className="tabular-nums">
                    {seg.label}：{seg.displayValue ?? `${pct.toFixed(1)}%`}
                  </span>
                </TooltipContent>
              </Tooltip>
            );
          })
        : null}

      {markerRatio !== null &&
      markerRatio !== undefined &&
      markerRatio > 0 &&
      markerRatio < 1 ? (
        <Tooltip>
          <TooltipTrigger
            render={
              <div
                style={{ left: `${markerRatio * 100}%` }}
                className="absolute inset-y-0 z-10 w-0.5 bg-amber-500/90"
              />
            }
          />
          {markerLabel ? <TooltipContent>{markerLabel}</TooltipContent> : null}
        </Tooltip>
      ) : null}
    </div>
  );
}
