<div align="center">

# ⚡ HyperLLM

**An enterprise-level LLM API gateway that supports mainstream API protocols and seamless inter-protocol conversion; highly optimized for high concurrency and high availability.**

<p>
  <a href="./README.md"><strong>English</strong></a> ·
  <a href="./README_zh-CN.md">简体中文</a>
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
  <a href="#performance-stress-testing">Benchmarks</a> ·
  <a href="#quickstart">Quickstart</a> ·
  <a href="#drop-in-sdk-examples">SDK Examples</a> ·
  <a href="#documentation">Documentation</a>
</p>

</div>

---

## Performance Stress Testing

**When deployed on a single machine with 4 vCPUs and 8 GB of RAM, the gateway can stably maintain 5,000 concurrent SSE connections, while ensuring an output rate of 200 tokens/s and a Time to First Token (TTFT) of 500 ms.**

> Since the gateway does not distinguish between same-protocol and cross-protocol paths—converting all incoming requests into an intermediate representation before forwarding them to the upstream (for security reasons)—there is minimal difference between the performance results for same-protocol and cross-protocol scenarios.

> **Stress Test Scenario:** 100K-token long-context input, 2,000 output tokens, an output rate of 200 tokens/s (5 tokens per frame, 400 frames per stream, lasting approximately 10.5 seconds), a 20-second linear ramp-up, and a 60-second steady-state window. The test was conducted on a single machine with strict CPU core isolation (hard partitioning); all valid test runs achieved `%steal = 0.00` and zero errors. All TTFT values below report strictly the gateway-added overhead **`ΔTTFT = measured TTFT − 500 ms`** (excluding the 500 ms simulated upstream TTFT).

### Tencent Cloud CVM `C6.2XLARGE16` (x86_64)

- **Instance Configuration**: 8 vCPU Intel Xeon Platinum 8372C @ 3.20 GHz (4 physical cores × 2 HT) / 16 GiB RAM (Gateway hard-isolated to 4 vCPU / 2 physical cores; `6 GiB` G1GC heap; meets 4C8G per-instance resource quota; `nettyEpoll=true`)

| Concurrency, Protocol Path & GC | `ΔTTFT` P50 / P99 (Gateway Overhead) | YGC / Total Pause | Full GC | Old Gen Peak | Gateway CPU |
| :--- | --: | --: | --: | --: | --: |
| 3,000 · OpenAI → OpenAI (G1) | +87.6 / +381.4 ms | 8 / 565 ms | **0** | 288 MB | 86.0% |
| 4,000 · OpenAI → OpenAI (G1) | +146.7 / +394.0 ms | 9 / 647 ms | **0** | 234 MB | 87.8% |
| **5,000 · OpenAI → OpenAI (G1)** | **+216.6 / +603.7 ms** | **10 / 833 ms** | **0** | **244 MB** | **89.9%** |

### Hetzner Cost-Optimized `CAX 31` (ARM64)

- **Instance Configuration**: 8 vCPU ARM Neoverse-N1 / 16 GiB RAM (Gateway hard-isolated to 4 vCPU; `5 GiB` heap; meets 4C8G per-instance resource quota; `nettyEpoll=true`)

| Concurrency, Protocol Path & GC | `ΔTTFT` P50 / P99 (Gateway Overhead) | YGC / Total Pause | Full GC | Old Gen Peak | Gateway CPU |
| :--- | --: | --: | --: | --: | --: |
| 2,000 · OpenAI → OpenAI (G1) | +60.2 / +149.8 ms | 6 / 291 ms | **0** | 139 MB | 89.2% |
| 2,000 · Anthropic → OpenAI (G1) | +59.3 / +156.3 ms | 5 / 239 ms | **0** | 137 MB | 89.0% |
| 3,000 · OpenAI → OpenAI (G1) | +176.8 / +378.6 ms | 8 / 508 ms | **0** | 131 MB | 93.6% |
| 3,000 · Anthropic → OpenAI (G1) | +174.6 / +370.0 ms | 8 / 487 ms | **0** | 141 MB | 93.3% |
| **3,500 · OpenAI → OpenAI (G1)** | **+262.5 / +512.8 ms** | **9 / 652 ms** | **0** | **134 MB** | **94.4%** |
| **3,500 · Anthropic → OpenAI (G1)** | **+255.5 / +532.5 ms** | **10 / 659 ms** | **0** | **144 MB** | **94.6%** |

