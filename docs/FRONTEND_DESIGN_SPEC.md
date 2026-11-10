# HyperLLM 前端开发与设计规范

本文档针对 HyperLLM 双独立前端工程（`frontend/console` 与 `frontend/admin`；技术栈：Next.js `16.4.6` App Router + `cacheComponents: true`、React `19.3.0`、TypeScript `strict`、`shadcn --preset b27GcrRo` / `@base-ui/react`、`@animate-ui/react`、`motion`、Tailwind CSS `v4`、`recharts`）确立工程标准、视觉几何、图表表达与提交前验收红线。分级采用 **【强制】**、**【推荐】** 与 **【禁止】**。

---

## 一、UI 组件体系、几何圆角与动效规范

1. **【强制】复用 shadcn (`--preset b27GcrRo`) / Base UI / Animate UI 组件体系**
   - 全部交互原语（`Sidebar`、`Tabs`、`Dialog`、`Sheet`、`AlertDialog`、`DropdownMenu`、`Select`、`Tooltip`、`Collapsible`、`Switch`、`Checkbox`、`Progress`、`Table`、`Chart`、`Popover`、`InputOTP` 等）统一收口于各应用的 `src/components/ui/`，业务页面优先组合已有原语。
   - **【禁止】手写重复基础组件库**：禁止在业务目录内另起一套 Modal、Dropdown、Tabs、Tooltip 或手写 SVG 业务图表。
2. **【强制】遵循苹果式比例与同心圆角体系**
   - 根据控件高度与容器层级选用对应圆角 Token（小型控件 `8px`、中等卡片/图表容器 `12px`、抽屉/弹窗 `16px`），严禁无视尺寸全局硬编码 `rounded-2xl`。
   - 嵌套元素满足同心圆角减法约束 `r_inner = max(4px, r_outer - padding)`；中大容器（`>= 12px`）优先启用 `corner-shape: squircle` 渐进增强。
3. **【禁止】多层卡片嵌套**
   - 同一视觉层级只允许存在一层带边框/背景的面板。禁止在卡片、分组容器或表格外框内再叠加带边框/背景/阴影的子卡片。
   - 指标条采用单容器 `divide-x` 均分；内部分层使用分组标题、间距与 `Separator` / `divide-y`；代码块、表格外框与图表画布仅使用浅底与 `1px` 细描边。
4. **【强制】统一全局 Motion Token 与空间连续性**
   - 全局动效曲线与时长统一收口在 `src/lib/motion.ts`，不在业务组件散写孤立 `duration`。
   - 保留 `Dialog` / `AlertDialog` 纯 2D Flex 居中防模糊约束（静止态不保留 `perspective` 或 `filter: blur(0px)`），消除冗余 CSS 动画冲突。
5. **【强制】尊重系统 `reduced-motion` 设置**
   - `AppProviders` 在根部启用 `<MotionConfig reducedMotion="user">` 与 Animate UI 降级支持。新增 CSS 过渡/动画（如 `animate-spin`、`animate-pulse`、`transition-*`）旁必须补充 `motion-reduce:animate-none` 或 `motion-reduce:transition-none`。
6. **【禁止】使用 `transition-all`**
   - CSS 过渡须明确指定属性（如 `transition-colors`、`transition-opacity`、`transition-[color,box-shadow]`），避免全属性重排并与 `motion` 冲突。
7. **【强制】克制视觉与等宽数字**
   - 采用中性语义化 OKLCH 变量与 5 组低饱和语义图表色板；所有金额、Token、请求数、百分比、延迟与时间戳统一使用 `tabular-nums`。
   - Playground 流式 Markdown 输出正文**严禁**逐字抖动动画。

---

## 二、最简自解释 UI 与去开发者化文案红线

1. **【强制】UI 自解释优先于文字解释**
   - 设计按钮、输入框与卡片时先确认：**仅凭图标、排版位置、占位符与视觉指示，用户能否直接知道它是干什么的？**
   - 按钮仅使用「图标 + 2–4 字动词短语」（如 `新建密钥`、`立即探测`、`生成兑换码`），不配冗长副标题。
   - 输入框用拟真占位符 + 内嵌单位后缀（`USD`、`RPM`、`TPM`、`分钟`、`天`）代替长段提示；仅对复杂业务机制（三顺位抵扣、在途预占、一次性密文）提供轻量 Tooltip 或单句核心提示。
