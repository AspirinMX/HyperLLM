"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ArrowRight, Moon, Sun, Zap } from "lucide-react";
import { useTheme } from "next-themes";
import { Button } from "@/components/ui/button";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { cn } from "@/lib/utils";

const NAV_ITEMS = [
  { href: "/", label: "首页" },
  { href: "/models", label: "模型广场" },
  { href: "/docs", label: "接入文档" },
] as const;

export function PublicShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { status } = useConsoleAuth();
  const { resolvedTheme, setTheme } = useTheme();

  return (
    <div className="flex min-h-svh flex-col bg-background text-foreground">
      <header className="sticky top-0 z-30 h-14 border-b border-border bg-background/85 backdrop-blur-md">
        <div className="mx-auto flex h-full max-w-6xl items-center justify-between px-4 sm:px-6">
          <div className="flex items-center gap-6">
            <Link
              href="/"
              className="flex items-center gap-2 text-sm font-semibold tracking-tight"
            >
              <span className="flex size-7 items-center justify-center rounded-lg bg-primary text-primary-foreground">
                <Zap className="size-3.5" />
              </span>
              <span>HyperLLM</span>
            </Link>

            <nav className="hidden items-center gap-1 sm:flex">
              {NAV_ITEMS.map((item) => {
                const active =
                  item.href === "/"
                    ? pathname === "/"
                    : pathname.startsWith(item.href);
                return (
                  <Button
                    key={item.href}
                    variant="ghost"
                    size="sm"
                    className={cn(
                      active
                        ? "bg-muted text-foreground"
                        : "text-muted-foreground",
                    )}
                    render={<Link href={item.href} />}
                  >
                    {item.label}
                  </Button>
                );
              })}
            </nav>
          </div>

          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label="切换主题"
              onClick={() =>
                setTheme(resolvedTheme === "dark" ? "light" : "dark")
              }
            >
              <Sun className="size-4 scale-100 rotate-0 transition-transform motion-reduce:transition-none dark:scale-0 dark:-rotate-90" />
              <Moon className="absolute size-4 scale-0 rotate-90 transition-transform motion-reduce:transition-none dark:scale-100 dark:rotate-0" />
            </Button>

            {status === "authenticated" ? (
              <Button size="sm" render={<Link href="/overview" />}>
                <span>进入控制台</span>
                <ArrowRight className="size-3.5" />
              </Button>
            ) : (
              <>
                <Button variant="ghost" size="sm" render={<Link href="/login" />}>
                  登录
                </Button>
                <Button size="sm" render={<Link href="/register" />}>
                  <span>开始使用</span>
                  <ArrowRight className="size-3.5" />
                </Button>
              </>
            )}
          </div>
        </div>
      </header>

      <main className="flex-1">{children}</main>

      <footer className="border-t border-border py-6">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 text-xs text-muted-foreground sm:px-6">
          <div className="flex items-center gap-2">
            <span className="font-medium text-foreground">HyperLLM</span>
            <span>·</span>
            <span>企业级多协议 LLM API 网关</span>
          </div>
          <div className="flex items-center gap-4">
            <Link href="/models" className="hover:text-foreground">
              模型定价
            </Link>
            <Link href="/docs" className="hover:text-foreground">
              接口文档
            </Link>
            <Link href="/overview" className="hover:text-foreground">
              控制台
            </Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
