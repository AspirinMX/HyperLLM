# HyperLLM 项目分阶段实施 TODO 清单

> 本文档依据 [PROPOSAL.md](./PROPOSAL.md) 与第一阶段详细设计文档（[01-database-design.md](./design/01-database-design.md)、[02-dependencies-and-infra.md](./design/02-dependencies-and-infra.md)、[03-phase1-backend-contracts.md](./design/03-phase1-backend-contracts.md)）拆解各阶段可跟踪任务。
> **当前研发策略**：**后端与基础设施先行，暂不实现前端页面开发**（`frontend/console` 与 `frontend/admin` 保持初始脚手架状态，待后端服务与 OpenAPI 3.1 契约稳定后另行启动独立前端交付期）。

---

## 进度总览

| 阶段 | 核心定位 | 当前状态 | 关联文档 |
| :--- | :--- | :---: | :--- |
| **Phase 0** | 仓库与多模块工程脚手架初始化 | ✅ 已完成 | [PROPOSAL.md](./PROPOSAL.md) |
| **Phase 1（设计）** | 数据库实体、工程依赖与基建、后端运行契约 | ✅ 已完成 | [01](./design/01-database-design.md) / [02](./design/02-dependencies-and-infra.md) / [03](./design/03-phase1-backend-contracts.md) |
| **Phase 1（编码）** | MVP 基础代理、控制面 API 与过渡记账闭环（纯后端） | ✅ 已完成（1.1 至 1.6 全部落地；活栈联调入口为 `scripts/e2e/phase1-smoke.sh`） | [03-phase1-backend-contracts.md](./design/03-phase1-backend-contracts.md) |
| **Phase 2** | Redis 权威层、商业化计费、Outbox 与 OLAP 闭环（12 个子阶段） | 🚧 进行中（2.0a 与 2.1–2.3 已完成；2.0b 进行中；2.4 起待开始） | [04-phase2-design.md](./design/04-phase2-design.md) / [04-04](./design/04-04-settlement-pipeline.md) |
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
| **G4** | `pricing == null` 时 `exactCostMicros = 0`，未配置定价的模型被免费使用 | ✅ 已关闭：2.2 在准入阶段拒绝并抛 `MODEL_PRICING_MISSING`（见 [04-02 第 13 节](./design/04-02-entitlements.md)） | 2.2 |
| **G5** | `deduct_wallet.lua` 不钳制下限，Redis 现金可为负；随后 `wallets.cash_balance_micros` 的 `CHECK (>= 0)` 让结算落库失败并无限重试 | ✅ 已关闭：分档预占 + 现金腿下限钳制（见 [04-03 第 16 节](./design/04-03-prehold-settle.md)）；滑动 TTL 落地前仍需补空键守卫 | 2.3 |
| **G6** | `spring.kafka.listener.ack-mode` 未配置（默认 BATCH），而 `BillingSettlementConsumer` 声明了 `Acknowledgment` 参数 → **每条结算消息都抛异常，账本从未被 Kafka 写入** | ✅ 已修复：`admin/application.yml` 增补 `ack-mode: manual`；`SettlementPipelineIT` 现在能守住这条链路 | 2.0 |
| **G7** | `BillingSettlementConsumer.onMessage` 调 `this.ingestIdempotent(...)` 属自调用，`@Transactional` 被代理绕过 → 监听器路径实际无事务（直调测试看不到） | ❌ 未修；已登记为 [方案 D-7](./design/04-phase2-design.md) | 2.5 |
| **G8** | `wallet_ledger` 跨月入账、分区预建作业从未被测过 | ❌ 未修；全仓无任何 `@Scheduled` 作业，已登记为 [方案 D-13](./design/04-phase2-design.md) | 2.5 |

## Phase 2：Redis 权威层、商业化计费与账本闭环

