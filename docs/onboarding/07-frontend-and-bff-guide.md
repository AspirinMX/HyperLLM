# 第 7 课：双独立 Next.js 16 前端架构与 BFF 开发指南

> **本课目标**：快速看懂 `frontend/console`（`:3000`）与 `frontend/admin`（`:3001`）的目录划分、同源 BFF 代理、Single-Flight 令牌刷新锁、Playground SSE 流消费以及提交前必须通过的 4 项门禁。

---

## 1. 两个独立 Next.js 16 应用全景

两个前端工程目前物理独立（各自拥有独立的 `package.json`、`pnpm-lock.yaml`、认证上下文与部署边界），统一技术栈为：
- **Next.js `16.4.6`**（App Router + Turbopack + `cacheComponents: true` + `partialPrefetching: true`）
- **React `19.3.0`** + **TypeScript `strict: true`**
- **Tailwind CSS v4** + **shadcn/ui (`@base-ui/react`)** + **`@animate-ui/react`** + **`motion`** + **`recharts`**

### ① `frontend/console`（`:3000` — 开发者门户与控制台）

路由按三大 Route Group 严格隔离：
- **`src/app/(public)/`**：公开着陆页（`/`）、模型广场（`/models`）、API 文档（`/docs`）
- **`src/app/(auth)/`**：登录（`/login`）、邮箱 OTP 注册（`/register`）、接受团队邀请（`/invitations/accept`）
- **`src/app/(console)/`**：受 `ConsoleAuthGuard` 保护的控制台工作台：
  - `/overview`（总览图表）、`/playground`（SSE 流式对话与对比）、`/projects` 与 `/projects/[projectId]`（项目与月预算）
  - `/api-keys`（签发/启停/吊销与一次性明文弹窗）、`/usage`（ClickHouse 趋势与延迟分位数图表）
  - `/billing`（实时钱包、充值下单、资源包购买、兑换码核销、账本流水）与 `/payment/return`、`/payment/cancel`
  - `/alerts`（Webhook/Email 端点与规则）、`/team`（组织成员与邀请）

### ② `frontend/admin`（`:3001` — 平台运营管理后台）

路由按两大 Route Group 隔离，并按 `AdminRole` 动态裁剪侧边栏与操作按钮：
- **`src/app/(auth)/login`**：运营登录 + 两步 TOTP MFA 绑定/校验（含 `InputOTP`）
- **`src/app/(admin)/`**：
  - `/overview`（经营与毛利大盘）、`/channels`（供应商与加密渠道池 CRUD + 实时探测）
  - `/models` 与 `/models/[modelId]`（虚拟模型、路由绑定编排、版本化定价发布）
  - `/tenants` 与 `/tenants/[orgId]`（租户列表已确认余额 vs 详情实时 Redis 钱包与冻结、人工充值、订单与退款）
  - `/commerce`（资源包 SKU、代金券与批量兑换码导出）
  - `/operations`（自动对账 Findings、支付异常工单、告警投递重试、遥测拒收排查）

---

## 2. 同源 BFF 代理（Route Handlers）

**硬性规定**：浏览器端组件**绝不直接跨域请求 `localhost:8080/8081/8082`**，全部请求同源相对路径，由 Next.js Route Handler 转发到后端服务并透传 `Cookie` / `Set-Cookie`：

| 前端应用 | 前端请求路径 | Route Handler 文件 | 转发目标（环境变量） |
| :--- | :--- | :--- | :--- |
| `frontend/console` | `/api/v1/**` | `src/app/api/v1/[...path]/route.ts` | `CONSOLE_BACKEND_URL`（默认 `http://localhost:8081`） |
| `frontend/console` | `/api/gateway/v1/**` | `src/app/api/gateway/v1/[...path]/route.ts` | `GATEWAY_BACKEND_URL`（默认 `http://localhost:8080`），强制附加 `cache-control: no-cache, no-transform` 与 `x-accel-buffering: no` 保证 SSE 零缓冲 |
| `frontend/admin` | `/api/v1/admin/**` | `src/app/api/v1/admin/[...path]/route.ts` | `ADMIN_BACKEND_URL`（默认 `http://localhost:8082`） |

