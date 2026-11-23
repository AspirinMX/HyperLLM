# 第 8 课：测试体系、故障排查 Runbook 与常见二次开发实战

> **本课目标**：把这套手册变成你日常开发的工具箱——清楚每一层测试跑什么、遇到高频线上/本地报错怎么秒级定位，以及新增表/接口/协议/支付渠道时的标准操作清单。

---

## 1. 四层测试金字塔与常用命令

| 层级 | 运行命令 | 需要 Docker？ | 覆盖什么 |
| :--- | :--- | :---: | :--- |
| **L1 纯单元测试** | `./backend/mvnw -f backend/pom.xml test` | 否 | `MicroUsd`/`CostMath`、AES/HMAC/ApiKey、Protocol Adapter、`PricingResolver`、`EntitlementAllocator`、`ChannelSelector`、`OutboxAdmissionGate`、支付金额与验签 |
| **L2 Testcontainers 集成测试** | `./backend/mvnw -f backend/pom.xml verify`（可加 `-pl domain` / `-pl gateway` / `-pl console` / `-pl admin` 定向跑） | 是 | Flyway `V1`–`V8` 迁移与约束、jOOQ 仓储、50 并发 `PreHoldServiceIT`、`LoadTenantScriptIT`、`LedgerHealthGateIT`、`GatewayE2EIntegrationTest`、`SettlementPipelineIT`、`PaymentFlowIT`、`AnalyticsPipelineIT`、`AlertingIT`、`ReconciliationIT` |
| **L3 活栈 E2E 冒烟脚本** | `bash scripts/e2e/phase1-smoke.sh` / `bash scripts/e2e/phase2-smoke.sh` | 是（Compose 全栈） | 真实跨进程联动（PG + Redis + Kafka + Debezium + ClickHouse + WireMock + 三后端） |
| **L4 前端四项门禁** | `pnpm lint && pnpm exec tsc --noEmit && pnpm build` | 否 | 路由类型、TS 严格校验、Next.js 16 `cacheComponents` 预渲染构建 |

> **定向加速技巧**：修改单个集成测试时不必跑全仓 `verify`，可以用：
> ```bash
> ./backend/mvnw -f backend/pom.xml failsafe:integration-test -pl gateway -Dit.test=PreHoldServiceIT
> ```

---

## 2. 高频故障排查 Runbook（新手最常遇见的 7 个坑）

### 坑 1：启动 `debezium-init` 后 Connector 状态不是 `RUNNING`，或配置修改后 Gateway 缓存不失效
- **排查**：
  ```bash
  curl -s http://localhost:8083/connectors?expand=status | jq
  ```
- **常见原因**：先起了 `--profile pipeline`，后起 `console`/`admin`，导致 `debezium-init` 注册时业务表还未由 Flyway 创建。
- **解法**：确认 `console` 或 `admin` 已成功跑完 Flyway 后，重新运行一次 `docker compose --env-file .env -f infra/docker-compose.yaml --profile pipeline up -d --force-recreate debezium-init`。

### 坑 2：Gateway 返回 `503 LEDGER_BACKLOG`（带 `Retry-After`）
- **含义**：`admin` 的 `LedgerLagMonitorJob` 检测到账本消费积压进入 `DEGRADED`，且当前请求的组织在 Redis 里没有热钱包键，Gateway 拒绝盲目从落后的 PG 重建余额。
- **排查**：
  1. 检查 `backend/admin` 是否正在运行、`BillingSettlementConsumer` 是否在消费 `cdc.billing`；
  2. 检查 `hyperllm-outbox-connector` 是否处于 `RUNNING`；
  3. 查 Redis `GET gw:health:ledger-lag` 查看当前 lag 状态。

### 坑 3：Gateway 返回 `503 OUTBOX_UNAVAILABLE`
- **含义**：`OutboxAdmissionGate` 触发了反压闸门（内存队列水位持续 > 50% 达 30 秒，或近 1 分钟写库失败率 > 1%）。
- **排查**：检查 PostgreSQL 连接池健康度、慢查询、`outbox_events` 行数以及 Gateway 日志中 `OutboxWriter` 的数据库异常。待写库恢复后闸门会自动放开。

### 坑 4：Gateway 返回 `503 PROJECT_BUDGET_UNAVAILABLE`
- **含义**：该 Project 设置了月度硬预算上限（`monthlyHardCapMicros`），且当月在 `project_budget_months` 中因异常结算被标为 `UNVERIFIED`。
- **解法**：由运营核实该 Project 当月实际消耗后，调用 `POST /api/v1/admin/reconciliation/orgs/{orgId}/projects/{projectId}/budget/{YYYY-MM}/resolve` 提交 `confirmedMicros` 恢复为 `CONFIRMED`。

### 坑 5：修改 `V*.sql` 后编译报 jOOQ 字段找不到，或有人手改了 `src/main/generated`
- **解法**：回滚对 `backend/domain/src/main/generated` 的手动改动，执行：
  ```bash
  ./backend/mvnw -f backend/pom.xml compile -pl common,domain -Pjooq
  ```

### 坑 6：前端刷新页面偶发 `401 AUTH_TOKEN_REUSE_DETECTED` 并被踢回登录页
- **常见原因**：某个新写的组件绕过了 `src/lib/api/client.ts` 的 `token-store.ts` 单飞锁，并发触发了多次 `POST /api/v1/auth/refresh`，旧 Cookie 被重放检测命中。
- **解法**：统一收口到 `src/lib/api/endpoints.ts`，绝不在组件或服务端重复并发调 `/refresh`。

