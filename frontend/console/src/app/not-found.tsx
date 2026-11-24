import Link from "next/link";
import { Compass, Home } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function ConsoleNotFound() {
  return (
    <div className="flex min-h-[75vh] flex-1 items-center justify-center p-6">
      <div className="flex w-full max-w-md flex-col items-center gap-4 rounded-xl border border-border bg-card p-6 text-center shadow-sm">
        <span className="rounded-md bg-muted px-2.5 py-1 font-mono text-xs font-semibold text-muted-foreground">
          404
        </span>
        <div className="flex flex-col gap-1">
          <h1 className="text-base font-semibold tracking-tight">
            未找到请求的页面
          </h1>
          <p className="text-sm text-muted-foreground">
            链接可能已失效或地址拼写有误，请返回首页或浏览可用模型目录。
          </p>
        </div>
        <div className="flex items-center gap-2 pt-1">
          <Button size="sm" render={<Link href="/" />}>
            <Home className="size-3.5" />
            <span>返回首页</span>
          </Button>
          <Button size="sm" variant="outline" render={<Link href="/models" />}>
            <Compass className="size-3.5" />
            <span>模型广场</span>
          </Button>
        </div>
      </div>
    </div>
  );
}
