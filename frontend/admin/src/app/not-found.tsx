import Link from "next/link";
import { LayoutDashboard, LogIn } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function AdminNotFound() {
  return (
    <div className="flex min-h-[75vh] flex-1 items-center justify-center p-6">
      <div className="flex w-full max-w-md flex-col items-center gap-4 rounded-xl border border-border bg-card p-6 text-center shadow-sm">
        <span className="rounded-md bg-muted px-2.5 py-1 font-mono text-xs font-semibold text-muted-foreground">
          404
        </span>
        <div className="flex flex-col gap-1">
          <h1 className="text-base font-semibold tracking-tight">
            未找到运营后台页面
          </h1>
          <p className="text-sm text-muted-foreground">
            请检查路径是否正确，或返回运营总览重新导航。
          </p>
        </div>
        <div className="flex items-center gap-2 pt-1">
          <Button size="sm" render={<Link href="/overview" />}>
            <LayoutDashboard className="size-3.5" />
            <span>返回总览</span>
          </Button>
          <Button size="sm" variant="outline" render={<Link href="/login" />}>
            <LogIn className="size-3.5" />
            <span>管理员登录</span>
          </Button>
        </div>
      </div>
    </div>
  );
}
