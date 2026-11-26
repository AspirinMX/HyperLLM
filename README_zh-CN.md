<div align="center">

# ⚡ HyperLLM

**企业级 LLM API 网关，支持主流大模型协议与跨协议转换，针对高并发与高可用深度优化。**

<sub>An enterprise-level LLM API gateway that supports mainstream API protocols and seamless inter-protocol conversion; highly optimized for high concurrency and high availability.</sub>

<p>
  <a href="./README.md">English</a> ·
  <a href="./README_zh-CN.md"><strong>简体中文</strong></a>
</p>

<p>
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3.5" />
  <img src="https://img.shields.io/badge/Next.js-16-000000?style=flat-square&logo=nextdotjs&logoColor=white" alt="Next.js 16" />
  <img src="https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=white" alt="React 19" />
  <img src="https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL 16" />
  <img src="https://img.shields.io/badge/Redis-7-DC382D?style=flat-square&logo=redis&logoColor=white" alt="Redis 7" />
  <img src="https://img.shields.io/badge/Apache%20Kafka-3.9-231F20?style=flat-square&logo=apachekafka&logoColor=white" alt="Kafka 3.9" />
  <img src="https://img.shields.io/badge/ClickHouse-24-FFCC01?style=flat-square&logo=clickhouse&logoColor=black" alt="ClickHouse 24" />
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square" alt="Apache 2.0 License" /></a>
</p>

<p>
  <a href="#性能压测">性能压测</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#开箱即用的-sdk-调用示例">SDK 示例</a> ·
  <a href="#文档导航">文档导航</a>
</p>

</div>

---

## 性能压测

**网关在 4C8G 单机部署下，保证 200 token/s 的输出速度和 500 ms TTFT 情况下，可稳定维持 5,000 个并发 SSE 连接。**

> 因为网关未区分同协议/跨协议路径，所有经过网关的请求一律转为中间级表示，再请求上游（出于安全考虑），所以下述同协议测量结果与跨协议测量结果相差不大。

> **压测场景**：100K-token 长上下文输入、2,000 输出 token、200 token/s（5 token/帧，单流 400 帧、持续约 10.5s）、20s 均匀 ramp + 60s 闭环稳态窗口；单机严格硬切核隔离，所有有效场景均满足 `%steal = 0.00`、`0 错误`。下表所有 TTFT 均只统计网关净增耗时 **`ΔTTFT = 实测首帧时间 − 500 ms`**（已扣除上游模拟首字延迟）。

### 腾讯云 CVM 服务器（计算型 C6，x86_64）

- **机型配置**：8 vCPU Intel Xeon Platinum 8372C @ 3.20 GHz（4 物理核 × 2 HT）/ 16 GiB 内存（网关硬隔离 4 vCPU / 2 物理核，`6 GiB` G1GC 堆，满足 4C8G 单机资源配额，`nettyEpoll=true`）

| 并发、协议路径与 GC | `ΔTTFT` P50 / P99（网关净增） | YGC / 总暂停 | Full GC | 老年代峰值 | 网关 CPU |
| :--- | --: | --: | --: | --: | --: |
| 3,000 · OpenAI → OpenAI（G1） | +87.6 / +381.4 ms | 8 / 565 ms | **0** | 288 MB | 86.0% |
| 4,000 · OpenAI → OpenAI（G1） | +146.7 / +394.0 ms | 9 / 647 ms | **0** | 234 MB | 87.8% |
| **5,000 · OpenAI → OpenAI（G1）** | **+216.6 / +603.7 ms** | **10 / 833 ms** | **0** | **244 MB** | **89.9%** |

### Hetzner Cost-Optimized `CAX 31`（ARM64）

- **机型配置**：8 vCPU ARM Neoverse-N1 / 16 GiB 内存（网关硬隔离 4 vCPU，`5 GiB` 堆，满足 4C8G 单机资源配额，`nettyEpoll=true`，Thread-per-Core 共享 Epoll + Lettuce 独立 I/O 隔离，Rust 压测套件）

| 并发、协议路径与 GC | RPS / 帧率 | `ΔTTFT` P50 / P99（网关净增） | YGC / 总暂停 | Full GC | 老年代峰值 | 网关 CPU |
| :--- | --: | --: | --: | --: | --: | --: |
| 3,000 · Anthropic → OpenAI（G1） | 275.70 / 110.3k fps | +157.7 / +380.5 ms *(充分预热 +143.4/+237.6)* | 9 / 587 ms | **0** | **91 MB** | 94.3% |
| 3,500 · Anthropic → OpenAI（G1） | 318.80 / 127.5k fps | +182.6 / +315.9 ms | 10 / 671 ms | **0** | **96 MB** | 94.5% |
| **4,000 · Anthropic → OpenAI（G1）** | **360.92 / 144.4k fps** | **+234.4 / +398.8 ms** | **11 / 811 ms** | **0** | **99 MB** | **95.2%** |
| 5,000* · Anthropic → OpenAI（G1，吞吐峰值） | 384.02 / 153.6k fps | +357.1 / +608.1 ms | 13 / 1,227 ms | **0** | 337 MB | 96.7% |

> `*` 在 Hetzner `CAX 31`（4 vCPU Neoverse-N1）上，**4,000 并发**为全速稳态推荐上限（`StreamTPS P50 = 195.6 tok/s`，老年代仅 `99 MB`）；超过 4,000 后该机型触发 `~153.6k fps` CPU 吞吐硬顶，但仍可在 **5,000 并发**下保持 `0 错误 / 0 Full GC` 稳定跑通。
>
> 完整基准报告见 **[`docs/benchmark/README.md`](./docs/benchmark/README.md)** 与 V5 深度复盘 **[`04-v5-p0-optimization-and-reflections.md`](./docs/benchmark/04-v5-p0-optimization-and-reflections.md)**，包含 ARM64 七代版本（`V0 → V5`，49 场景）完整演进、33 份原始交互式 `async-profiler` 火焰图（CPU / Alloc / Lock）、7 张 SVG 图表、3,000 并发堆存活集审计及 G1GC vs Generational ZGC 对比。

## 快速开始

### 1. 初始化环境变量与核心基础设施

```bash
# 复制本地环境变量模板（生产部署前务必替换所有密钥！）
cp .env.example .env

# 启动核心底座
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
- **[性能基准与七代演进报告](./docs/benchmark/README.md)** — Hetzner `CAX 31`（ARM Neoverse-N1）七代版本全演进（`V0 → V5`，49 场景）、33 份原始交互式 `async-profiler` 火焰图（CPU / Alloc / Lock）、7 张 SVG 图表、3,000 并发堆存活集审计、G1GC vs Generational ZGC 对比、V5 P0 深度复盘（[`04-v5-p0-optimization-and-reflections.md`](./docs/benchmark/04-v5-p0-optimization-and-reflections.md)）及双机容量边界报告（Hetzner `CAX 31` 4,000 稳态 / 5,000 峰值，腾讯云 `C6.2XLARGE16` 5,000 稳态）。

## 参与贡献与开源协议

欢迎提交 Issue 与 Pull Request！开发前请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md) 与 [AGENTS.md](./AGENTS.md)。

本项目基于 **[Apache License 2.0](./LICENSE)** 协议开源。