这样做的两大好处：
1. `HttpOnly` Refresh Cookie（`hl_console_rt` / `hl_admin_rt`）始终同源传输，无需放宽 CORS `credentials` 或第三方 Cookie 限制；
2. 后端内部地址只配置在 Next.js 服务端环境变量中，不暴露给浏览器。

---

## 3. 三个前端核心机制（改代码前必看）

### ① `token-store.ts` 的 Single-Flight 刷新锁
打开 `frontend/console/src/lib/api/token-store.ts`，你会看到一个模块级 `inflightRefresh` Promise：
- 页面初始化时往往同时发出 4–5 个并行 API 请求；若 Access Token 即将过期（提前 30 秒缓冲）或返回 `401`，`client.ts` 会统一调用 `refreshAuthSession()`。
- **为什么必须共用同一个 `inflightRefresh`？** 因为后端 `/api/v1/auth/refresh` 会立即作废旧 Refresh Cookie；如果并发发出两次 `/refresh`，第二个请求就会携带已作废的旧 Cookie，触发 `AUTH_TOKEN_REUSE_DETECTED` 导致整个账号被踢出登录！
- **铁律**：永远通过 `src/lib/api/endpoints.ts`（底层走 `client.ts` + `token-store.ts`）发请求，禁止在组件里手写裸 `fetch("/api/...")`。

### ② Playground 流式 SSE 消费（`src/lib/api/sse.ts`）
Playground 不使用浏览器原生 `EventSource`（因为 `EventSource` 只支持 `GET` 且不能自定义 `Authorization` / `x-api-key` 请求头），而是：
1. 先调用 `POST /api/v1/playground/ephemeral-token` 获取 5 分钟短效 `sk-hl-sb-...` 令牌；
2. 用 `fetch("/api/gateway/v1/chat/completions" | "/api/gateway/v1/messages")` + `response.body.getReader()` (`ReadableStream`) 逐块解析 SSE，实时计算并展示 TTFT、生成速率与内容流。

### ③ 定点 `micro-USD` 格式化与 UI 规范红线
- 金额展示与表单美元输入全部走 `src/lib/format/micro-usd.ts`（纯字符串定点解析为整数 `micro-USD`，禁止 `float` 乘法）；数值展示统一加 `tabular-nums`。
- 所有错误分支必须按 `ApiError.code`（对应 RFC 9457 `ProblemDetail.code`）判断，禁止匹配英文/中文 `detail` 文案。
- UI 组件、圆角同心约束、禁止多层卡片嵌套、禁止默认界面暴露内部 UUID/乐观锁 `version`/后端枚举常量、禁止 `transition-all` 以及 `reduced-motion` 等完整视觉与工程红线，请务必通读一遍 [FRONTEND_DESIGN_SPEC.md](../FRONTEND_DESIGN_SPEC.md)。

---

## 4. 前端开发与提交前四项门禁

在 `frontend/console` 或 `frontend/admin` 目录下改动代码后，提交前必须依次跑绿以下 4 项门禁：

```bash
# 若新增/删除了路由文件，先刷新 Next.js 16 路由类型
pnpm exec next typegen

# 1. ESLint 零错误
pnpm lint

# 2. TypeScript 严格模式零报错
pnpm exec tsc --noEmit

# 3. 生产构建全路由通过（验证 cacheComponents & Suspense 边界）
pnpm build

# 4. Animate UI 动效审计（检查 reduced-motion 与高开销 CSS 动画）
pnpm dlx @animate-ui/cli@latest audit
```

---

**下一课**：[第 8 课：测试体系、故障排查 Runbook 与常见二次开发实战](./08-testing-debugging-and-extending.md)
