# HyperLLM 项目分阶段实施 TODO 清单

> 本文档依据 [PROPOSAL.md](./PROPOSAL.md) 与第一阶段详细设计文档（[01-database-design.md](./design/01-database-design.md)、[02-dependencies-and-infra.md](./design/02-dependencies-and-infra.md)、[03-phase1-backend-contracts.md](./design/03-phase1-backend-contracts.md)）拆解各阶段可跟踪任务。
> **当前研发策略**：**后端与基础设施先行，暂不实现前端页面开发**（`frontend/console` 与 `frontend/admin` 保持初始脚手架状态，待后端服务与 OpenAPI 3.1 契约稳定后另行启动独立前端交付期）。

---

## 进度总览

| 阶段 | 核心定位 | 当前状态 | 关联文档 |
| :--- | :--- | :---: | :--- |
| **Phase 0** | 仓库与多模块工程脚手架初始化 | ✅ 已完成 | [PROPOSAL.md](./PROPOSAL.md) |
| **Phase 1（设计）** | 数据库实体、工程依赖与基建、后端运行契约 | ✅ 已完成 | [01](./design/01-database-design.md) / [02](./design/02-dependencies-and-infra.md) / [03](./design/03-phase1-backend-contracts.md) |
| **Phase 1（编码）** | MVP 基础代理、控制面 API 与过渡记账闭环（纯后端） | 🚧 进行中（已完成 1.1 与 1.2；下一步：1.3 `protocol` 模块） | [03-phase1-backend-contracts.md](./design/03-phase1-backend-contracts.md) |
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
- [x] `hyperllm-common`：实现 `micro-USD` 运算与向上取整工具、AES-256-GCM 加解密封装（`[1B ver][12B IV][cipher+tag]` + AAD）、HMAC-SHA256 工具、Base62 `sk-hl-` Key 生成器、UUIDv7 工具与统一业务异常/错误码枚举
- [x] `hyperllm-domain`：编写 Phase 1 Flyway 脚本 `V1__init_auth_and_tenants.sql`、`V2__init_models_channels_pricing.sql`、`V3__init_wallets_ledger_and_audit.sql`（含 `wallet_ledger` 与 `audit_logs` 按月 `RANGE` 分区与近期分区预创建）
- [x] 在 `backend/domain/pom.xml` 配置 `-Pjooq` Profile，使用 Testcontainers Postgres 16 运行 `V1`–`V3` 生成 jOOQ 代码至 `src/main/generated`
- [x] `hyperllm-domain`：编写纯 Java `record` 领域模型、核心 jOOQ Repository（User/Org/Project/Member/ApiKey/Provider/Channel/Model/Binding/Pricing/Wallet/Ledger）及 Postgres 容器集成测试

### 1.3 `protocol` 模块（Phase 1 同构透传骨架与 Usage 嗅探）
- [ ] 定义 `Canonical IR` 核心抽象模型与协议适配器接口（预留 Phase 3 双向异构转译扩展位）
- [ ] 实现 OpenAI `/v1/chat/completions` 请求预处理（自动按需注入 `stream_options.include_usage=true`）、非流式/SSE 流式 Usage 提取与末帧还原剥离
- [ ] 实现 Anthropic `/v1/messages` 请求校验与原生 SSE 事件流（`message_start`、`content_block_delta`、`message_delta`）Usage 提取
- [ ] 集成 `jtokkit` 实现缺失 Usage 或流中途断连时的 Token 估算兜底，补齐协议单元测试

### 1.4 `console` 开发者控制台后端（端口 `8081`）
- [ ] 实现邮箱 OTP 发送（Redis）与用户注册（同事务建 `users`、`Personal Workspace`、`default` Project、`wallets` 与可配置体验金）
- [ ] 实现 `console` 独立 `HS256` Access JWT + `hl_console_rt` `HttpOnly` Refresh Cookie 家族轮换防重放与登出吊销
- [ ] 实现组织/成员/邀请与 `org_members + project_members` 双层 RBAC 权限校验器
- [ ] 实现 Project CRUD、API Key 签发（仅创建返一次明文）/启停/吊销、`GET /api/v1/models`、钱包与账本分页查询及 Playground `sk-hl-sb-...` 临时凭证签发
- [ ] 所有配置写接口在事务提交后主动更新/清理对应 Redis L2 Key；配置 RFC 9457 `ProblemDetail` 全局异常处理与 `springdoc-openapi` OpenAPI 3.1 导出

