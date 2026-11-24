"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  BarChart3,
  Bell,
  BookOpen,
  Building2,
  Check,
  ChevronsUpDown,
  CreditCard,
  FolderKanban,
  KeyRound,
  LayoutDashboard,
  Loader2,
  LogOut,
  Moon,
  Plus,
  Settings,
  Sparkles,
  Sun,
  Terminal,
  User as UserIcon,
  Users,
  Wallet,
} from "lucide-react";
import { useTheme } from "next-themes";
import { toast } from "sonner";
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
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarRail,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Separator } from "@/components/ui/separator";
import { ProportionBar } from "@/components/common/proportion-bar";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { orgApi } from "@/lib/api/endpoints";
import { formatMicroUsd } from "@/lib/format/micro-usd";
import {
  canUsePlaygroundOrKeys,
  canViewWallet,
  ORG_ROLE_LABEL,
} from "@/lib/format/labels";
import { cn } from "@/lib/utils";

const PAGE_TITLES: Record<string, string> = {
  "/overview": "总览",
  "/playground": "Playground",
  "/projects": "项目",
  "/api-keys": "API 密钥",
  "/usage": "用量分析",
  "/billing": "账务与充值",
  "/alerts": "告警",
  "/team": "团队与设置",
  "/settings": "个人设置",
};

function resolvePageTitle(pathname: string): string {
  for (const [prefix, title] of Object.entries(PAGE_TITLES)) {
    if (pathname === prefix || pathname.startsWith(`${prefix}/`)) {
      return title;
    }
  }
  return "控制台";
}

function getInitials(name: string | undefined): string {
  if (!name) return "U";
  const trimmed = name.trim();
  return trimmed.slice(0, 2).toUpperCase();
}

