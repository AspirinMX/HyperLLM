"use client";

import Link from "next/link";
import {
  ArrowRight,
  Brain,
  Database,
  Eye,
  Lock,
  ShieldCheck,
  Sparkles,
  Wrench,
  Zap,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { CopyButton } from "@/components/common/copy-button";
import { useConsoleAuth } from "@/components/providers/console-auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { catalogApi } from "@/lib/api/endpoints";
import { isApiErrorCode } from "@/lib/api/error";
import {
  formatCompactNumber,
  formatPerMillionUsd,
} from "@/lib/format/micro-usd";
import { PROTOCOL_LABEL } from "@/lib/format/labels";
import { GATEWAY_PUBLIC_BASE_URL } from "@/lib/config";

const SNIPPETS = {
  openai: `import OpenAI from "openai";

const client = new OpenAI({
  baseURL: "${GATEWAY_PUBLIC_BASE_URL}/v1",
  apiKey: process.env.HYPERLLM_API_KEY,
});

const res = await client.chat.completions.create({
  model: "gpt-4o",
  messages: [{ role: "user", content: "你好" }],
  stream: true,
});`,
  responses: `import OpenAI from "openai";

const client = new OpenAI({
  baseURL: "${GATEWAY_PUBLIC_BASE_URL}/v1",
  apiKey: process.env.HYPERLLM_API_KEY,
});

const res = await client.responses.create({
  model: "claude-sonnet-4-5",
  input: "用三句话总结分布式事务 Outbox 模式",
  stream: true,
});`,
  anthropic: `import Anthropic from "@anthropic-ai/sdk";

const client = new Anthropic({
  baseURL: "${GATEWAY_PUBLIC_BASE_URL}",
  apiKey: process.env.HYPERLLM_API_KEY,
});

const msg = await client.messages.create({
  model: "claude-sonnet-4-5",
  max_tokens: 1024,
  messages: [{ role: "user", content: "你好" }],
});`,
  gemini: `curl "${GATEWAY_PUBLIC_BASE_URL}/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse" \\
  -H "x-goog-api-key: $HYPERLLM_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "contents": [{"role": "user", "parts": [{"text": "你好"}]}]
  }'`,
} as const;

export default function LandingPage() {
  const { status } = useConsoleAuth();
  const isAuthed = status === "authenticated";

  const { data: models, loading, error } = useAsyncData(
    (signal) => catalogApi.listModels({ auth: isAuthed, signal }),
    [isAuthed],
    status !== "loading",
  );

  const featuredModels = (models ?? []).slice(0, 6);
  const maxOutputPrice = Math.max(
    1,
    ...featuredModels.map((m) => m.pricing?.outputPriceMicros1m ?? 0),
  );
  const needsAuthForCatalog =
    isApiErrorCode(error, "AUTH_INVALID_CREDENTIALS") ||
    isApiErrorCode(error, "AUTH_TOKEN_EXPIRED");

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-12 px-4 py-12 sm:px-6 sm:py-16">
      {/* Split Hero */}
      <section className="grid items-center gap-8 lg:grid-cols-12">
        <div className="flex flex-col gap-5 lg:col-span-6">
          <Badge variant="secondary" className="w-fit gap-1.5 px-2.5 py-1">
            <Sparkles className="size-3" />
            <span>OpenAI · Responses · Anthropic · Gemini 四协议互通</span>
          </Badge>

          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
            一套接口，统一调度全球主流大模型
          </h1>

          <p className="max-w-lg text-sm leading-relaxed text-muted-foreground sm:text-base">
            同构零开销透传、四协议双向实时转译、微美元级预占与多渠道容灾重试，默认零数据留存。
          </p>

          <div className="flex flex-wrap items-center gap-3 pt-1">
            <Button
              size="lg"
              render={<Link href={isAuthed ? "/overview" : "/register"} />}
            >
              <span>{isAuthed ? "进入控制台" : "免费注册"}</span>
              <ArrowRight className="size-4" />
            </Button>
            <Button variant="outline" size="lg" render={<Link href="/models" />}>
              浏览模型与定价
            </Button>
          </div>
        </div>

        <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card lg:col-span-6">
          <Tabs defaultValue="openai" className="gap-0">
            <div className="flex items-center justify-between border-b border-border bg-muted/40 px-3 py-2">
              <TabsList className="h-7 bg-transparent p-0">
                <TabsTrigger value="openai" className="px-2.5 text-xs">
                  Chat
                </TabsTrigger>
                <TabsTrigger value="responses" className="px-2.5 text-xs">
                  Responses
                </TabsTrigger>
                <TabsTrigger value="anthropic" className="px-2.5 text-xs">
                  Anthropic
                </TabsTrigger>
                <TabsTrigger value="gemini" className="px-2.5 text-xs">
                  Gemini
                </TabsTrigger>
              </TabsList>
              <TabsContent value="openai" className="flex-none">
                <CopyButton value={SNIPPETS.openai} label="复制" />
              </TabsContent>
              <TabsContent value="responses" className="flex-none">
                <CopyButton value={SNIPPETS.responses} label="复制" />
              </TabsContent>
              <TabsContent value="anthropic" className="flex-none">
                <CopyButton value={SNIPPETS.anthropic} label="复制" />
              </TabsContent>
              <TabsContent value="gemini" className="flex-none">
                <CopyButton value={SNIPPETS.gemini} label="复制" />
              </TabsContent>
            </div>

            {(["openai", "responses", "anthropic", "gemini"] as const).map((key) => (
              <TabsContent key={key} value={key} className="m-0">
                <pre className="overflow-x-auto p-4 font-mono text-xs leading-relaxed text-foreground">
                  <code>{SNIPPETS[key]}</code>
                </pre>
              </TabsContent>
            ))}
          </Tabs>
        </div>
      </section>

      {/* Featured Model Price Strip */}
      <section className="flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold tracking-tight">
            热门模型与实时定价
          </h2>
          <Button
            variant="ghost"
            size="sm"
            render={<Link href="/models" />}
            className="text-muted-foreground"
          >
            <span>全部模型</span>
            <ArrowRight className="size-3.5" />
          </Button>
        </div>

        <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
          {loading ? (
            <div className="flex flex-col gap-3 p-4">
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
            </div>
          ) : needsAuthForCatalog ? (
            <div className="flex flex-col items-center justify-between gap-3 px-5 py-8 sm:flex-row">
              <div className="flex items-center gap-3">
                <Lock className="size-4 text-muted-foreground" />
                <span className="text-sm text-muted-foreground">
                  登录后查看当前环境已上架模型与实时单价
                </span>
              </div>
              <Button size="sm" variant="outline" render={<Link href="/login" />}>
                登录查看
              </Button>
            </div>
          ) : featuredModels.length === 0 ? (
            <div className="px-5 py-8 text-center text-sm text-muted-foreground">
              暂无公开上架模型
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/35 hover:bg-muted/35">
                  <TableHead>模型</TableHead>
                  <TableHead>协议</TableHead>
                  <TableHead>能力</TableHead>
                  <TableHead className="text-right">上下文</TableHead>
                  <TableHead className="text-right">输入 / 1M</TableHead>
                  <TableHead className="w-44 text-right">输出 / 1M</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {featuredModels.map((model) => {
                  const outMicros = model.pricing?.outputPriceMicros1m ?? 0;
                  const ratio = Math.min(100, Math.round((outMicros / maxOutputPrice) * 100));
                  return (
                    <TableRow key={model.id}>
                      <TableCell>
                        <div className="flex flex-col">
                          <span className="font-medium">{model.displayName}</span>
                          <span className="font-mono text-xs text-muted-foreground">
                            {model.modelCode}
                          </span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline">
                          {PROTOCOL_LABEL[model.nativeProtocol]}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-1.5 text-muted-foreground">
                          {model.supportsTools ? (
                            <span title="工具调用">
                              <Wrench className="size-3.5" />
                            </span>
                          ) : null}
                          {model.supportsVision ? (
                            <span title="视觉理解">
                              <Eye className="size-3.5" />
                            </span>
                          ) : null}
                          {model.supportsThinking ? (
                            <span title="深度推理">
                              <Brain className="size-3.5" />
                            </span>
                          ) : null}
                          {model.supportsPromptCache ? (
                            <span title="提示词缓存">
                              <Database className="size-3.5" />
                            </span>
                          ) : null}
                        </div>
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatCompactNumber(model.contextWindow)}
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatPerMillionUsd(model.pricing?.inputPriceMicros1m)}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex flex-col items-end gap-1">
                          <span className="font-mono text-xs tabular-nums">
                            {formatPerMillionUsd(model.pricing?.outputPriceMicros1m)}
                          </span>
                          <div className="h-1 w-24 overflow-hidden rounded-full bg-muted">
                            <div
                              className="h-full rounded-full bg-[var(--chart-1)]"
                              style={{ width: `${Math.max(ratio, outMicros > 0 ? 6 : 0)}%` }}
                            />
                          </div>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </div>
      </section>

      {/* Single-layer 3-column feature strip */}
      <section className="squircle-md grid divide-y divide-border overflow-hidden rounded-xl border border-border bg-card sm:grid-cols-3 sm:divide-x sm:divide-y-0">
        <div className="flex flex-col gap-2 p-5">
          <Zap className="size-4 text-foreground" />
          <h3 className="text-sm font-medium">同构优先 · 四协议双向转译</h3>
          <p className="text-xs leading-relaxed text-muted-foreground">
            原生支持 OpenAI Chat / Responses、Anthropic 与 Gemini 协议，首字节前可跨协议容灾切换备用渠道。
          </p>
        </div>
        <div className="flex flex-col gap-2 p-5">
          <Database className="size-4 text-foreground" />
          <h3 className="text-sm font-medium">微美元级实时计量</h3>
          <p className="text-xs leading-relaxed text-muted-foreground">
            支持资源包、赠金与现金三顺位抵扣，项目级月度软硬预算精准拦截。
          </p>
        </div>
        <div className="flex flex-col gap-2 p-5">
          <ShieldCheck className="size-4 text-foreground" />
          <h3 className="text-sm font-medium">默认零数据留存</h3>
          <p className="text-xs leading-relaxed text-muted-foreground">
            请求正文仅在内存流转不落盘，报表与账本仅记录结构化用量与延迟指标。
          </p>
        </div>
      </section>
    </div>
  );
}
