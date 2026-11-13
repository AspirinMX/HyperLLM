"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowRight, Building2, User as UserIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { EmptyState } from "@/components/common/empty-state";
import { ProportionBar } from "@/components/common/proportion-bar";
import { StatusDot } from "@/components/common/status-dot";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminTenantApi } from "@/lib/api/endpoints";
import type { OrgStatus } from "@/lib/api/types";
import { formatDateTime, formatMicroUsd } from "@/lib/format/micro-usd";
import { formatBpsPercent, ORG_STATUS_LABEL } from "@/lib/format/labels";

export default function AdminTenantsPage() {
  const [statusFilter, setStatusFilter] = useState<"ALL" | OrgStatus>("ALL");
  const [page, setPage] = useState(1);

  const state = useAsyncData(
    (signal) =>
      adminTenantApi.listOrgs(
        {
          status: statusFilter === "ALL" ? undefined : statusFilter,
          page,
          pageSize: 20,
        },
        signal,
      ),
    [statusFilter, page],
  );

  const totalPages = Math.max(1, Math.ceil((state.data?.total ?? 0) / 20));

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs
          value={statusFilter}
          onValueChange={(v) => {
            setStatusFilter(v as "ALL" | OrgStatus);
            setPage(1);
          }}
        >
          <TabsList>
            <TabsTrigger value="ALL">全部租户</TabsTrigger>
            <TabsTrigger value="ACTIVE">正常</TabsTrigger>
            <TabsTrigger value="SUSPENDED">已停用</TabsTrigger>
            <TabsTrigger value="CLOSED">已关闭</TabsTrigger>
          </TabsList>
        </Tabs>

        {state.data ? (
          <Badge variant="secondary" className="tabular-nums">
            共 {state.data.total} 个组织
          </Badge>
        ) : null}
      </div>

      <ApiErrorAlert error={state.error} onRetry={state.reload} />

      <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
        {state.loading ? (
          <div className="flex flex-col gap-3 p-5">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-11 w-full" />
            ))}
          </div>
        ) : (state.data?.items ?? []).length === 0 ? (
          <EmptyState
            icon={<Building2 className="size-4" />}
            title="暂无符合条件的租户"
          />
        ) : (
          <>
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/35 hover:bg-muted/35">
                  <TableHead>组织</TableHead>
                  <TableHead className="w-56">账本确认余额</TableHead>
                  <TableHead className="text-right">折扣 / 并发</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>创建时间</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(state.data?.items ?? []).map((org) => {
                  const cash = org.cashBalanceMicros ?? 0;
                  const bonus = org.bonusBalanceMicros ?? 0;
                  return (
                    <TableRow key={org.id}>
                      <TableCell>
                        <Link
                          href={`/tenants/${org.id}`}
                          className="flex items-center gap-2 hover:underline"
                        >
                          {org.personal ? (
                            <UserIcon className="size-3.5 text-muted-foreground" />
                          ) : (
                            <Building2 className="size-3.5 text-muted-foreground" />
                          )}
                          <div className="flex flex-col">
                            <span className="font-medium">{org.name}</span>
                            <span className="font-mono text-xs text-muted-foreground">
                              {org.slug}
                            </span>
                          </div>
                        </Link>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-col gap-1">
                          <div className="flex items-center justify-between font-mono text-xs tabular-nums">
                            <span>{formatMicroUsd(cash + bonus)}</span>
                            <span className="text-[11px] text-muted-foreground">
                              赠 {formatMicroUsd(bonus)}
                            </span>
                          </div>
                          <ProportionBar
                            heightClass="h-1.5"
                            segments={[
                              {
                                key: "cash",
                                label: "账本现金",
                                value: Math.max(0, cash),
                                displayValue: formatMicroUsd(cash),
                                colorClass: "bg-[var(--chart-2)]",
                              },
                              {
                                key: "bonus",
                                label: "账本赠金",
                                value: Math.max(0, bonus),
                                displayValue: formatMicroUsd(bonus),
                                colorClass: "bg-[var(--chart-3)]",
                              },
                            ]}
                          />
                        </div>
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        <div>{formatBpsPercent(org.discountRatioBps)}</div>
                        <div className="text-[11px] text-muted-foreground">
                          {org.maxConcurrency} 并发
                        </div>
                      </TableCell>
                      <TableCell>
                        <StatusDot
                          tone={
                            org.status === "ACTIVE"
                              ? "success"
                              : org.status === "SUSPENDED"
                                ? "danger"
                                : "muted"
                          }
                          label={ORG_STATUS_LABEL[org.status]}
                        />
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground tabular-nums">
                        {formatDateTime(org.createdAt)}
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          size="xs"
                          variant="ghost"
                          render={<Link href={`/tenants/${org.id}`} />}
                        >
                          <span>钱包与订单</span>
                          <ArrowRight className="size-3" />
                        </Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>

            {totalPages > 1 ? (
              <div className="flex items-center justify-between border-t border-border px-4 py-2.5 text-xs text-muted-foreground">
                <span className="tabular-nums">
                  第 {page} / {totalPages} 页
                </span>
                <div className="flex items-center gap-1.5">
                  <Button
                    size="xs"
                    variant="outline"
                    disabled={page <= 1}
                    onClick={() => setPage((p) => p - 1)}
                  >
                    上一页
                  </Button>
                  <Button
                    size="xs"
                    variant="outline"
                    disabled={page >= totalPages}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
}