> See the full benchmark suite ([`docs/benchmark/README.md`](./docs/benchmark/README.md)) for the 6-version ARM64 optimization evolution (`V0 → V4`, 43 scenarios), 30 verbatim `async-profiler` HTML flamegraphs (CPU / Alloc / Lock), 7 SVG charts, 3,000-stream live-set audit, and G1GC vs Generational ZGC analysis.

## Quickstart

### 1. Configure Environment & Start Core Infrastructure

```bash
# Copy local environment template (replace all secrets before production deployment!)
cp .env.example .env

# Start core infrastructure
docker compose --env-file .env -f infra/docker-compose.yaml --profile tools up -d
```

### 2. Run Backend Services (Java 21 LTS)

```bash
# Build shared modules & run unit tests (no Docker required)
./backend/mvnw -f backend/pom.xml test

# Start Console (:8081) — runs Flyway V1–V9 migrations automatically
HYPERLLM_OTP_DEBUG_ECHO=true ./backend/mvnw -f backend/pom.xml -pl console spring-boot:run

# After Flyway tables exist, start Debezium CDC + ClickHouse pipeline
docker compose --env-file .env -f infra/docker-compose.yaml --profile pipeline up -d

# Start Admin (:8082) and Gateway (:8080) in separate terminals
HYPERLLM_ADMIN_MFA_REQUIRED=false ./backend/mvnw -f backend/pom.xml -pl admin spring-boot:run
./backend/mvnw -f backend/pom.xml -pl gateway spring-boot:run
```

> **Optional observability stack**: add `--profile observability` to launch OTel Collector (`:4317/:4318`), Prometheus (`:9090`), and Grafana (`:3100`).

### 3. Run Frontend Portals (Next.js 16 + pnpm)

```bash
# Developer Portal & Console -> http://localhost:3000
cd frontend/console && pnpm install && pnpm dev

# Operator Control Center -> http://localhost:3001
cd frontend/admin && pnpm install && pnpm dev
```

## Drop-In SDK Examples

### OpenAI Python SDK

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="sk-your-hyperllm-api-key",
)

stream = client.chat.completions.create(
    model="gpt-6.2-astra",
    messages=[{"role": "user", "content": "Explain how Pre-hold prevents wallet overspending."}],
    stream=True,
)
for chunk in stream:
    if chunk.choices and chunk.choices[0].delta.content:
        print(chunk.choices[0].delta.content, end="", flush=True)
```

### Anthropic Native SDK & Claude Code

```bash
# Point Claude Code or Anthropic SDKs directly at HyperLLM
export ANTHROPIC_BASE_URL="http://localhost:8080"
export ANTHROPIC_API_KEY="sk-your-hyperllm-api-key"

curl http://localhost:8080/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2026-10-21" \
  -H "content-type: application/json" \
  -d '{
    "model": "claude-opus-5-5",
    "max_tokens": 512,
    "messages": [{"role": "user", "content": "Hello from HyperLLM!"}]
  }'
```

## Documentation

- **[Newcomer Takeover & Onboarding Handbook](./docs/onboarding/README.md)** — 8-lesson guide covering local setup, architecture invariants, storage model, gateway lifecycle, Redis authority and Lua scripts, control planes, frontend BFF patterns, and troubleshooting runbooks.
- **[OpenAPI 3.1 Specifications](./docs/openapi/README.md)** — machine-readable contracts for Swagger UI, Postman, and TypeScript SDK generation: [Gateway](./docs/openapi/gateway-api.yaml) · [Console](./docs/openapi/console-api.yaml) · [Admin](./docs/openapi/admin-api.yaml).
- **[Benchmark & Load-Testing Suite](./benchmark/README.md)** — how to run reproducible CPU-isolated load tests and capture `async-profiler` flamegraphs.
- **[Performance Benchmark & Evolution Reports](./docs/benchmark/README.md)** — 6-version ARM64 optimization evolution (`V0 → V4`, 43 scenarios), 30 verbatim `async-profiler` HTML flamegraphs (CPU / Alloc / Lock), 7 SVG charts, live-set audit, G1GC vs Generational ZGC, and dual-host capacity comparison across Hetzner `CAX 31` (ARM64) and Tencent Cloud `C6.2XLARGE16` (x86_64).

## Contributing & License

Contributions are welcome! Please review [CONTRIBUTING.md](./CONTRIBUTING.md) and [AGENTS.md](./AGENTS.md) before opening a pull request.

Licensed under the **[Apache License 2.0](./LICENSE)**.