export function ConsoleShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { resolvedTheme, setTheme } = useTheme();
  const { user, currentOrg, wallet, switchOrg, refreshProfile, logout } =
    useConsoleAuth();

  const [createOrgOpen, setCreateOrgOpen] = useState(false);
  const [orgName, setOrgName] = useState("");
  const [orgSlug, setOrgSlug] = useState("");
  const [creatingOrg, setCreatingOrg] = useState(false);
  const [createError, setCreateError] = useState<unknown>(null);

  const isPlayground = pathname.startsWith("/playground");
  const showWallet = canViewWallet(currentOrg?.role);
  const showDevTools = canUsePlaygroundOrKeys(currentOrg?.role);
  const pageTitle = resolvePageTitle(pathname);

  async function handleCreateOrg(e: FormEvent) {
    e.preventDefault();
    setCreateError(null);
    setCreatingOrg(true);
    try {
      const created = await orgApi.create({
        name: orgName.trim(),
        slug: orgSlug.trim().toLowerCase(),
      });
      await refreshProfile();
      switchOrg(created.id);
      setCreateOrgOpen(false);
      setOrgName("");
      setOrgSlug("");
      toast.success(`已创建工作空间「${created.name}」`);
    } catch (err) {
      setCreateError(err);
    } finally {
      setCreatingOrg(false);
    }
  }

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  const walletSegments = wallet
    ? [
        {
          key: "cash",
          label: "现金",
          value: Math.max(0, wallet.cashBalanceMicros),
          displayValue: formatMicroUsd(wallet.cashBalanceMicros),
          colorClass: "bg-[var(--chart-2)]",
        },
        {
          key: "bonus",
          label: "赠金",
          value: Math.max(0, wallet.bonusBalanceMicros),
          displayValue: formatMicroUsd(wallet.bonusBalanceMicros),
          colorClass: "bg-[var(--chart-3)]",
        },
        {
          key: "held",
          label: "在途预占",
          value: Math.max(0, wallet.heldMicros),
          displayValue: formatMicroUsd(wallet.heldMicros),
          colorClass: "bg-[var(--chart-4)]",
        },
      ]
    : [];

  return (
    <SidebarProvider>
      <Sidebar collapsible="icon">
        <SidebarHeader>
          <SidebarMenu>
            <SidebarMenuItem>
              <DropdownMenu>
                <DropdownMenuTrigger
                  render={
                    <SidebarMenuButton
                      size="lg"
                      tooltip={currentOrg?.orgName ?? "选择工作空间"}
                    />
                  }
                >
                  <div className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
                    {currentOrg?.personal ? (
                      <UserIcon className="size-3.5" />
                    ) : (
                      <Building2 className="size-3.5" />
                    )}
                  </div>
                  <div className="grid flex-1 text-left text-xs leading-tight">
                    <span className="truncate font-semibold">
                      {currentOrg?.orgName ?? "工作空间"}
                    </span>
                    <span className="truncate text-[11px] text-muted-foreground">
                      {currentOrg ? ORG_ROLE_LABEL[currentOrg.role] : ""}
                    </span>
                  </div>
                  <ChevronsUpDown className="ml-auto size-3.5 text-muted-foreground" />
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start" sideOffset={4} className="w-60">
                  <DropdownMenuGroup>
                    <DropdownMenuLabel>切换工作空间</DropdownMenuLabel>
                    {(user?.organizations ?? []).map((org) => (
                      <DropdownMenuItem
                        key={org.orgId}
                        onClick={() => switchOrg(org.orgId)}
                      >
                        <div className="flex flex-1 flex-col truncate">
                          <span className="truncate font-medium">{org.orgName}</span>
                          <span className="text-[11px] text-muted-foreground">
                            {ORG_ROLE_LABEL[org.role]}
                          </span>
                        </div>
                        {currentOrg?.orgId === org.orgId ? (
                          <Check className="size-3.5 text-foreground" />
                        ) : null}
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuGroup>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => setCreateOrgOpen(true)}>
                    <Plus className="size-3.5" />
                    <span>新建组织</span>
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarHeader>

        <SidebarContent>
          <SidebarGroup>
            <SidebarGroupLabel>工作台</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname === "/overview"}
                    tooltip="总览"
                    render={<Link href="/overview" />}
                  >
                    <LayoutDashboard />
                    <span>总览</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
                {showDevTools ? (
                  <SidebarMenuItem>
                    <SidebarMenuButton
                      isActive={pathname.startsWith("/playground")}
                      tooltip="Playground"
                      render={<Link href="/playground" />}
                    >
                      <Terminal />
                      <span>Playground</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ) : null}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>

          <SidebarGroup>
            <SidebarGroupLabel>接入管理</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/projects")}
                    tooltip="项目"
                    render={<Link href="/projects" />}
                  >
                    <FolderKanban />
                    <span>项目</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
                {showDevTools ? (
                  <SidebarMenuItem>
                    <SidebarMenuButton
                      isActive={pathname.startsWith("/api-keys")}
                      tooltip="API 密钥"
                      render={<Link href="/api-keys" />}
                    >
                      <KeyRound />
                      <span>API 密钥</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ) : null}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>

          <SidebarGroup>
            <SidebarGroupLabel>监控与账务</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/usage")}
                    tooltip="用量分析"
                    render={<Link href="/usage" />}
                  >
                    <BarChart3 />
                    <span>用量分析</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
                {showWallet ? (
                  <SidebarMenuItem>
                    <SidebarMenuButton
                      isActive={pathname.startsWith("/billing")}
                      tooltip="账务与充值"
                      render={<Link href="/billing" />}
                    >
                      <CreditCard />
                      <span>账务与充值</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ) : null}
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/alerts")}
                    tooltip="告警"
                    render={<Link href="/alerts" />}
                  >
                    <Bell />
                    <span>告警</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>

          <SidebarGroup>
            <SidebarGroupLabel>组织</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuItem>
                  <SidebarMenuButton
                    isActive={pathname.startsWith("/team")}
                    tooltip="团队与设置"
                    render={<Link href="/team" />}
                  >
                    <Users />
                    <span>团队与设置</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        </SidebarContent>

        <SidebarFooter>
          {showWallet && wallet ? (
            <Link
              href="/billing"
              className="flex flex-col gap-1.5 rounded-lg border border-sidebar-border bg-background/70 px-2.5 py-2 transition-colors motion-reduce:transition-none hover:bg-sidebar-accent group-data-[collapsible=icon]:hidden"
            >
              <div className="flex items-center justify-between text-xs">
                <span className="flex items-center gap-1 text-muted-foreground">
                  <Wallet className="size-3" />
                  可用余额
                </span>
                <span className="font-mono font-medium tabular-nums">
                  {formatMicroUsd(wallet.availableMicros)}
                </span>
              </div>
              <ProportionBar segments={walletSegments} heightClass="h-1.5" />
            </Link>
          ) : null}

          <SidebarMenu>
            <SidebarMenuItem>
              <DropdownMenu>
                <DropdownMenuTrigger
                  render={
                    <SidebarMenuButton
                      size="lg"
                      tooltip={user?.displayName ?? "账号"}
                    />
                  }
                >
                  <Avatar className="size-7 rounded-lg">
                    <AvatarFallback className="rounded-lg text-xs font-medium">
                      {getInitials(user?.displayName)}
                    </AvatarFallback>
                  </Avatar>
                  <div className="grid flex-1 text-left text-xs leading-tight">
                    <span className="truncate font-medium">
                      {user?.displayName ?? "用户"}
                    </span>
                    <span className="truncate text-[11px] text-muted-foreground">
                      {user?.email ?? ""}
                    </span>
                  </div>
                  <ChevronsUpDown className="ml-auto size-3.5 text-muted-foreground" />
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" side="top" className="w-56">
                  <DropdownMenuGroup>
                    <DropdownMenuItem render={<Link href="/settings" />}>
                      <Settings className="size-3.5" />
                      <span>个人设置</span>
                    </DropdownMenuItem>
                    <DropdownMenuItem render={<Link href="/models" />}>
                      <Sparkles className="size-3.5" />
                      <span>模型广场</span>
                    </DropdownMenuItem>
                    <DropdownMenuItem render={<Link href="/docs" />}>
                      <BookOpen className="size-3.5" />
                      <span>接入文档</span>
                    </DropdownMenuItem>
                  </DropdownMenuGroup>
                  <DropdownMenuSeparator />
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

      <SidebarInset
        className={cn(
          isPlayground
            ? "flex h-svh min-w-0 flex-col overflow-hidden"
            : "flex min-h-svh min-w-0 flex-col",
        )}
      >
        <header className="flex h-12 shrink-0 items-center justify-between border-b border-border bg-background/90 px-4 backdrop-blur-sm">
          <div className="flex min-w-0 items-center gap-2">
            <SidebarTrigger />
            <Separator orientation="vertical" className="h-4" />
            <span className="truncate text-xs text-muted-foreground">
              {currentOrg?.orgName ?? "工作空间"}
            </span>
            <span className="text-xs text-muted-foreground/60">/</span>
            <span className="truncate text-sm font-medium">{pageTitle}</span>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="xs"
              render={<Link href="/models" />}
              className="hidden text-muted-foreground sm:inline-flex"
            >
              模型广场
            </Button>
            <Button
              variant="ghost"
              size="xs"
              render={<Link href="/docs" />}
              className="hidden text-muted-foreground sm:inline-flex"
            >
              文档
            </Button>
          </div>
        </header>

        {isPlayground ? (
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
            {children}
          </div>
        ) : (
          <div className="mx-auto w-full max-w-7xl flex-1 px-4 py-6 sm:px-6">
            {children}
          </div>
        )}
      </SidebarInset>

      {/* Create Organization Dialog */}
      <Dialog open={createOrgOpen} onOpenChange={setCreateOrgOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>新建组织</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreateOrg} className="flex flex-col gap-4">
            <ApiErrorAlert error={createError} />
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="new-org-name">组织名称</Label>
              <Input
                id="new-org-name"
                required
                minLength={2}
                maxLength={120}
                value={orgName}
                onChange={(e) => {
                  const val = e.target.value;
                  setOrgName(val);
                  if (!orgSlug) {
                    const suggested = val
                      .toLowerCase()
                      .replace(/[^a-z0-9]+/g, "-")
                      .replace(/^-+|-+$/g, "");
                    if (suggested.length >= 3) setOrgSlug(suggested);
                  }
                }}
                placeholder="Acme AI"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="new-org-slug">唯一标识</Label>
              <Input
                id="new-org-slug"
                required
                pattern="^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$"
                value={orgSlug}
                onChange={(e) => setOrgSlug(e.target.value)}
                placeholder="acme-ai"
                className="font-mono text-xs"
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setCreateOrgOpen(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={creatingOrg}>
                {creatingOrg ? (
                  <Loader2 className="size-3.5 animate-spin motion-reduce:animate-none" />
                ) : null}
                <span>创建</span>
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </SidebarProvider>
  );
}