### 1.5 `admin` 运营后台后端与 Phase 1 异步账本消费（端口 `8082`）
- [ ] 实现首个 `SUPER_ADMIN` 环境变量启动器引导，以及 `admin` 独立 `HS256` JWT + 可配置 `hyperllm.admin.mfa.required` TOTP 绑定/校验
- [ ] 实现 Providers 查询、Channels CRUD（AES-256-GCM 凭证加密、脱敏返回）与 `/probe` 真实上游连通性探针
- [ ] 实现 Virtual Models CRUD、Model-Channel Bindings 配置、版本化 `pricing_rules` 发布（校验生效时间窗不重叠）
- [ ] 实现租户状态管理与 Phase 1 过渡测试充值接口（`/api/v1/admin/orgs/{orgId}/wallet/topup`，同事务更新 `wallets` + `wallet_ledger` 并刷 Redis）
- [ ] 实现 Kafka `llm.billing.settlement` 消费者：幂等写入 `wallet_ledger`（`ON CONFLICT (idempotency_key, created_at) DO NOTHING`）并扣减 `wallets`

### 1.6 `gateway` 数据面 WebFlux 网关（端口 `8080`）
- [ ] 配置 `spring.main.web-application-type=reactive`、`spring.flyway.enabled=false`、小容量 Hikari 冷回源连接池与按供应商隔离的 Reactor Netty `HttpClient`
- [ ] 实现 `Caffeine L1 -> Lettuce Reactive Redis L2 -> SingleFlight + 虚拟线程/boundedElastic 调 domain 回源` 三级配置与钱包加载链路
- [ ] 接入 Kafka `cdc.gateway.config`（每实例独立 Consumer Group）消费 Debezium 事件，亚秒级驱逐 Caffeine L1
- [ ] 实现入口鉴权（`sk-hl-...` HMAC 与 `sk-hl-sb-...` Sandbox Redis Token）、IP/模型白名单校验与 Redis RPM + 并发限流
- [ ] 实现 `priority_tier + weight` 加权随机路由与最多 2 次首字节前失败重试（`429`/`5xx`/超时）
- [ ] 实现 `/v1/chat/completions`、`/v1/messages` 同构透传与 `/v1/models`，接入简化版 Redis Lua 扣费（先通用赠金后现金）并异步发射 `llm.billing.settlement` 与 `llm.request.events`
- [ ] 编写 WireMock + Testcontainers 端到端集成测试，通过 `curl`/HTTP 脚本验证完整调用、CDC 失效、故障重试与账本落盘

---

## Phase 2：商业化计费、完整 CDC/Outbox 与 OLAP 管道闭环

- [ ] 编写并迁移 Flyway `V4__init_commercial_billing_outbox.sql`（`payment_orders`, `resource_packs`, `vouchers`, `redemption_codes`, `bonus_grants`, `org_model_pricing_overrides`, `notification_endpoints`, `billing_alert_rules`, `outbox_events`）及刷新 `-Pjooq`
- [ ] 升级 `gateway` Redis Lua 引擎为完整版 `Pre-hold / Settle` 原子冻结与流式多退少补（含 `hold_id` ZSET 延迟回收队列与长推理心跳续期）
- [ ] 实现三顺位确定性抵扣引擎（1. `resource_packs` Token/次 -> 2. 按到期时间优先 `bonus_grants` -> 3. `cash` 余额）与大客户单模型协议价覆盖
- [ ] 完善四层分布式限流（补充 TPM 滑动窗口、Organization 并发池保护与 Channel 上游自身保护阈值）
- [ ] 部署 `infra/debezium/hyperllm-outbox-connector.json`（Outbox Event Router），实现 `admin` 幂等消费 `cdc.wallet.*` / `cdc.pack.*` / `cdc.bonus.*` 加额同步 Redis
- [ ] 上线 `admin` 周期性准实时余额对账任务（Redis vs Postgres + Kafka Lag 巡检与差异告警）
- [ ] 在 `console` 接入支付抽象层，对接 Stripe、支付宝、微信支付下单与验签回调，以及兑换码核销/资源包购买
- [ ] 落地 ClickHouse `llm_request_events` 与 `mv_usage_hourly` 批写摄入，在 `console` / `admin` 开放聚合用量与实时毛利查询 API
- [ ] 落地低余额阈值与 Project 月度软/硬预算超限 Webhook / 邮件告警派发

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
