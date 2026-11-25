<div align="center">

# ⚡ HyperLLM

**企业级 LLM API 网关，支持主流大模型协议与无缝跨协议转换，针对高并发与高可用深度优化。**

<sub>An enterprise-level LLM API gateway that supports mainstream API protocols and seamless inter-protocol conversion; highly optimized for high concurrency and high availability.</sub>

<p>
  <a href="./README.md">English</a> ·
  <a href="./README_zh-CN.md"><strong>简体中文</strong></a>
</p>

<p>
  <img src="https://img.shields.io/badge/Java-21%20LTS-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21 LTS" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3.5" />
  <img src="https://img.shields.io/badge/Next.js-16%20%2B%20React%2019-000000?style=flat-square&logo=nextdotjs&logoColor=white" alt="Next.js 16 + React 19" />
  <img src="https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL 16" />
  <img src="https://img.shields.io/badge/Redis-7%20Lua-DC382D?style=flat-square&logo=redis&logoColor=white" alt="Redis 7" />
  <img src="https://img.shields.io/badge/Apache%20Kafka-3.9%20KRaft-231F20?style=flat-square&logo=apachekafka&logoColor=white" alt="Kafka 3.9" />
  <img src="https://img.shields.io/badge/ClickHouse-24-FFCC01?style=flat-square&logo=clickhouse&logoColor=black" alt="ClickHouse 24" />
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square" alt="Apache 2.0 License" /></a>
</p>

<p>
  <a href="#快速开始">快速开始</a> ·
  <a href="#开箱即用的-sdk-调用示例">SDK 示例</a> ·
  <a href="#文档导航">文档导航</a>
</p>

</div>

---

## 快速开始

### 1. 初始化环境变量与核心基础设施

```bash
# 复制本地环境变量模板（生产部署前务必替换所有密钥！）
cp .env.example .env

# 启动核心底座（PostgreSQL 16 逻辑复制、Redis 7 AOF、Kafka 3.9 KRaft）与调试工具
docker compose --env-file .env -f infra/docker-compose.yaml --profile tools up -d
```

### 2. 启动后端服务（Java 21 LTS）

```bash
# 全模块编译并运行纯单元测试（无需 Docker）
./backend/mvnw -f backend/pom.xml test

# 先启动 Console (:8081) 自动执行 Flyway V1–V9 建表迁移
HYPERLLM_OTP_DEBUG_ECHO=true ./backend/mvnw -f backend/pom.xml -pl console spring-boot:run

# Flyway 建表完成后，拉起 Debezium CDC + ClickHouse 管道
docker compose --env-file .env -f infra/docker-compose.yaml --profile pipeline up -d

# 在另外两个终端分别启动 Admin (:8082) 与 Gateway (:8080)
HYPERLLM_ADMIN_MFA_REQUIRED=false ./backend/mvnw -f backend/pom.xml -pl admin spring-boot:run
./backend/mvnw -f backend/pom.xml -pl gateway spring-boot:run
```

> **可选可观测性栈**：追加 `--profile observability` 即可拉起 OTel Collector（`:4317/:4318`）、Prometheus（`:9090`）与 Grafana（`:3100`）。

### 3. 启动双端前端应用（Next.js 16 + pnpm 12）

```bash
# 开发者门户与控制台 -> http://localhost:3000
cd frontend/console && pnpm install && pnpm dev

# 平台运营管理后台 -> http://localhost:3001
cd frontend/admin && pnpm install && pnpm dev
```

### 4. 全链路验证

```bash
# 全量构建 + Testcontainers 容器集成测试（PostgreSQL、Redis、Kafka、ClickHouse、WireMock）
./backend/mvnw -f backend/pom.xml verify
```

> 如需复现网关压测与 `async-profiler` 火焰图剖析，请参阅 [benchmark/README.md](./benchmark/README.md)。

## 开箱即用的 SDK 调用示例

### OpenAI Python SDK

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="sk-your-hyperllm-api-key",
)

stream = client.chat.completions.create(
    model="gpt-6.2-astra",
    messages=[{"role": "user", "content": "用两句话解释 Pre-hold 预占如何防止并发超支。"}],
    stream=True,
)
for chunk in stream:
    if chunk.choices and chunk.choices[0].delta.content:
        print(chunk.choices[0].delta.content, end="", flush=True)
```

### Anthropic 原生 SDK / Claude Code

```bash
# 直接将 Claude Code 或 Anthropic SDK 指向 HyperLLM 网关
export ANTHROPIC_BASE_URL="http://localhost:8080"
export ANTHROPIC_API_KEY="sk-your-hyperllm-api-key"

curl http://localhost:8080/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2026-10-21" \
  -H "content-type: application/json" \
  -d '{
    "model": "claude-opus-5-5",
    "max_tokens": 512,
    "messages": [{"role": "user", "content": "你好，HyperLLM！"}]
  }'
```

## 文档导航

- **[新手接管实战手册（8 课循序渐进）](./docs/onboarding/README.md)** — 从零环境搭建、工程红线、存储全景、Gateway 请求链路、Redis 权威层与 Lua 脚本，到双控制面、前端 BFF 与排障 Runbook。
- **[OpenAPI 3.1 接口契约文档](./docs/openapi/README.md)** — 可直接导入 Swagger UI / Postman / `openapi-typescript` 的标准规范：[Gateway 数据面](./docs/openapi/gateway-api.yaml) · [Console 开发者控制面](./docs/openapi/console-api.yaml) · [Admin 运营控制面](./docs/openapi/admin-api.yaml)。
- **[压测与火焰图剖析套件](./benchmark/README.md)** — 如何在核隔离环境下复现网关压力测试并抓取 `async-profiler` 火焰图。

## 参与贡献与开源协议

欢迎提交 Issue 与 Pull Request！开发前请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md) 与 [AGENTS.md](./AGENTS.md)。

本项目基于 **[Apache License 2.0](./LICENSE)** 协议开源。
