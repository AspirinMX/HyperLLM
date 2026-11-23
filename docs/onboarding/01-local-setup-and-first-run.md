# 第 1 课：环境搭建、基础设施启动与首次全链路跑通

> **本课目标**：把 HyperLLM 在本机真正跑起来——从配置 `.env`、按正确顺序启动 Docker Compose 基础设施，到运行三后端 + 双前端，并用自动化测试与活栈冒烟脚本验证整条链路。

---

## 1. 本地前置工具检查

开始前请确认本机具备以下环境：

| 工具 | 最低版本要求 | 用途 | 验证命令 |
| :--- | :--- | :--- | :--- |
| **JDK** | **Java 21 LTS** | 后端全部 6 个 Maven 模块（使用虚拟线程与 `record`） | `java -version` |
| **Docker & Compose** | Docker Engine + Compose v2 | 本地中间件（PG/Redis/Kafka/Debezium/ClickHouse）与 Testcontainers | `docker compose version` |
| **Node.js & pnpm** | Node 20+、`pnpm@12.21.0` | 双独立 Next.js 16 前端应用（`frontend/console`、`frontend/admin`） | `node -v && pnpm -v` |
| **bash / curl / jq** | 常见版本（Windows 推荐 Git Bash 或 WSL） | 执行 `scripts/e2e/phase1-smoke.sh` 与 `phase2-smoke.sh` | `curl --version && jq --version` |

> **Maven 无需单独安装**：仓库在 `backend/` 下内置了 Maven Wrapper（`./backend/mvnw` / `backend\mvnw.cmd`，绑定 Apache Maven 3.9.16）。

---

## 2. 初始化 `.env` 与理解核心环境变量

在仓库根目录执行：

```bash
cp .env.example .env
```

不要盲目跳过 `.env.example`，先认清下面这 5 组关键变量（它们在后续排查问题时经常出场）：

### ① 加密与双域 JWT 密钥（生产环境必须替换）
- `HYPERLLM_AES_MASTER_KEY`：Base64 编码的 **精确 32 字节** AES-256-GCM 主密钥，用于加密上游渠道 API Key 与 Admin TOTP 密钥（`console`/`admin`/`gateway` 必须一致）。
- `HYPERLLM_KEY_PEPPER_V1`（以及可选的 `HYPERLLM_KEY_PEPPER_V2`）：客户 `sk-...` API Key 做 `HMAC-SHA256` 摘要的服务端 Pepper；支持双 Pepper 平滑轮换。
- `HYPERLLM_CONSOLE_JWT_SECRET` 与 `HYPERLLM_ADMIN_JWT_SECRET`：各自 ≥ 32 字节的 `HS256` 签名密钥。**二者必须严格不同**。
- `HYPERLLM_VOUCHER_CODE_PEPPER`：兑换码 HMAC 摘要 Pepper；一旦上线不可随意变更，否则旧兑换码将无法核销。

### ② 本地开发便捷开关（切勿在生产开启）
- `HYPERLLM_OTP_DEBUG_ECHO=true`：`console` 注册发送邮箱验证码时，把 6 位验证码直接回显在 `SendEmailCodeResponse.debugCode` 中，免去配置真实 SMTP 发信。
- `HYPERLLM_ADMIN_MFA_REQUIRED=false`：跳过 `admin` 登录的 TOTP 两步校验（默认 `application.yml` 为 `true`，`.env.example` 为 `false` 方便本地启动）。
- `HYPERLLM_BOOTSTRAP_ADMIN_EMAIL` / `HYPERLLM_BOOTSTRAP_ADMIN_PASSWORD`：`admin` 启动时由 `SuperAdminBootstrapRunner` 自动幂等创建首个 `SUPER_ADMIN` 账号。

### ③ 支付渠道开关与汇率
- `HYPERLLM_STRIPE_ENABLED` / `HYPERLLM_ALIPAY_ENABLED` / `HYPERLLM_WECHAT_PAY_ENABLED`：默认全部 `false`，无需真实商户密钥即可正常启动；`HYPERLLM_FX_CNY_PER_USD`（默认 `0.14`）在下单瞬间锁定到 `payment_orders.fx_rate`。

