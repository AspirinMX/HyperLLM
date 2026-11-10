"use client";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { CopyButton } from "@/components/common/copy-button";

const SECTIONS = [
  { id: "endpoint", label: "网关地址与鉴权" },
  { id: "openai", label: "OpenAI 兼容接口" },
  { id: "anthropic", label: "Anthropic 原生接口" },
  { id: "billing", label: "抵扣顺序与预算" },
  { id: "errors", label: "常见状态说明" },
] as const;

const CODE_OPENAI_PY = `from openai import OpenAI

client = OpenAI(
    base_url="https://api.hyperllm.dev/v1",
    api_key="sk-hl-...",
)

stream = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "总结微服务可观测性要点"}],
    stream=True,
)
for chunk in stream:
    if chunk.choices and chunk.choices[0].delta.content:
        print(chunk.choices[0].delta.content, end="")`;

const CODE_OPENAI_TS = `import OpenAI from "openai";

const client = new OpenAI({
  baseURL: "https://api.hyperllm.dev/v1",
  apiKey: process.env.HYPERLLM_API_KEY,
});

const completion = await client.chat.completions.create({
  model: "gpt-4o",
  messages: [{ role: "user", content: "总结微服务可观测性要点" }],
});`;

const CODE_ANTHROPIC = `curl https://api.hyperllm.dev/v1/messages \\
  -H "x-api-key: $HYPERLLM_API_KEY" \\
  -H "anthropic-version: 2023-06-01" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "claude-sonnet-4-5",
    "max_tokens": 1024,
    "messages": [{"role": "user", "content": "你好"}],
    "stream": true
  }'`;

const CODE_CLAUDE_CODE = `export ANTHROPIC_BASE_URL="https://api.hyperllm.dev"
export ANTHROPIC_API_KEY="sk-hl-..."
claude`;

