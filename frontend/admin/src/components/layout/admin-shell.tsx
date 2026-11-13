"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  Activity,
  BarChart3,
  Building2,
  ChevronsUpDown,
  Cpu,
  LogOut,
  Moon,
  Package,
  PlugZap,
  Shield,
  Sun,
} from "lucide-react";
import { useTheme } from "next-themes";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarRail,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { useAdminAuth } from "@/components/providers/admin-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminOperationsApi } from "@/lib/api/endpoints";
import { ADMIN_ROLE_LABEL } from "@/lib/format/labels";

const PAGE_TITLES: Record<string, string> = {
  "/overview": "经营与毛利大盘",
  "/channels": "渠道密钥池",
  "/models": "模型与路由",
  "/tenants": "租户与钱包",
  "/commerce": "资源包与代金券",
  "/operations": "对账与告警巡检",
};

function resolveTitle(pathname: string): string {
  for (const [prefix, title] of Object.entries(PAGE_TITLES)) {
    if (pathname === prefix || pathname.startsWith(`${prefix}/`)) {
      return title;
    }
  }
  return "运营后台";
}

export function AdminShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { resolvedTheme, setTheme } = useTheme();
  const { profile, logout } = useAdminAuth();

  const openAlertsCount = useAsyncData(
    async (signal) => {
      const [findings, anomalies] = await Promise.all([
        adminOperationsApi.listFindings("OPEN", 100, signal).catch(() => []),
        adminOperationsApi
          .listAnomalies({ status: "OPEN", page: 1, pageSize: 1 }, signal)
          .catch(() => ({ items: [], page: 1, pageSize: 1, total: 0 })),
      ]);
      return findings.length + anomalies.total;
    },
    [profile?.adminId],
    Boolean(profile),
  );

  const pendingOps = openAlertsCount.data ?? 0;

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  return (
    <SidebarProvider>
      <Sidebar collapsible="icon">
        <SidebarHeader>
          <SidebarMenu>
            <SidebarMenuItem>
              <SidebarMenuButton
                size="lg"
                render={<Link href="/overview" />}
                tooltip="HyperLLM 运营后台"
              >
                <div className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
                  <Shield className="size-3.5" />
                </div>
                <div className="flex flex-1 items-center justify-between gap-2 truncate">
                  <span className="font-semibold tracking-tight">HyperLLM</span>
                  <Badge variant="outline" className="h-4 px-1.5 font-mono text-[10px]">
                    ADMIN
                  </Badge>
                </div>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarHeader>

        <SidebarContent>
          <SidebarGroup>
            <SidebarGroupLabel>经营分析</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname === "/overview"}
                    tooltip="经营大盘"
                    render={<Link href="/overview" />}
                  >
                    <BarChart3 />
                    <span>经营大盘</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>

          <SidebarGroup>
            <SidebarGroupLabel>路由与供给</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/channels")}
                    tooltip="渠道密钥池"
                    render={<Link href="/channels" />}
                  >
                    <PlugZap />
                    <span>渠道密钥池</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/models")}
                    tooltip="模型与路由"
                    render={<Link href="/models" />}
                  >
                    <Cpu />
                    <span>模型与路由</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>

          <SidebarGroup>
            <SidebarGroupLabel>租户与商业化</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/tenants")}
                    tooltip="租户与钱包"
                    render={<Link href="/tenants" />}
                  >
                    <Building2 />
                    <span>租户与钱包</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/commerce")}
                    tooltip="商品与券码"
                    render={<Link href="/commerce" />}
                  >
                    <Package />
                    <span>商品与券码</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>

          <SidebarGroup>
            <SidebarGroupLabel>财务与风控</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/operations")}
                    tooltip="运维与对账"
                    render={<Link href="/operations" />}
                  >
                    <Activity />
                    <span>运维与对账</span>
                  </SidebarMenuButton>
                  {pendingOps > 0 ? (
                    <SidebarMenuBadge className="bg-destructive/15 text-destructive">
                      {pendingOps}
                    </SidebarMenuBadge>
                  ) : null}
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        </SidebarContent>

        <SidebarFooter>
          <SidebarMenu>
            <SidebarMenuItem>
              <DropdownMenu>
                <DropdownMenuTrigger
                  render={
                    <SidebarMenuButton
                      size="lg"
                      tooltip={profile?.username ?? "管理员"}
                    />
                  }
                >
                  <Avatar className="size-7 rounded-lg">
                    <AvatarFallback className="rounded-lg text-xs font-medium">
                      {(profile?.username ?? "AD").slice(0, 2).toUpperCase()}
                    </AvatarFallback>
                  </Avatar>
                  <div className="grid flex-1 text-left text-xs leading-tight">
                    <span className="truncate font-medium">
                      {profile?.username ?? "管理员"}
                    </span>
                    <span className="truncate text-[11px] text-muted-foreground">
                      {profile ? ADMIN_ROLE_LABEL[profile.role] : ""}
                    </span>
                  </div>
                  <ChevronsUpDown className="ml-auto size-3.5 text-muted-foreground" />
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" side="top" className="w-52">
                  <DropdownMenuItem
                    onClick={() =>
                      setTheme(resolvedTheme === "dark" ? "light" : "dark")
                    }
                  >
                    {resolvedTheme === "dark" ? (
                      <Sun className="size-3.5" />
                    ) : (
                      <Moon className="size-3.5" />
                    )}
                    <span>{resolvedTheme === "dark" ? "浅色外观" : "深色外观"}</span>
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem variant="destructive" onClick={handleLogout}>
                    <LogOut className="size-3.5" />
                    <span>退出登录</span>
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarFooter>
        <SidebarRail />
      </Sidebar>

      <SidebarInset className="flex min-h-svh min-w-0 flex-col">
        <header className="flex h-12 shrink-0 items-center justify-between border-b border-border bg-background/90 px-4 backdrop-blur-sm">
          <div className="flex min-w-0 items-center gap-2">
            <SidebarTrigger />
            <Separator orientation="vertical" className="h-4" />
            <span className="truncate text-sm font-medium">
              {resolveTitle(pathname)}
            </span>
          </div>
          {profile ? (
            <Badge variant="secondary" className="text-[11px]">
              {ADMIN_ROLE_LABEL[profile.role]}
            </Badge>
          ) : null}
        </header>

        <div className="mx-auto w-full max-w-7xl flex-1 px-4 py-6 sm:px-6">
          {children}
        </div>
      </SidebarInset>
    </SidebarProvider>
  );
}
