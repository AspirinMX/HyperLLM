"use client";

import { AlertCircle, RefreshCw } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { getErrorMessage, isApiErrorCode } from "@/lib/api/error";

interface ApiErrorAlertProps {
  error: unknown;
  onRetry?: () => void;
  className?: string;
}

export function ApiErrorAlert({ error, onRetry, className }: ApiErrorAlertProps) {
  if (!error) return null;

  const isConflict = isApiErrorCode(error, "CONFLICT_OPTIMISTIC_LOCK");
  const title = isConflict ? "数据已更新" : "请求未完成";
  const message = getErrorMessage(error);

  return (
    <Alert variant="destructive" className={className}>
      <AlertCircle className="size-4" />
      <AlertTitle className="flex items-center justify-between gap-2">
        <span>{title}</span>
        {onRetry ? (
          <Button
            type="button"
            variant="outline"
            size="xs"
            onClick={onRetry}
            className="border-destructive/30 text-destructive hover:bg-destructive/10"
          >
            <RefreshCw className="size-3" />
            {isConflict ? "刷新数据" : "重试"}
          </Button>
        ) : null}
      </AlertTitle>
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}