### ④ 分析、告警与对账开关
- `HYPERLLM_ANALYTICS_ENABLED=true`：开启 `admin` 内的 Kafka 遥测摄入与 ClickHouse 小时版本化聚合。
- `HYPERLLM_ALERTS_ENABLED=true` / `HYPERLLM_RECONCILIATION_ENABLED=true`：开启告警采样投递与自动对账作业。
- `HYPERLLM_ALERTS_ALLOW_HTTP` / `HYPERLLM_ALERTS_ALLOW_LOOPBACK`：默认均为 `false`（生产级 SSRF 防护，强制 HTTPS 且拒绝回环/内网地址）；本地联调 Webhook 时可临时设为 `true`。

---

## 3. 全栈默认端口速查表

| 服务 | 端口 | 所属层 | 备注 |
| :--- | :---: | :--- | :--- |
| `frontend/console` | `3000` | 开发者前端 | Next.js 16，同源代理 `/api/v1/*` 与 `/api/gateway/v1/*` |
| `frontend/admin` | `3001` | 运营后台前端 | Next.js 16，同源代理 `/api/v1/admin/*` |
| `backend/gateway` | `8080` | 数据面网关 | WebFlux Netty，`/v1/chat/completions`、`/v1/messages`、`/v1/models` |
| `backend/console` | `8081` | 开发者控制面 | MVC + 虚拟线程，Swagger UI：`/swagger-ui.html` |
| `backend/admin` | `8082` | 运营控制面 + Worker | MVC + 虚拟线程，Swagger UI：`/swagger-ui.html` |
| PostgreSQL 16 | `5432` | Core 基础设施 | 开启 `wal_level=logical` |
| Redis 7 | `6379` | Core 基础设施 | 开启 `--appendonly yes` |
| Apache Kafka 3.9 | `9092` | Core 基础设施 | KRaft 模式（无 ZooKeeper），自动执行 `kafka-init` 建 Topic |
| ClickHouse 24 | `8123` / `9000` | `pipeline` Profile | HTTP (`8123`) / Native (`9000`) |
| Debezium Connect | `8083` | `pipeline` Profile | 注册 Config CDC 与 Outbox 两个 Connector |
| WireMock | `8099` | `tools` Profile | 模拟上游 OpenAI 响应（供 E2E 冒烟脚本使用） |
| Kafka UI | `8088` | `tools` Profile | 可视化查看 Kafka Topic 与消息 |
| OTel Collector | `4317` / `4318` | `observability` Profile | gRPC / HTTP OTLP 链路接收 |
| Prometheus / Grafana | `9090` / `3100` | `observability` Profile | Grafana 错峰在 `3100` 避开前端 `3000`/`3001` |

---

## 4. 启动基础设施与「必须遵守的启动顺序」

`infra/docker-compose.yaml` 采用 4 层 Profile 编排：
- **默认（无 profile）**：`postgres`、`redis`、`kafka`、`kafka-init`
- **`--profile pipeline`**：`clickhouse`、`debezium`、`debezium-init`
- **`--profile tools`**：`kafka-ui`、`wiremock`
- **`--profile observability`**：`otel-collector`、`prometheus`、`grafana`

### ⚠️ 新手必看第一坑：Debezium 必须在 Flyway 建表之后注册！

如果你从**全新空数据库**起步，直接一口气执行 `docker compose --profile pipeline up -d`，`debezium-init` 会在 PostgreSQL 里还没有 `organizations` / `outbox_events` 等业务表之前尝试创建 Filtered Publication（`pub_hyperllm_config` 与 `pub_hyperllm_outbox`），可能报错或建槽不完整。

**标准冷启动 4 步顺序**：

```bash
# 第 1 步：启动核心三件套（Postgres + Redis + Kafka + Topic 初始化）与调试工具（含 WireMock）
docker compose --env-file .env -f infra/docker-compose.yaml --profile tools up -d

# 第 2 步：先启动 console 或 admin 任意一个，让 Flyway 自动执行 V1–V8 建表迁移
# （先编译安装本地模块，或直接 spring-boot:run）
./backend/mvnw -f backend/pom.xml -pl console -am spring-boot:run
```