> 阶段划分依据 [阶段二最终设计方案](./design/04-phase2-design.md)，它持有阶段二的基线与决议；机制与验收细节见 [04-04-settlement-pipeline.md](./design/04-04-settlement-pipeline.md)。每个阶段必须声明**依赖**与**验收手段**，依赖未满足不得开工。
> 与旧划分相比的主要变化：**「Redis 权威层」从 2.2/2.3 的脚注提升为独立阶段 2.4**，其后各阶段整体顺延一位；2.5 与 2.6 紧邻，避免同一段事务写两遍。
> 2.4 至 2.6 的机制、时序、配置与验收以 [04-04-settlement-pipeline.md](./design/04-04-settlement-pipeline.md) 为准；本文只保留任务分解与验收手段。

### 阶段地图

| 阶段 | 名称 | 目标 | 依赖 | 状态 |
| :--- | :--- | :--- | :--- | :---: |
| **2.0a** | Phase 1 基线收口 | 关闭 G1 至 G8，固化既有产出 | Phase 1 | ✅ |
| **2.0b** | Phase 2 设计评审 | 落地六条基线、三层归属与事件契约 | 04-02、04-03 | ✅ |
| **2.1** | V4 schema 与领域仓储 | schema、jOOQ、9 组仓储 | Phase 1 | ✅ |
| **2.2** | 定价与三顺位分摊 | 定价解析、分摊器、明细契约 | 2.1 | ✅ |
| **2.3** | 预占、结算与回收 | 分档预占、流式结算、僵尸回收 | 2.1、2.2 | ✅ |
| **2.4** | **Redis 权威层** | 逐批次冻结、滑动 TTL、结算离开 PostgreSQL、事件门禁、控制面读 Redis、全局重建闸门 | 2.0b 至 2.3 | ✅ |
| **2.5** | 结算落库与账本事务 | 逐腿扣库、明细落库、事务边界、折叠恒等、维护作业、对账工单 | 2.4 | ✅ |
| **2.6** | Outbox 投递与生命周期 | 消耗异步批量、发放同事务提交；事件副本与巡检重投；Debezium 投递；水位删除 | 2.5 | ✅ |
| **2.7** | [支付、兑换码与资源包购买](./design/04-05-payment.md) | 下单、验签、回调、核销 | 2.5、2.6 | 设计已确认，待实现 |
| **2.8** | [四层限流与项目预算](./design/04-06-rate-limit-budget.md) | Key、Project、Org、Channel 限流与预算拦截 | 2.4 至 2.6 | 设计已确认，待实现 |
| **2.9** | [ClickHouse 摄入与用量毛利](./design/04-07-analytics.md) | 请求事件入仓、聚合查询 | 2.6、2.7（包销售） | 设计已确认，待实现 |
| **2.10** | [告警、对账与全链路验收](./design/04-08-alerts-reconcile.md) | Webhook、邮件、账务对账与 HTTP 验收 | 2.6 至 2.9 | 设计已确认，待实现 |

### 2.0a Phase 1 基线收口

- [x] 新增 `SettlementPipelineIT`，验证 gateway 发事件、admin 消费落库的跨服务链路（关闭 G1）。
- [x] 将 E2E 的 CDC 步骤改为「吊销 key 后轮询直到 401」的真实断言（关闭 G2）。
- [x] 新增 `scripts/e2e/phase1-smoke.sh` 与 Compose `tools` profile 的 WireMock 上游（关闭 G3）。
- [x] 补 `admin` 的 `spring.kafka.listener.ack-mode: manual`，修复结算消息逐条抛异常的问题（关闭 G6）。
- [x] 回填上方「Phase 1 遗留缺口登记」表中 G1 至 G8 的真实状态。

### 2.0b Phase 2 设计评审

