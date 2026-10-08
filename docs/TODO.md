# HyperLLM 项目分阶段实施 TODO 清单

> 本文档依据 [PROPOSAL.md](./PROPOSAL.md) 与第一阶段详细设计文档（[01-database-design.md](./design/01-database-design.md)、[02-dependencies-and-infra.md](./design/02-dependencies-and-infra.md)、[03-phase1-backend-contracts.md](./design/03-phase1-backend-contracts.md)）拆解各阶段可跟踪任务。
> **当前研发策略**：**后端与基础设施先行，暂不实现前端页面开发**（`frontend/console` 与 `frontend/admin` 保持初始脚手架状态，待后端服务与 OpenAPI 3.1 契约稳定后另行启动独立前端交付期）。

---

## 进度总览

| 阶段 | 核心定位 | 当前状态 | 关联文档 |
| :--- | :--- | :---: | :--- |
| **Phase 0** | 仓库与多模块工程脚手架初始化 | ✅ 已完成 | [PROPOSAL.md](./PROPOSAL.md) |
| **Phase 1（设计）** | 数据库实体、工程依赖与基建、后端运行契约 | ✅ 已完成 | [01](./design/01-database-design.md) / [02](./design/02-dependencies-and-infra.md) / [03](./design/03-phase1-backend-contracts.md) |
| **Phase 1（编码）** | MVP 基础代理、控制面 API 与过渡记账闭环（纯后端） | 🚧 进行中（已完成 1.1–1.5 与 1.6 核心编码及测试；待补独立 `curl`/HTTP 联调脚本） | [03-phase1-backend-contracts.md](./design/03-phase1-backend-contracts.md) |
| **Phase 2** | 商业化计费、完整 `Pre-hold/Settle`、Outbox CDC 与 ClickHouse | ⏳ 待开始 | [01-database-design.md](./design/01-database-design.md) |
| **Phase 3** | 全协议双向转译、智能路由、SSO/风控与运营治理 | ⏳ 待开始 | [PROPOSAL.md](./PROPOSAL.md) |
| **Phase 4** | 高并发压测调优、可观测性大盘与 K8s 生产交付 | ⏳ 待开始 | [PROPOSAL.md](./PROPOSAL.md) |
| **Frontend Track** | `frontend/console` 与 `frontend/admin` 前端选型与页面开发 | ⏸️ 暂缓（后端先行） | [PROPOSAL.md](./PROPOSAL.md#16-双端应用功能清单nextjs-16--react-19) |

---

## Phase 0：仓库骨架与第一阶段详细设计（已完成）

- [x] 初始化 `HyperLLM` 仓库、Apache-2.0 `LICENSE`、`README.md` / `README_zh-CN.md` / `AGENTS.md` / `CONTRIBUTING.md` 占位
- [x] 初始化 `backend/` Java 21 + Maven Wrapper 6 模块骨架（`common`、`domain`、`protocol`、`gateway`、`console`、`admin`）
- [x] 初始化 `frontend/console` 与 `frontend/admin`（Next.js 16.4.6 + React 19.3.0 + Tailwind CSS 4.3.3）初始脚手架
- [x] 建立 `infra/` 目录框架（`debezium/`、`kafka/`、`observability/`、`k8s/`、`docker-compose.yaml` 占位）
- [x] 对齐规划蓝图 [PROPOSAL.md](./PROPOSAL.md) 与当前工程基线
- [x] 完成第一阶段（第一部分）设计：[01-database-design.md](./design/01-database-design.md)（PostgreSQL 16 + ClickHouse 24 + Redis 7）
- [x] 完成第一阶段（第二部分）设计：[02-dependencies-and-infra.md](./design/02-dependencies-and-infra.md)（Maven 依赖矩阵、`-Pjooq`、Compose 分层与端口）
- [x] 完成第一阶段（第三部分）设计：[03-phase1-backend-contracts.md](./design/03-phase1-backend-contracts.md)（双域认证、Core REST API 与 Gateway MVP 代理/记账契约）

---

## Phase 1：MVP 基础代理与核心后端闭环（当前焦点 · 暂不开发前端）

### 1.1 工程基建、POM 依赖与容器编排
- [x] 新增根目录 `.gitignore`（忽略 `target/`、`out/`、`.env`、`*.log`、`node_modules/` 等）与 `.env.example`（DB/Redis/Kafka/ClickHouse/JWT/AES/Pepper/Bootstrap Admin 变量模板）
- [x] 补全 [backend/pom.xml](../backend/pom.xml)（继承 `spring-boot-starter-parent` 3.5.x、Java 21、`uuid-creator`、`jtokkit`、`springdoc-openapi`、`jjwt`、`wiremock-standalone`、Surefire/Failsafe 集成测试分组）
- [x] 补全 6 个子模块 `pom.xml` 依赖关系（确保 `gateway`、`console`、`admin` 统一依赖 `common` + `domain` + `protocol`）
- [x] 补全 [infra/docker-compose.yaml](../infra/docker-compose.yaml)（默认 core：`postgres:16` 逻辑复制、`redis:7` AOF、`apache/kafka:3.9` KRaft、`kafka-init`；`pipeline`：`debezium/connect:2.7`、`debezium-init`、`clickhouse:24`；`observability` / `tools`）
- [x] 编写初始化配置文件：`infra/kafka/create-topics.sh`、`infra/debezium/hyperllm-config-connector.json`、`infra/clickhouse/init/01-init-olap.sql`
- [x] 完善工程协作文档：`AGENTS.md`、`CONTRIBUTING.md`、`README.md` 与 `README_zh-CN.md`

### 1.2 `common` 与 `domain` 底座及 Flyway + jOOQ 落地
- [x] `hyperllm-common`：实现 `micro-USD` 运算与向上取整工具、AES-256-GCM 加解密封装（`[1B ver][12B IV][cipher+tag]` + AAD）、HMAC-SHA256 工具、Base62 `sk-` Key 生成器、UUIDv7 工具与统一业务异常/错误码枚举
- [x] `hyperllm-domain`：编写 Phase 1 Flyway 脚本 `V1__init_auth_and_tenants.sql`、`V2__init_models_channels_pricing.sql`、`V3__init_wallets_ledger_and_audit.sql`（含 `wallet_ledger` 与 `audit_logs` 按月 `RANGE` 分区与近期分区预创建）
- [x] 在 `backend/domain/pom.xml` 配置 `-Pjooq` Profile，使用 Testcontainers Postgres 16 运行 `V1`–`V3` 生成 jOOQ 代码至 `src/main/generated`
- [x] `hyperllm-domain`：编写纯 Java `record` 领域模型、核心 jOOQ Repository（User/Org/Project/Member/ApiKey/Provider/Channel/Model/Binding/Pricing/Wallet/Ledger）及 Postgres 容器集成测试

### 1.3 `protocol` 模块（Phase 1 同构透传骨架与 Usage 嗅探）
- [x] 定义 `Canonical IR` 核心抽象模型与协议适配器接口（预留 Phase 3 双向异构转译扩展位）
- [x] 实现 OpenAI `/v1/chat/completions` 请求预处理（自动按需注入 `stream_options.include_usage=true`）、非流式/SSE 流式 Usage 提取与末帧还原剥离
- [x] 实现 Anthropic `/v1/messages` 请求校验与原生 SSE 事件流（`message_start`、`content_block_delta`、`message_delta`）Usage 提取
- [x] 集成 `jtokkit` 实现缺失 Usage 或流中途断连时的 Token 估算兜底，补齐协议单元测试

### 1.4 `console` 开发者控制台后端（端口 `8081`）
- [x] 实现邮箱 OTP 发送（Redis）与用户注册（同事务建 `users`、`Personal Workspace`、`default` Project、`wallets` 与可配置体验金）
- [x] 实现 `console` 独立 `HS256` Access JWT + `hl_console_rt` `HttpOnly` Refresh Cookie 家族轮换防重放与登出吊销
- [x] 实现组织/成员/邀请与 `org_members + project_members` 双层 RBAC 权限校验器
- [x] 实现 Project CRUD、API Key 签发（仅创建返一次明文）/启停/吊销、`GET /api/v1/models`、钱包与账本分页查询及 Playground `sk-hl-sb-...` 临时凭证签发
- [x] 所有配置写接口在事务提交后主动更新/清理对应 Redis L2 Key；配置 RFC 9457 `ProblemDetail` 全局异常处理与 `springdoc-openapi` OpenAPI 3.1 导出

### 1.5 `admin` 运营后台后端与 Phase 1 异步账本消费（端口 `8082`）
- [x] 实现首个 `SUPER_ADMIN` 环境变量启动器引导，以及 `admin` 独立 `HS256` JWT + 可配置 `hyperllm.admin.mfa.required` TOTP 绑定/校验
- [x] 实现 Providers 查询、Channels CRUD（AES-256-GCM 凭证加密、脱敏返回）与 `/probe` 真实上游连通性探针
- [x] 实现 Virtual Models CRUD、Model-Channel Bindings 配置、版本化 `pricing_rules` 发布（校验生效时间窗不重叠）
- [x] 实现租户状态管理与 Phase 1 过渡测试充值接口（`/api/v1/admin/orgs/{orgId}/wallet/topup`，同事务更新 `wallets` + `wallet_ledger` 并刷 Redis）
- [x] 实现 Kafka `llm.billing.settlement` 消费者：幂等写入 `wallet_ledger`（`ON CONFLICT (idempotency_key, created_at) DO NOTHING`）并扣减 `wallets`

### 1.6 `gateway` 数据面 WebFlux 网关（端口 `8080`）
- [x] 配置 `spring.main.web-application-type=reactive`、`spring.flyway.enabled=false`、小容量 Hikari 冷回源连接池与按供应商隔离的 Reactor Netty `HttpClient`
- [x] 实现 `Caffeine L1 -> Lettuce Reactive Redis L2 -> SingleFlight + 虚拟线程/boundedElastic 调 domain 回源` 三级配置与钱包加载链路
- [x] 接入 Kafka `cdc.gateway.config`（每实例独立 Consumer Group）消费 Debezium 事件，亚秒级驱逐 Caffeine L1
- [x] 实现入口鉴权（`sk-...` HMAC 与 `sk-hl-sb-...` Sandbox Redis Token）、IP/模型白名单校验与 Redis RPM + 并发限流
- [x] 实现 `priority_tier + weight` 加权随机路由与最多 2 次首字节前失败重试（`429`/`5xx`/超时）
- [x] 实现 `/v1/chat/completions`、`/v1/messages` 同构透传与 `/v1/models`，接入简化版 Redis Lua 扣费（先通用赠金后现金）并异步发射 `llm.billing.settlement` 与 `llm.request.events`
- [x] 编写 WireMock + Testcontainers 端到端集成测试，通过 `curl`/HTTP 脚本验证完整调用、CDC 失效、故障重试与账本落盘（2.0 收尾：`GatewayE2EIntegrationTest` 补 CDC 驱逐断言、新增 `SettlementPipelineIT`、新增 [scripts/e2e/phase1-smoke.sh](../scripts/e2e/phase1-smoke.sh)）

---

## Phase 1 遗留缺口登记（2.0 产出）

> 2.0 的原则是**只钉基线、不改基线行为**。已修复的项标注修复点，未修的项标注由哪个子任务承接，避免「知道有问题但忘了」。
> 基线验证入口：`./backend/mvnw -f backend/pom.xml verify`（JVM 内）+ `bash scripts/e2e/phase1-smoke.sh`（活栈）。

| # | 缺口 | 现状 | 处置 |
| :--- | :--- | :--- | :--- |
| **G1** | 跨服务链路未验证：gateway 发结算事件、admin 消费落库，两半只在各自 JVM 里测过 | ✅ 已关闭：新增 `SettlementPipelineIT`，走真实 Kafka 监听器写入 `wallet_ledger`，并断言重放只落一行 | 2.0 |
| **G2** | CDC 只发不验：E2E 第 5 步发完事件没有任何断言 | ✅ 已关闭：改为「吊销 key → 删 L2 → 发事件 → 轮询直到 401」，否则断言失败 | 2.0 |
| **G3** | 没有活栈脚本，真实 Debezium/Kafka topic/Redis Lua 从未一起跑过 | ✅ 已关闭：`scripts/e2e/phase1-smoke.sh` + Compose `tools` profile 的 WireMock 上游 | 2.0 |
| **G4** | `pricing == null` 时 `exactCostMicros = 0`，未配置定价的模型被免费使用 | ❌ 未修（会改变基线行为）；已在新设计稿中定义为拒绝并抛 `MODEL_PRICING_MISSING` | 2.2 |
| **G5** | `deduct_wallet.lua` 不钳制下限，Redis 现金可为负；随后 `wallets.cash_balance_micros` 的 `CHECK (>= 0)` 让结算落库失败并无限重试 | ❌ 未修；分档预占 + 现金腿钳制后不再可能出现负值 | 2.3 |
| **G6** | `spring.kafka.listener.ack-mode` 未配置（默认 BATCH），而 `BillingSettlementConsumer` 声明了 `Acknowledgment` 参数 → **每条结算消息都抛异常，账本从未被 Kafka 写入** | ✅ 已修复：`admin/application.yml` 增补 `ack-mode: manual`；`SettlementPipelineIT` 现在能守住这条链路 | 2.0 |
| **G7** | `BillingSettlementConsumer.onMessage` 调 `this.ingestIdempotent(...)` 属自调用，`@Transactional` 被代理绕过 → 监听器路径实际无事务（直调测试看不到） | ❌ 未修 | 2.4 |
| **G8** | `wallet_ledger` 跨月入账、分区预建作业从未被测过 | ❌ 未修 | 2.4 |

## Phase 2：商业化计费、完整 CDC/Outbox 与 OLAP 管道闭环

> 按 `2.0 → 2.1 → 2.2 → 2.3 → 2.4 → 2.5 → 2.6` 推进计费主线。2.7 可在用量与计价口径明确后并行，2.8 可在请求事件契约明确后并行。2.9 完成全链路验收。

### 2.0 设计与 Phase 1 验收基线

- [ ] 梳理 Phase 1 遗留的 HTTP 联调缺口、结算事务边界、测试充值幂等性和并发超支问题，将修复与回归场景纳入对应开发步骤。
- [ ] 完成 Phase 2 详细设计与评审，确认预占默认上限与超预占处理、历史余额与 Redis Key 迁移、支付渠道范围、ClickHouse 摄入方式及各步骤的事件和 API 契约。

### 2.1 Flyway V4、jOOQ 与领域仓储

- [ ] 编写并执行 `V4__init_commercial_billing_outbox.sql`，新增 `payment_orders`、`resource_packs`、`vouchers`、`redemption_codes`、`bonus_grants`、`org_model_pricing_overrides`、`notification_endpoints`、`billing_alert_rules` 和 `outbox_events`，补齐 `wallet_ledger.ref_order_id` 外键。见 [数据库迁移规划](./design/01-database-design.md)。
- [ ] 通过 `-Pjooq` 重新生成代码，补齐 `domain` 的领域模型和仓储。
- [ ] 通过 PostgreSQL 16 容器测试验证空库初始化、V3 升级、旧账本兼容性以及新表的关键约束。

### 2.2 三顺位抵扣与组织模型协议价

- [ ] 实现按模型适用范围和到期顺序确定分摊的三顺位抵扣 `资源包（Token/次） → 赠金批次 → 现金`。
- [ ] 接入组织级单模型协议价，沿用 `micro-USD` 整数精度与 `MicroUsd` 向上取整规则，输出逐批次分摊明细。
- [ ] 验证过期或不适用的权益、部分抵扣、多批次抵扣、余额不足与定价生效边界。

### 2.3 网关预占、流式结算与回收

- [ ] 将 `gateway` 的 Redis Lua 计费升级为 `Pre-hold / Settle`，准入时原子预占，收尾时按实测 Usage 或估算用量结算并释放剩余预占。
- [ ] 实现长流心跳续期、僵尸预占到期回收，以及结算和释放的幂等处理。
- [ ] 用 Redis 容器测试验证并发请求防超支，以及断连、上游异常和实例退出后的结算与回收。

### 2.4 结算事件、账本落库与分区维护

- [ ] 升级 `llm.billing.settlement` 事件，携带资源包与赠金分摊明细，兼容 Phase 1 事件重放。
- [ ] 在 `admin` 同一事务内幂等写入 `wallet_ledger` 并更新钱包及权益余额，补齐失败重试和重放流程。
- [ ] 预建 `wallet_ledger` 与 `audit_logs` 的未来月份分区，验证跨月入账、重复事件和消费者恢复。

### 2.5 Outbox CDC 与 Redis 加额同步

- [ ] 在充值及权益发放事务中写入 `outbox_events`，按评审确定的方案切换 Phase 1 测试充值链路。
- [ ] 在 V4 就绪后启用 Outbox Connector，由 `admin` 幂等消费 `cdc.wallet.*`、`cdc.pack.*`、`cdc.bonus.*`，同步钱包及权益变动。见 [Connector 配置](../infra/debezium/hyperllm-outbox-connector.json)。
- [ ] 验证业务事务回滚、重复投递和消费者停机恢复。恢复后已提交的加额与权益发放各生效一次。

### 2.6 支付、兑换码与资源包购买

- [ ] 在 `console` 实现支付抽象、下单、订单查询和验签回调。Stripe、支付宝、微信支付的接入范围及顺序由 2.0 确认。
- [ ] 交付兑换码核销、资源包购买及相应的 `admin` 运营接口，复用 2.5 的加额与权益发放链路。
- [ ] 通过沙箱和模拟测试验证支付成功、签名与金额校验、回调重放及兑换码并发核销。

### 2.7 四层限流与项目预算

- [ ] 补齐 API Key 和 Project 的 RPM/TPM 滑动窗口，完善 Organization 并发保护和 Channel 上游容量保护。
- [ ] 实现 Project 月度预算检查，软上限触发告警，硬上限在准入时拦截超预算请求。
- [ ] 验证跨分钟限流、并发准入和预算超限的协议错误响应。

### 2.8 ClickHouse 摄入、用量与毛利 API

- [ ] 接通 `llm.request.events` 到 ClickHouse `llm_request_events` 和 `mv_usage_hourly` 的摄入，遵循 ZDR，仅落盘结构化用量元数据。
- [ ] 在 `console` 提供按租户权限过滤的聚合用量查询，在 `admin` 提供毛利查询。
- [ ] 验证重复投递后的指标准确性、明细与聚合的一致性、查询权限和 ZDR 约束。

### 2.9 告警、对账与全链路验收

- [ ] 上线低余额及 Project 月度软/硬预算的 Webhook 和邮件告警，验证冷却期与失败处理。
- [ ] 在 `admin` 周期性核对 Redis 与 PostgreSQL 的钱包和权益余额，纳入 Kafka 待落账事件，监控结算积压和账务差异。
- [ ] 补齐可重复执行的 HTTP 联调脚本，覆盖充值或兑换、预占、SSE 调用、结算入账、报表与告警，并回归 Phase 1 的配置 CDC、故障重试和账本落盘。
- [ ] 通过 Maven `test`、`verify` 和 Compose `pipeline` 配置校验，并在联调环境验证并发防超支、重复投递幂等和消费恢复后的余额一致性。

---

## Phase 3：全协议双向转译、智能路由与企业级治理

- [ ] 编写并迁移 Flyway `V5__init_governance_sso_invoices.sql`（`sso_connections`, `model_org_grants`, `guardrail_policies`, `wallet_adjustments`, `billing_statements`, `invoices`, `invoice_payment_orders`）及刷新 `-Pjooq`
- [ ] 补全 `protocol` Canonical IR 双向异构转译引擎（OpenAI Chat/Responses <-> Anthropic Messages `thinking`/`tool_use`/`cache_control` <-> Gemini）
- [ ] 扩展网关对外协议端点：`/v1/responses`、`/v1beta/models/{model}:generateContent` / `streamGenerateContent`、`/v1/messages/count_tokens`、`/v1/embeddings`、`/v1/images/generations`、`/v1/audio/*`、`/v1/rerank`
- [ ] 升级 `gateway` 智能路由：`LOWEST_COST` 策略、`TTFT_AWARE`（近 1m Redis 滑动窗口 P90 TTFT + EWMA）、Prompt Cache Sticky Session（`gw:sticky:{hash}`）与三态分布式熔断器（`CLOSED`/`OPEN`/`HALF_OPEN`）
- [ ] 接入企业级能力：OIDC / SAML 企业 SSO 登录与 JIT 入组、`RESTRICTED` 私有模型定向授权、GitHub / Google OAuth 完善
- [ ] 落地内容安全与调试合规：`GuardrailProvider` SPI（AC 自动机敏感词、PII 脱敏、注入拦截、外部审核）与 Opt-in 项目加密调试日志写 ClickHouse `llm_debug_payloads`（1~7 天 TTL 自动清除）
- [ ] 落地财务与合规后台接口：运营人工调账二次审批流（四眼约束）、月度 `billing_statements` 结账任务、双轨 `invoices` 开具与 `audit_logs` 审计查询 API

---

## Phase 4：压测调优、可观测性与生产交付

- [ ] 使用 K6 / Gatling 执行万级并发 SSE 长连接与防穿仓混沌压测（验证网关附加延迟 P99 < 15ms、网关 OOM 僵尸预占自动回收）
- [ ] 完善 `infra/observability/`：OTel Collector 链路串联配置、Prometheus 指标告警规则、Grafana 业务营收/毛利/渠道健康/账务积压大盘
- [ ] 完善 `infra/k8s/`：Helm Charts、基于并发/CPU/连接数的 HPA 弹性扩缩容模板、Ingress/TLS/WAF 配置
- [ ] 按吞吐规模评估是否将 `admin` 内的异步消费/对账模块独立抽离为 `backend/worker` 子模块
- [ ] 安全演练与上线检查：双 Pepper 平滑轮换验证、AES-256-GCM 主密钥版本轮换验证、全量回归测试

---

## Frontend Track（⏸️ 当前暂缓，待后端 Phase 1/2 接口稳定后独立启动）

- [ ] 选型与统一前端底座：`shadcn/ui`、Tailwind CSS v4 语义主题、`openapi-typescript` + `openapi-fetch`、Next.js 16 `cacheComponents` 鉴权状态流与 `next-intl` 双语方案
- [ ] `frontend/console`（端口 `3000`）：门户官网与模型广场、注册登录引导、Org/Project/API Key 管理、Playground（SSE 直连与 Arena 对比）、ClickHouse 用量仪表盘与充值开票中心
- [ ] `frontend/admin`（端口 `3001`）：运营登录与 TOTP MFA、渠道密钥池与探针调试、虚拟模型与路由编排、定价版本与代金券配置、毛利大盘、调账审批与对账巡检中心
- [ ] 评估双端通用组件稳定后是否整合 `frontend/` 为统一 `pnpm` Workspace（抽离 `packages/ui` 与 `packages/sdk`）