看到 `console` 日志打印 `Successfully applied ... migrations` 并监听 `8081` 后：

```bash
# 第 3 步：再拉起 pipeline（ClickHouse + Debezium Connect + 自动注册两个 Connector）
docker compose --env-file .env -f infra/docker-compose.yaml --profile pipeline up -d

# 验证两个 Debezium Connector 状态均为 RUNNING
curl -s http://localhost:8083/connectors?expand=status | jq
```

```bash
# 第 4 步（可选）：如需本地查看 Prometheus / Grafana / OTel 链路
docker compose --env-file .env -f infra/docker-compose.yaml --profile observability up -d
```

---

## 5. 启动三大后端服务与双前端应用

### 后端三服务（建议开三个独立终端）

在加载 `.env` 的 Shell 中（bash 可用 `set -a; source .env; set +a`）：

```bash
# 终端 1：Console 控制面 (:8081)
HYPERLLM_OTP_DEBUG_ECHO=true \
./backend/mvnw -f backend/pom.xml -pl console spring-boot:run

# 终端 2：Admin 运营控制面 + 账本消费者 + 对账/告警/分析作业 (:8082)
HYPERLLM_ADMIN_MFA_REQUIRED=false \
./backend/mvnw -f backend/pom.xml -pl admin spring-boot:run

# 终端 3：Gateway 数据面网关 (:8080)
./backend/mvnw -f backend/pom.xml -pl gateway spring-boot:run
```

> **提示**：首次单独运行子模块前，先在仓库根目录跑一次 `./backend/mvnw -f backend/pom.xml test-compile` 或 `install -DskipTests`，确保 `common` / `domain` / `protocol` 安装到本地 Maven 缓存。

### 前端双应用（可选，按需启动）

```bash
# 终端 4：开发者控制台 (:3000)
cd frontend/console
pnpm install
pnpm dev

# 终端 5：运营管理后台 (:3001)
cd frontend/admin
pnpm install
pnpm dev
```

启动后访问：
- 开发者门户与控制台：`http://localhost:3000`
- 运营管理后台：`http://localhost:3001`（使用 `.env` 中的 `HYPERLLM_BOOTSTRAP_ADMIN_EMAIL` / `HYPERLLM_BOOTSTRAP_ADMIN_PASSWORD` 登录）
- Swagger UI：`http://localhost:8081/swagger-ui.html` 与 `http://localhost:8082/swagger-ui.html`

---

## 6. 跑通自动化测试与活栈冒烟脚本

在开始改任何代码前，建议先跑一遍以下 3 类验证，确认本机工具链 100% 绿色：

### ① 纯单元测试（无需 Docker，速度最快）

```bash
./backend/mvnw -f backend/pom.xml test
```

Maven Surefire 默认排除 `@Tag("integration")`，只运行 `micro-USD` 运算、加解密、协议适配、定价与三顺位分摊、路由选择等纯内存单测。

### ② 全量编译 + Testcontainers 集成测试（需要 Docker）

```bash
./backend/mvnw -f backend/pom.xml verify
```

Maven Failsafe 会自动拉起 PostgreSQL 16、Redis 7、Kafka、ClickHouse、WireMock 容器，运行所有 `*IT.java` 与 `*IntegrationTest.java`，完整覆盖 Flyway 迁移、并发 Pre-hold 防超支、Outbox 批写与准入闸门、ClickHouse 版本化聚合、告警投递与自动对账。

### ③ 活栈端到端冒烟脚本（验证真实跨进程协作）

当核心基础设施 + `pipeline` + `tools`（WireMock）以及 `console` / `admin` / `gateway` 都启动后，运行：

```bash
# Phase 1 基础闭环验证
bash scripts/e2e/phase1-smoke.sh

# Phase 2 完整商业化闭环验证（含故障重试、SSE、项目预算、兑换码、ClickHouse 报表、告警、对账、CDC Key 吊销）
bash scripts/e2e/phase2-smoke.sh
```

当你看到 `SMOKE PASSED` 输出，说明你已经在本地完整跑通了整个 HyperLLM 分布式链路！

---

**下一课**：[第 2 课：工程结构、模块依赖与架构红线](./02-repo-architecture-and-invariants.md)
