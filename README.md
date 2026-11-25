<div align="center">

# ⚡ HyperLLM

**An enterprise-level LLM API gateway that supports mainstream API protocols and seamless inter-protocol conversion; highly optimized for high concurrency and high availability.**

<p>
  <a href="./README.md"><strong>English</strong></a> ·
  <a href="./README_zh-CN.md">简体中文</a>
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
  <a href="#quickstart">Quickstart</a> ·
  <a href="#drop-in-sdk-examples">SDK Examples</a> ·
  <a href="#documentation">Documentation</a>
</p>

</div>

---

## Quickstart

### 1. Configure Environment & Start Core Infrastructure

```bash
# Copy local environment template (replace all secrets before production deployment!)
cp .env.example .env

# Start core infrastructure (PostgreSQL 16 logical WAL, Redis 7 AOF, Kafka 3.9 KRaft) + dev tools
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

### 4. Verify

```bash
# Full reactor build + Testcontainers integration tests (PostgreSQL, Redis, Kafka, ClickHouse, WireMock)
./backend/mvnw -f backend/pom.xml verify
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
- **[Benchmark & Load-Testing Suite](./benchmark/README.md)** — how to run reproducible CPU-isolated load tests and capture `async-profiler` flamegraphs, plus the [large-payload zero-copy report](./docs/benchmark/2026-11-25-gateway-arm64-large-payload-optimization.md), [P0/P1 optimization report](./docs/benchmark/2026-11-25-gateway-arm64-p0-p1-optimization.md), and [ARM64 baseline report](./docs/benchmark/2026-11-25-gateway-arm64-baseline.md).

## Contributing & License

Contributions are welcome! Please review [CONTRIBUTING.md](./CONTRIBUTING.md) and [AGENTS.md](./AGENTS.md) before opening a pull request.

Licensed under the **[Apache License 2.0](./LICENSE)**.
