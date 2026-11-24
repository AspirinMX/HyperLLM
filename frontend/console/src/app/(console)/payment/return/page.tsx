"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ArrowLeft, CheckCircle2, Clock, XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { billingApi } from "@/lib/api/endpoints";
import type { PaymentOrderDto } from "@/lib/api/types";
import {
  FULFILLMENT_STATUS_LABEL,
  ORDER_STATUS_LABEL,
} from "@/lib/format/labels";

function isTerminalOrder(order: PaymentOrderDto): boolean {
  return (
    order.fulfillmentStatus === "APPLIED" ||
    order.paymentStatus === "FAILED" ||
    order.paymentStatus === "CLOSED" ||
    order.paymentStatus === "REFUNDED"
  );
}

function PaymentReturnContent() {
  const searchParams = useSearchParams();
  const orderId = searchParams.get("orderId") ?? searchParams.get("order_id");
  const { currentOrg, refreshWallet } = useConsoleAuth();
  const orgId = currentOrg?.orgId ?? null;

  const [order, setOrder] = useState<PaymentOrderDto | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (!orgId || !orderId) return;
    let active = true;
    let timer: number | null = null;
    let attempts = 0;
    const MAX_ATTEMPTS = 60; // stop after ~3 minutes even if webhook never arrives

    const stopPolling = () => {
      if (timer !== null) {
        window.clearInterval(timer);
        timer = null;
      }
    };

    const poll = () => {
      if (!active) return;
      attempts += 1;
      if (attempts > MAX_ATTEMPTS) {
        stopPolling();
        return;
      }
      billingApi
        .getPaymentOrder(orgId, orderId)
        .then((res) => {
          if (!active) return;
          setError(null);
          setOrder(res);
          if (isTerminalOrder(res)) {
            stopPolling();
            if (res.fulfillmentStatus === "APPLIED") {
              void refreshWallet();
            }
          }
        })
        .catch((err) => {
          if (!active) return;
          setError(err);
          stopPolling();
        });
    };

    poll();
    timer = window.setInterval(poll, 3000);
    return () => {
      active = false;
      stopPolling();
    };
  }, [orgId, orderId, refreshWallet]);

  const applied = order?.fulfillmentStatus === "APPLIED";
  const failed =
    order?.paymentStatus === "FAILED" || order?.paymentStatus === "CLOSED";

  return (
    <div className="mx-auto flex max-w-md flex-col items-center gap-4 py-16 text-center">
      <div className="flex size-12 items-center justify-center rounded-2xl bg-muted">
        {applied ? (
          <CheckCircle2 className="size-6 text-emerald-500" />
        ) : failed ? (
          <XCircle className="size-6 text-destructive" />
        ) : (
          <Clock className="size-6 text-amber-500" />
        )}
      </div>
      <h1 className="text-lg font-semibold">
        {!orderId
          ? "缺少订单编号"
          : applied
            ? "支付已完成并入账"
            : failed
              ? "支付未完成"
              : "正在确认支付结果"}
      </h1>
      {order ? (
        <p className="font-mono text-xs text-muted-foreground">
          {order.orderNo} · {ORDER_STATUS_LABEL[order.paymentStatus]} /{" "}
          {FULFILLMENT_STATUS_LABEL[order.fulfillmentStatus]}
        </p>
      ) : null}
      {error ? <ApiErrorAlert error={error} /> : null}
      <Button variant="outline" size="sm" render={<Link href="/billing" />}>
        <ArrowLeft className="size-3.5" />
        <span>返回账务中心</span>
      </Button>
    </div>
  );
}

export default function PaymentReturnPage() {
  return (
    <Suspense
      fallback={
        <div className="mx-auto max-w-md py-16">
          <Skeleton className="h-32 w-full" />
        </div>
      }
    >
      <PaymentReturnContent />
    </Suspense>
  );
}