- [x] 评审并确认 [阶段二最终设计方案](./design/04-phase2-design.md)：六条基线、三层数据归属、P1 至 P12 不变量。
- [x] 以方案第 2 节取代 04-00 原 T3 关于「批次余额权威在 PostgreSQL」的表述，并同步修正 04-02 第 13 节与 04-03 第 16 节的口径。
- [x] 确认方案第 11 节的决议：Q1 废除 `gw:ent:ver` / `gw:ent:stale`；Q2 实现积压检测与 TTL 联动；Q3 租户列表内联余额；Q4 按租户整体加载。
- [x] 确认消耗事件契约。现金、赠金、资源包三桶 delta 记录实扣量。`legs[].batchId` 对真实 PACK 和 BONUS 批次必填，CASH 和旧版虚拟赠金使用 `null`。参见[结算事件](./design/04-04-settlement-pipeline.md#31-消费者事务)。

**验收手段**：方案文档合并，且 04-00、04-02、04-03 的冲突口径已消除。

### 2.1 Flyway V4、jOOQ 与领域仓储

- [x] 编写并执行 `V4__init_commercial_billing_outbox.sql`，新增 `payment_orders`、`resource_packs`、`vouchers`、`redemption_codes`、`bonus_grants`、`org_model_pricing_overrides`、`notification_endpoints`、`billing_alert_rules` 和 `outbox_events`，补齐 `wallet_ledger.ref_order_id` 外键。见 [数据库迁移规划](./design/01-database-design.md) 与 [2.1 设计稿](./design/04-01-v4-schema.md)。
- [x] 通过 `-Pjooq` 重新生成代码，补齐 `domain` 的领域模型和仓储（9 个 record、14 个枚举、9 组仓储契约与实现）。
- [x] 通过 PostgreSQL 16 容器测试验证空库初始化、V3 升级、旧账本兼容性以及新表的关键约束（`V4MigrationPathIT`、`V4SchemaConstraintIT`、`CommercialBillingRepositoryIntegrationTest`，共 17 个用例）。

**验收手段**：`./backend/mvnw -f backend/pom.xml verify -pl domain`。

### 2.2 三顺位抵扣与组织模型协议价

- [x] 实现按模型适用范围和到期顺序确定分摊的三顺位抵扣 `资源包（Token/次） → 赠金批次 → 现金`（`EntitlementAllocator`，含部分抵扣与 shortfall）。
- [x] 接入组织级单模型协议价，沿用 `micro-USD` 整数精度与 `MicroUsd` 向上取整规则，输出逐批次分摊明细（`PricingResolver` + `AllocationDetailCodec`）。
- [x] 验证过期或不适用的权益、部分抵扣、多批次抵扣、余额不足与定价生效边界（`EntitlementAllocatorTest`、`PricingResolverTest`、`CommercialBillingRepositoryIntegrationTest`）。

> **偏离登记**：方案第 4 节的批次镜像（`gw:pack:item`、`gw:bonus:item`）在本阶段未落地，导致结算读取 PostgreSQL。该偏离由 **2.4** 收敛（方案 D-2）。

### 2.3 网关预占、流式结算与回收

- [x] 将 `gateway` 的 Redis Lua 计费升级为 `Pre-hold / Settle`，准入时原子预占，收尾时按实测 Usage 或估算用量结算并释放剩余预占（`prehold_admit` / `prehold_settle` / `prehold_release`）。
- [x] 实现长流心跳续期、僵尸预占到期回收，以及结算和释放的幂等处理（心跳、网关惰性回收与双标记幂等已落地；admin 周期兜底归 2.5）。
- [x] 用 Redis 容器测试验证并发请求防超支，以及断连、上游异常和实例退出后的结算与回收（`PreHoldServiceIT`，含 50 并发只放行 40 例）。

> **偏离登记**：本阶段只落地了聚合键 `gw:pack:avail` 与 `gw:pack:held`，未建逐批次镜像；手头脚本均未设置 TTL，心跳也未续期钱包键。三项均由 **2.4** 收敛（方案 D-2、D-3）。

### 2.4 Redis 权威层

> 本阶段完成后，方案 B1「键存活期间 Redis 是唯一权威」才真正成立。机制、时序与配置以 [04-04 第 2 节](./design/04-04-settlement-pipeline.md) 为准。

- [x] **逐批次冻结**：准入携带逐条腿（PACK 带 packId 与 units、BONUS 带 grantId、CASH 无 id），item 键上记 `held_units` / `held_micros`，hold 存 `legs`；结算以 hold 的腿为快照重跑分摊，不再重新查询（D-1、D-2、D-8）。
- [x] **废除三个聚合字段**：`general_bonus_micros`、`gw:pack:avail`、`gw:pack:held`。赠金总额由快照脚本按 `score > now` 现算，连带关闭「TTL 冻结时过期赠金仍可花」的漏洞（D-9 作废）。
- [x] **快照读原语** `prehold_snapshot.lua`：一次往返返回钱包与逐条腿，网关准入、结算重分摊、console 与 admin 单租户查询共用。该脚本先 ZRANGE 再拼 item 键，**与 Redis Cluster 不兼容**，计费实例必须按租户分 cell。
- [x] **加载协议**：统一通过 Lua 原子补齐缺失镜像并设置 TTL，校验未确认实扣与钱包快照版本。
- [x] **滑动 TTL 全续期点**：补 `prehold_renew.lua` 的钱包与批次键（D-3），修正心跳把 orgId 当 projectId 传入（D-10），并实现实例本地续期节流（每键 60 秒最多一次）。
- [x] **事件门禁**补 `packDeltaUnits != 0`，保证纯资源包抵扣也发结算事件（D-4）。
- [x] **控制面读 Redis**：console 与 admin 的单租户查询读取实时快照。admin 租户列表内联账本余额并标注确认状态。
- [x] **健康信号与全局重建闸门**：admin 写 `gw:health:ledger-lag` 与 `:armed`；网关本地缓存 5 秒，DEGRADED 时对触碰到的键 PERSIST，**键缺失则返回 503 `LEDGER_BACKLOG`**（`Retry-After` 取 `drainSeconds`）。积压信号全局共享，监视器失联按 OK 处理并打 P1。
- [x] **重建偏差度量**：`gateway_rebuild_stale_delta_micros` 按现金、赠金和偏差方向统计。余额键已过期时计入缺失指标。降级拒绝重建记录总数，并在日志中保留组织标识。
- [x] 删除 `gw:org:holds`，惰性回收改为按 `gw:holds:exp` 全局扫描。
- [x] 配置项落地：`sliding-ttl.*`、`lag-monitor.*`。

**验收手段**
- 容器测试：长流跨 TTL 后钱包键仍存在；空闲 300 秒后键消失且可从 checkpoint 重建。
- 容器测试：空键哨兵、加载原子性、并发加载只回源一次、**已存在的键不被加载覆盖**。
- 断言：结算路径对 `resource_packs` / `bonus_grants` 的查询次数为 0。
- 断言：纯资源包请求产生一条事件且 `packDeltaUnits` 非零。
- 断言：结算后各腿 `held` 归零，item 余量按到期顺序递减，无超卖。
- 单测：健康信号四种状态映射正确；`armed` 存在但数据过期时按 OK 处理并打 P1。
- 断言：DEGRADED 且键存在时请求仍 200；DEGRADED 且键缺失时返回 `LEDGER_BACKLOG` 且带 `Retry-After`。
- 断言：`admin` 租户列表为常数条 SQL，不随租户数增长。

### 2.5 结算落库与账本事务

> 以 [04-04 第 3 节](./design/04-04-settlement-pipeline.md) 为准。

- [x] **消费者事务边界**：把幂等写入移到独立 Bean 或显式事务模板，消除自调用绕过代理（D-7、G7）。
- [x] **按逐腿明细扣减** `resource_packs.remaining_units` 与 `bonus_grants.remaining_micros`，余量归零且 ACTIVE 时置 `EXHAUSTED`（D-6）。
- [x] **明细落库**：`allocation_detail_json`、`hold_id`、`shortfall_micros`、`settled_after_reclaim`（D-5）。
- [x] **不钳位**：事件只带实扣量，落库一律加法；触发负余额即判错账并告警，不得静默 `GREATEST(0, ...)`。
- [x] **`folded` 落 PostgreSQL**：同一事务内推进 `wallets.last_applied_event_id`；`produced` 留在 `gw:ledger:pos`。
- [x] **定时作业**：僵尸预占周期回收（后台 sweep + 惰性回收）、批次过期 sweeper（只改 PG + 写 `BONUS_EXPIRE`）、`wallet_ledger` 与 `audit_logs` 分区预建（D-13、G8）。

- [x] **已交付预占回收**：向客户端发送首帧前记录交付标记。回收按准入时冻结的仅 prompt 兜底腿扣费，并原子释放其余冻结。
- [x] **投递水位回传**：消费者折叠后推进 `outbox_watermark`。逐笔确认依据账本及认亏审计。
- [x] **对账工单四级阶梯**：L1 重投 outbox，L2 按 outbox 行或可核验副本修复投递，L3 在水位及实时扣款证据允许时补 `ADJUST`，L4 审计认亏。

**验收手段**
- 容器测试：跨月入账、重复事件只落一行、消费者恢复后余额一致。
- 容器测试：逐腿余量扣减与账本 delta 一致；过期 sweeper 只写一次 `BONUS_EXPIRE`。
- 断言：`allocation_detail_json` 非空且可由明细复算出 `chargedMicros`。
- 断言：随机化并发投递下 `fold(wallet_ledger) == wallets` 恒成立。
- 断言：构造乱序投递，验证幂等门禁与退避重试，且不出现负余额。
- 断言：工单在 L4 时间点无条件关闭，且留下审计与 P1。

### 2.6 Outbox 投递与生命周期

> 以 [04-04 第 4 节](./design/04-04-settlement-pipeline.md) 为准。本阶段完成后，网关不再直投 Kafka。

- [x] **单一金额 topic**：connector 的 `route.topic.replacement` 改为 `cdc.billing`，Kafka key = `orgId`，`OutboxAggregateType` 收敛为一个值；`llm.request.events` 仍直投。
- [x] **消耗路径批量写入**：请求路径只入队；单写者每 20ms 或满 200 条批量插入，单批上限 500 行；独立连接池 2 个连接。退避重试，不得因次数上限丢弃，超过 `retry-deadline` 才放弃并计入孤儿指标。
- [x] **事件副本与巡检重投**：结算 Lua 原子保存实扣事件与镜像标识，证据保留一小时。admin 每 30 秒推进工单，结算确认或认亏后清理。
- [x] **准入闸门**：队列满、水位 > 50% 持续 30 秒，或最近一分钟写入至少尝试 50 行且失败率 > 1%，触发实例停止准入并返回 503 与 `Retry-After`。已结算的响应照常返回。
- [x] **停用直投**：删除 `GatewayTelemetryService` 的结算事件直发路径。
- [x] **水位删除作业**：每 30 秒分批删除水位内已确认的事件。水位停滞 24 小时按时间兜底并打 P1，未确认发放保留并重投。
- [x] **既有发放入口**：人工充值和注册赠金先写 outbox，再由消费者按 delta 应用。人工充值返回 HTTP 202 与事件 ID。
- [x] **`V4_2` 迁移**：删 `idx_outbox_events_created`、`outbox_events` 设 `fillfactor = 100`、`wallet_ledger` 增 `shortfall_micros` 与 `settled_after_reclaim`、`wallets` 增 `last_applied_event_id`、新增 `outbox_watermark` 单行表；同步刷新 `-Pjooq`。

**验收手段**
- 容器测试：批量插入行数正确、UUIDv7 单调、无二级索引仍可按区间删除。
- 容器测试：队列水位越线后停止准入并返回 503，恢复后自动放开。
- 断言：结算后 outbox 写失败时响应仍为 200，事件达到 60 秒宽限期后进入下一次巡检。
- 断言：水位推进后表行数收敛到未确认事件数量级，不随时间线性增长。
- 断言：三条路径产生的事件契约一致，消费者无需分支即可处理。

### 2.7 支付、兑换码与资源包购买

- [ ] 在 `console` 实现支付抽象、下单、订单查询和验签回调，支持 Stripe、支付宝和微信支付收款。
- [ ] 交付兑换码核销、资源包购买及相应的 `admin` 运营接口，复用 2.6 的投影链路。
- [ ] 通过沙箱和模拟测试验证支付成功、签名与金额校验、回调重放及兑换码并发核销。

**验收手段**：沙箱与模拟回调测试，覆盖验签失败、金额不符、回调重放、并发核销。

### 2.8 四层限流与项目预算

- [ ] 补齐 API Key 和 Project 的 RPM 与 TPM 滑动窗口，完善 Organization 并发保护和 Channel 上游容量保护。
- [ ] 实现 Project 月度预算检查，软上限触发告警，硬上限在准入时拦截超预算请求。
- [ ] 验证跨分钟限流、并发准入和预算超限的协议错误响应。

**验收手段**：跨分钟限流、并发准入、预算软硬限的协议错误体断言。

### 2.9 ClickHouse 摄入、用量与毛利 API

- [ ] 接通 `llm.request.events` 到 ClickHouse `llm_request_events` 和 `mv_usage_hourly` 的摄入，遵循 ZDR，仅落盘结构化用量元数据。
- [ ] 在 `console` 提供按租户权限过滤的聚合用量查询，在 `admin` 提供毛利查询。
- [ ] 验证重复投递后的指标准确性、明细与聚合的一致性、查询权限和 ZDR 约束。

**验收手段**：重复投递后指标一致性、明细与聚合一致性、ZDR 断言。

### 2.10 告警、对账与全链路验收

- [ ] 上线低余额及 Project 月度软硬预算的 Webhook 和邮件告警，验证冷却期与失败处理。
- [ ] 在 `admin` 周期性对账，按方案 7.5 节的四项职责执行：账本折叠值与快照一致、快照与 Redis 差额可解释、批次之和等于赠金快照、`shortfall` 与 `settledAfterReclaim` 可解释。
- [ ] 对账覆盖 Redis 已扣但尚无账本记录的窗口。按事件证据复用 L1–L4，缺少逐笔证据的差额登记并告警。
- [ ] 补齐可重复执行的 HTTP 联调脚本，覆盖充值或兑换、预占、SSE 调用、结算入账、报表与告警，并回归 Phase 1 的配置 CDC、故障重试和账本落盘。
- [ ] 通过 Maven `test`、`verify` 和 Compose `pipeline` 配置校验，并在联调环境验证并发防超支、重复投递幂等和消费恢复后的余额一致性。

**验收手段**：全链路 HTTP 脚本可重复执行，注入的偏差可定位。有证据的扣款经逐笔工单收敛，缺证差额可查询。

---

### Phase 2 依赖说明

1. **2.4 是 2.5 的硬前置**：逐批次冻结必须先于「结算离开 PostgreSQL」，否则 2.5 落库时没有可信的批次归属。
2. **2.5 与 2.6 必须相邻交付**：2.5 的消费者要回传投递水位，2.6 的 purge 依赖它；两者隔开会让 outbox 表无界增长。
3. **2.6 完成后网关才停止直投 Kafka**：在此之前两条投递通道并存是允许的，因为事件契约与消费者相同、且都能幂等处理；但必须在同一版本内完成切换，不得长期双轨。
4. **数据面依赖 PostgreSQL**。网关按批次写出消耗事件。写入积压触发准入闸门后，新请求返回 503，已结算响应按原结果下发。
5. **2.8 依赖 2.4–2.6**。限流复用 2.4 的 Redis 预占，项目预算的冷恢复依赖 2.5–2.6 的结算证据和折叠。2.7 与 2.8 可以并行。
6. **写方向已在 2.6 分叉**：消耗「先 Redis 后 outbox」、发放「先 outbox 后 Redis」。两条路共用同一事件契约与消费者，差异只在提交方式，见 [04-04 第 1 节](./design/04-04-settlement-pipeline.md)。


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
