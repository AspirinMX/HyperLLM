# HyperLLM

[English](./README.md) | 简体中文

**HyperLLM**（`app.pagefault.hyperllm`）是一个面向商用场景的多租户 LLM API 网关与计费管理平台，后端基于 **Java 21 LTS**、**Spring Boot 3.5**、**PostgreSQL 16（Flyway + jOOQ）**、**Redis 7**、**Apache Kafka（KRaft + Debezium CDC）** 与 **ClickHouse 24** 构建。

## 工程模块结构

| 模块 | 默认端口 | 技术栈与职责 |
| :--- | :---: | :--- |
| `backend/common` | — | `micro-USD` 整形精度计算、`UuidV7`、分页与错误码、`AesGcmCipher`、`HmacSha256`、`ApiKeyGenerator` |
| `backend/domain` | — | Flyway `V1`–`V3` 迁移脚本、按月分区账本/审计表、`-Pjooq` 生成代码与 jOOQ 仓储层 |
| `backend/protocol` | — | `Canonical IR`、OpenAI/Anthropic 协议适配、SSE Usage 提取与 `jtokkit` 估算兜底 |
| `backend/gateway` | `8080` | WebFlux 响应式数据面（Caffeine L1 + Redis L2）、Debezium L1 失效、分级权重路由与 Lua 限流扣费 |
| `backend/console` | `8081` | 开发者控制台后端（Spring MVC + 虚拟线程）、多租户/项目 RBAC、API Key 与钱包明细 |
| `backend/admin` | `8082` | 运营管理后台（Spring MVC + 虚拟线程）、渠道/模型/定价管理、TOTP MFA 与异步账本消费者 |
| `frontend/console` | `3000` | Next.js 16 + React 19 开发者控制台前端脚手架（后端契约稳定后推进） |
| `frontend/admin` | `3001` | Next.js 16 + React 19 运营后台前端脚手架（后端契约稳定后推进） |

## 快速开始

### 1. 初始化环境变量

```bash
cp .env.example .env
```

### 2. 启动本地基础设施

```bash
# 默认核心底座：Postgres 16（开启逻辑复制）、Redis 7（AOF）、Kafka 3.9 KRaft 及 Topic 自动初始化
docker compose -f infra/docker-compose.yaml up -d

# 可选 CDC 与 OLAP 链路：Debezium Connect + ClickHouse 24
# 提示：请先启动一次 console 或 admin 完成 Flyway 建表，再拉起 pipeline 以自动创建 Filtered Publication
docker compose -f infra/docker-compose.yaml --profile pipeline up -d

# 可选可观测性与调试工具：OTel Collector (:4317/:4318)、Prometheus (:9090)、Grafana (:3100)、Kafka UI (:8088)
docker compose -f infra/docker-compose.yaml --profile observability --profile tools up -d
```

### 3. 编译与测试后端

```bash
# 全模块编译与纯单元测试（无需 Docker）
./backend/mvnw -f backend/pom.xml test

# 全流程验证（含 Testcontainers PostgreSQL 16 集成测试与 Boot 可执行包构建）
./backend/mvnw -f backend/pom.xml verify

# 变更 Flyway SQL 后重新生成 jOOQ 代码（需 Docker）
./backend/mvnw -f backend/pom.xml compile -pl common,domain -Pjooq
```

## 相关文档

- [系统规划蓝图（PROPOSAL）](./docs/PROPOSAL.md)
- [分阶段实施 TODO 清单](./docs/TODO.md)
- [数据库实体详细设计](./docs/design/01-database-design.md)
- [工程依赖与基础设施编排设计](./docs/design/02-dependencies-and-infra.md)
- [后端认证、REST API 与 Gateway 运行契约](./docs/design/03-phase1-backend-contracts.md)

## 开源协议

基于 [Apache License 2.0](./LICENSE) 协议开源。
