"use client";

import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface CopyButtonProps {
  value: string;
  label?: string;
  copiedToast?: string;
  variant?: "ghost" | "outline" | "secondary" | "default";
  size?: "xs" | "sm" | "default" | "icon-xs" | "icon-sm" | "icon";
  className?: string;
}

export function CopyButton({
  value,
  label,
  copiedToast,
  variant = "ghost",
  size = label ? "xs" : "icon-xs",
  className,
}: CopyButtonProps) {
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      if (copiedToast) {
        toast.success(copiedToast);
      }
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      toast.error("复制失败");
    }
  }

  return (
    <Button
      type="button"
      variant={variant}
      size={size}
      onClick={handleCopy}
      className={cn(className)}
      aria-label={label ?? "复制"}
    >
      {copied ? (
        <Check className="size-3.5 text-emerald-600 dark:text-emerald-400" />
      ) : (
        <Copy className="size-3.5" />
      )}
      {label ? <span>{copied ? "已复制" : label}</span> : null}
    </Button>
  );
}