export default function DocsPage() {
  return (
    <div className="mx-auto grid max-w-6xl gap-10 px-4 py-8 sm:px-6 lg:grid-cols-[200px_minmax(0,1fr)]">
      {/* Left sticky TOC */}
      <aside className="hidden lg:block">
        <nav className="sticky top-20 flex flex-col gap-1 text-sm">
          {SECTIONS.map((s) => (
            <a
              key={s.id}
              href={`#${s.id}`}
              className="rounded-lg px-2.5 py-1.5 text-muted-foreground transition-colors motion-reduce:transition-none hover:bg-muted hover:text-foreground"
            >
              {s.label}
            </a>
          ))}
        </nav>
      </aside>

      {/* Main prose */}
      <article className="flex max-w-3xl flex-col gap-8">
        <div className="flex flex-col gap-2">
          <h1 className="text-xl font-semibold tracking-tight">接入文档</h1>
          <p className="text-sm text-muted-foreground">
            使用在控制台签发的 API 密钥，直接替换现有 SDK 的 Base URL 即可接入。
          </p>
        </div>

        <section id="endpoint" className="scroll-mt-20 flex flex-col gap-3">
          <h2 className="text-base font-medium">网关地址与鉴权</h2>
          <div className="squircle-md flex items-center justify-between rounded-xl border border-border bg-muted/40 px-4 py-3 font-mono text-xs">
            <span>https://api.hyperllm.dev/v1</span>
            <CopyButton
              value="https://api.hyperllm.dev/v1"
              label="复制"
              copiedToast="已复制网关地址"
            />
          </div>
          <div className="grid gap-3 text-xs sm:grid-cols-2">
            <div className="rounded-xl border border-border bg-card p-3.5">
              <div className="font-medium">OpenAI 协议请求头</div>
              <code className="mt-1.5 block font-mono text-muted-foreground">
                Authorization: Bearer sk-hl-...
              </code>
            </div>
            <div className="rounded-xl border border-border bg-card p-3.5">
              <div className="font-medium">Anthropic 协议请求头</div>
              <code className="mt-1.5 block font-mono text-muted-foreground">
                x-api-key: sk-hl-...
              </code>
            </div>
          </div>
        </section>

        <Separator />

        <section id="openai" className="scroll-mt-20 flex flex-col gap-3">
          <div className="flex items-center gap-2">
            <h2 className="text-base font-medium">OpenAI 兼容接口</h2>
            <Badge variant="outline" className="font-mono text-[11px]">
              POST /v1/chat/completions
            </Badge>
          </div>
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            <Tabs defaultValue="python" className="gap-0">
              <div className="flex items-center justify-between border-b border-border bg-muted/40 px-3 py-2">
                <TabsList className="h-7 bg-transparent p-0">
                  <TabsTrigger value="python" className="px-2.5 text-xs">
                    Python
                  </TabsTrigger>
                  <TabsTrigger value="ts" className="px-2.5 text-xs">
                    TypeScript
                  </TabsTrigger>
                </TabsList>
                <TabsContent value="python" className="flex-none">
                  <CopyButton value={CODE_OPENAI_PY} label="复制" />
                </TabsContent>
                <TabsContent value="ts" className="flex-none">
                  <CopyButton value={CODE_OPENAI_TS} label="复制" />
                </TabsContent>
              </div>
              <TabsContent value="python" className="m-0">
                <pre className="overflow-x-auto p-4 font-mono text-xs leading-relaxed">
                  <code>{CODE_OPENAI_PY}</code>
                </pre>
              </TabsContent>
              <TabsContent value="ts" className="m-0">
                <pre className="overflow-x-auto p-4 font-mono text-xs leading-relaxed">
                  <code>{CODE_OPENAI_TS}</code>
                </pre>
              </TabsContent>
            </Tabs>
          </div>
        </section>

        <Separator />

        <section id="anthropic" className="scroll-mt-20 flex flex-col gap-3">
          <div className="flex items-center gap-2">
            <h2 className="text-base font-medium">Anthropic 原生接口</h2>
            <Badge variant="outline" className="font-mono text-[11px]">
              POST /v1/messages
            </Badge>
          </div>
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            <Tabs defaultValue="curl" className="gap-0">
              <div className="flex items-center justify-between border-b border-border bg-muted/40 px-3 py-2">
                <TabsList className="h-7 bg-transparent p-0">
                  <TabsTrigger value="curl" className="px-2.5 text-xs">
                    cURL
                  </TabsTrigger>
                  <TabsTrigger value="claude-code" className="px-2.5 text-xs">
                    Claude Code
                  </TabsTrigger>
                </TabsList>
                <TabsContent value="curl" className="flex-none">
                  <CopyButton value={CODE_ANTHROPIC} label="复制" />
                </TabsContent>
                <TabsContent value="claude-code" className="flex-none">
                  <CopyButton value={CODE_CLAUDE_CODE} label="复制" />
                </TabsContent>
              </div>
              <TabsContent value="curl" className="m-0">
                <pre className="overflow-x-auto p-4 font-mono text-xs leading-relaxed">
                  <code>{CODE_ANTHROPIC}</code>
                </pre>
              </TabsContent>
              <TabsContent value="claude-code" className="m-0">
                <pre className="overflow-x-auto p-4 font-mono text-xs leading-relaxed">
                  <code>{CODE_CLAUDE_CODE}</code>
                </pre>
              </TabsContent>
            </Tabs>
          </div>
        </section>

        <Separator />

        <section id="billing" className="scroll-mt-20 flex flex-col gap-3">
          <h2 className="text-base font-medium">抵扣顺序与隐私保障</h2>
          <div className="squircle-md grid divide-y divide-border overflow-hidden rounded-xl border border-border bg-card text-xs sm:grid-cols-3 sm:divide-x sm:divide-y-0">
            <div className="p-4">
              <div className="font-medium">1 · 定向资源包</div>
              <p className="mt-1 text-muted-foreground">
                命中适用模型的未过期资源包按最早到期优先扣减。
              </p>
            </div>
            <div className="p-4">
              <div className="font-medium">2 · 赠金余额</div>
              <p className="mt-1 text-muted-foreground">
                按有效期先后自动抵扣活动赠金与兑换码余额。
              </p>
            </div>
            <div className="p-4">
              <div className="font-medium">3 · 现金余额</div>
              <p className="mt-1 text-muted-foreground">
                按实测 Token 多退少补；默认零数据留存，不保存对话正文。
              </p>
            </div>
          </div>
        </section>

        <Separator />

        <section id="errors" className="scroll-mt-20 flex flex-col gap-3">
          <h2 className="text-base font-medium">常见拦截与限流场景</h2>
          <div className="squircle-md overflow-hidden rounded-xl border border-border bg-card">
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/35 hover:bg-muted/35">
                  <TableHead>场景</TableHead>
                  <TableHead>建议处理方式</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody className="text-xs">
                <TableRow>
                  <TableCell className="font-medium">密钥无效或已吊销</TableCell>
                  <TableCell className="text-muted-foreground">
                    核对密钥前缀与状态，或在控制台重新签发。
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell className="font-medium">余额不足</TableCell>
                  <TableCell className="text-muted-foreground">
                    前往账务中心充值或核销兑换码后立即恢复调用。
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell className="font-medium">触发限流或项目硬预算</TableCell>
                  <TableCell className="text-muted-foreground">
                    遵循响应重试提示退避，或在项目配置中调高月度上限与并发阈值。
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell className="font-medium">上游渠道暂时不可用</TableCell>
                  <TableCell className="text-muted-foreground">
                    网关已在首字节前自动尝试备援渠道；持续异常请稍后指数退避重试。
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </div>
        </section>
      </article>
    </div>
  );
}