### 坑 7：本地测试 Webhook 告警时报错拒绝投递
- **原因**：`WebhookSender` 默认启用生产级 SSRF 防护（强制 `https://`、禁止重定向、禁止回环与内网 IP）。
- **解法**：仅在本地联调环境设置 `HYPERLLM_ALERTS_ALLOW_HTTP=true` 与 `HYPERLLM_ALERTS_ALLOW_LOOPBACK=true`（**生产环境严禁开启**）。

---

## 3. 常见二次开发实战 Checklist

### 场景 A：新增数据库表或字段
- [ ] 在 `backend/domain/src/main/resources/db/migration/` 新增下一个版本号的 `V*__*.sql`（遵循 UUIDv7 主键、`TIMESTAMPTZ` UTC、`VARCHAR + CHECK` 枚举、`version` 乐观锁、`deleted_at` 软删除）
- [ ] 运行 `./backend/mvnw -f backend/pom.xml compile -pl common,domain -Pjooq` 重新生成 jOOQ
- [ ] 更新 `domain/model/` 的 `record`、`domain/repository/` 接口与 `Jooq*Repository` 实现
- [ ] 如需同步到 Gateway 热缓存，评估是否加入 `infra/debezium/hyperllm-config-connector.json` 的 `table.include.list` 及 `DebeziumConfigCdcConsumer`
- [ ] 在 `backend/domain/src/test/` 补充容器集成测试并跑通 `verify -pl domain`

### 场景 B：新增 Console 或 Admin REST 接口
- [ ] 在对应模块的 `dto/` 定义不可变 `record`（带 Bean Validation 注解，金额一律 `long ...Micros`）
- [ ] 在 `controller/` 添加路由；写操作校验 `version` 乐观锁，幂等写接口收 `Idempotency-Key`
- [ ] **权限校验**：Console 调 `ConsoleRbacService` 校验 Org/Project 权限；Admin 调 `AdminRequestContext.requireRole(...)` 显式列出允许角色
- [ ] 配置变更后通过 `AfterCommitRunner` 清理/同步对应 Redis L2 键（绝不在未提交事务内删缓存）
- [ ] 同步更新 [docs/openapi/console-api.yaml](../openapi/console-api.yaml) 或 [admin-api.yaml](../openapi/admin-api.yaml)
- [ ] 若前端需要消费，在对应前端的 `src/lib/api/types.ts` 与 `endpoints.ts` 封装并跑通前端四项门禁

### 场景 C：扩展新的上游 LLM 协议（Phase 3 方向）
- [ ] 在 `backend/protocol` 实现新的 `ProtocolAdapter`（如 Gemini `generateContent` / OpenAI `/v1/responses` / `/v1/embeddings` / `/v1/rerank`），并在 `ProtocolRegistry` 注册
- [ ] 实现请求解析（提取 `model`、`stream`、`max_tokens` 与估算输入 Token）、流式 `StreamUsageTracker` 与 `ProtocolErrorFormatter` 错误映射
- [ ] 如涉及异构跨协议转译（如 Anthropic 入站 → OpenAI 出站），通过 `CanonicalChatRequest` / `CanonicalMessage` IR 完成请求与 SSE 状态机双向映射
- [ ] 在 `GatewayController` 增加端点路由，并在 `docs/openapi/gateway-api.yaml` 补充契约

### 场景 D：扩展新的支付渠道
- [ ] 在 `PaymentChannel` 枚举及对应 Flyway `CHECK` 约束增加渠道名
- [ ] 在 `backend/console/.../payment/` 实现 `PaymentGateway` 接口（下单构造跳转/二维码指令 + 严格的回调验签与防重放）并在 `PaymentGatewayRegistry` 注册
- [ ] 在 `PaymentWebhookController` 补齐该渠道期望的成功应答格式
- [ ] 补充验签失败、金额不符、回调重放与并发确认的集成测试（参照 `PaymentFlowIT`）

---

## 4. 下一步阅读（进阶设计原稿）

当你已经跑通本手册全部 8 课、准备深度改造某个子系统或推进 Phase 3 / Phase 4 时，可以按需查阅原始设计文档：
- 全景规划与 Phase 3/4 蓝图：[docs/PROPOSAL.md](../PROPOSAL.md) · [docs/TODO.md](../TODO.md)
- 数据库与基建原稿：[01-database-design.md](../design/01-database-design.md) · [02-dependencies-and-infra.md](../design/02-dependencies-and-infra.md) · [03-phase1-backend-contracts.md](../design/03-phase1-backend-contracts.md)
- Phase 2 计费与数据管道专题：[04-phase2-design.md](../design/04-phase2-design.md) · [04-04-settlement-pipeline.md](../design/04-04-settlement-pipeline.md) · [04-05-payment.md](../design/04-05-payment.md) · [04-06-rate-limit-budget.md](../design/04-06-rate-limit-budget.md) · [04-07-analytics.md](../design/04-07-analytics.md) · [04-08-alerts-reconcile.md](../design/04-08-alerts-reconcile.md)
- 前端设计规范红线：[docs/FRONTEND_DESIGN_SPEC.md](../FRONTEND_DESIGN_SPEC.md)
- OpenAPI 3.1 接口契约：[docs/openapi/README.md](../openapi/README.md)