2. **【禁止】暴露内部编号、版本号与后端常量**
   - 默认界面禁止呈现 36 位内部 UUID、乐观锁 `version`、HTTP 状态码、后端枚举常量（如 `BONUS_EXPIRE`、`CONFLICT_OPTIMISTIC_LOCK`、`LEDGER_BACKLOG`）与裸 JSON。
   - 仅保留面向 API 用户的必要业务标识（如模型标识 `claude-sonnet-4-5`、脱敏前缀 `sk-hl-...`、接口地址），以及管理后台对账/支付异常抽屉内显式折叠的「诊断详情」。
3. **【强制】一次性密文安全确认**
   - 新建 API Key 的 `rawKey`、新建 Webhook 的 `signingSecret`、批量生成的兑换码与 TOTP 绑定密钥仅展示一次，须使用独立确认弹窗提供一键复制/导出与关闭前提醒。

---

## 三、图表优先的数据可视化原则

1. **【强制】用图表和比例条替代纯数字陈列**
   - 控制台总览、用量分析、项目预算、钱包余额、经营毛利大盘与路由编排页面，均须通过 shadcn `Chart`（Area / Stacked Bar / Composed Bar+Line / Multi-Line / Horizontal Ranked Bar）及语义分段进度条呈现趋势、构成与对比，禁止只罗列一排数字。
2. **【强制】严格 `micro-USD` 整数精度**
   - 所有金额底层以整数 `micro-USD`（`1 USD = 1_000_000 micros`）运算与传输；表单输入的美元金额通过纯字符串定点解析转为整数微美元，绝不使用 `float` 做计费乘除。

---

## 四、双端隔离与 Next.js 16 / React 19 / TypeScript 规范

1. **【强制】双端与路由组严格隔离**
   - `frontend/console`（`:3000`）与 `frontend/admin`（`:3001`）保持物理独立部署、独立 JWT/Cookie 与独立业务 Context。
   - `frontend/console` 严格划分 `(public)` / `(auth)` / `(console)`；`frontend/admin` 严格划分 `(auth)` / `(admin)`，并按 RBAC 角色动态裁剪导航与写操作。
2. **【强制】兼容 `cacheComponents: true` 与 `strict: true`**
   - 使用客户端 Hooks 或 Motion/Animate UI 的组件首行声明 `"use client"`；动态路由参数、搜索参数与数据边界按 Next.js 16 预渲染要求配合 `<Suspense>`。
   - TypeScript 开启 `strict: true`，**禁止使用 `any`**（未知结构使用 `unknown` + 类型守卫）。
3. **【强制】Base UI `render` 无嵌套交互标签**
   - 通过 `render={<Link ... />}` 或 `render={<Button ... />}` 组合触发器，禁止 `<button><button>` 或 `<a><button>` 嵌套。

---

## 五、API 代理、Single-Flight 刷新与 SSE 消费

1. **【强制】API 调用收口与同源代理**
   - 业务组件禁止散落裸 `fetch("/api/...")`，全部经由 `src/lib/api/` 调用同源相对路径，并由 Next.js Route Handler 代理到后端（`CONSOLE_BACKEND_URL` / `GATEWAY_BACKEND_URL` / `ADMIN_BACKEND_URL`），透传 `Cookie` 与 `Set-Cookie`。
2. **【强制】Single-Flight 令牌刷新锁**
   - 禁止绕过 `token-store.ts` 的单飞刷新锁并发调用 `/refresh`，防止轮换 Cookie 触发重放检测。
3. **【强制】`fetch` `ReadableStream` 消费 SSE**
   - Playground 统一通过 `fetch` + `ReadableStream` 流式消费 OpenAI `/v1/chat/completions` 与 Anthropic `/v1/messages`，禁止使用原生 `EventSource`。
4. **【强制】按 `error.code` 分支处理与幂等键**
   - 所有错误分支只判断 `ApiError.code`（RFC 9457 `ProblemDetail.code`），禁止字符串匹配提示文案；支付下单与人工充值必须传递 `Idempotency-Key`。

---

## 六、布局安全与提交前门禁

1. **【强制】固定高度可滚动工作台防塌陷**
   - Playground 使用 `SidebarInset`（`flex h-svh min-w-0 flex-col overflow-hidden`）+ `ResizablePanelGroup`（`min-h-0 flex-1 overflow-hidden`）+ 内部消息流 `min-h-0 flex-1 overflow-y-auto` 与底部悬浮输入区安全留白。
2. **【强制】提交前四项门禁**
   - `pnpm lint`（零错误）
   - `pnpm exec tsc --noEmit`（零报错，路由增删后先 `npx next typegen`）
   - `pnpm build`（全路由生产构建通过）
   - `pnpm dlx @animate-ui/cli@latest audit`（无遗漏 `reduced-motion` 或高成本 CSS 动画）
