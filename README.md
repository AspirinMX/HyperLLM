# HyperLLM

English | [简体中文](./README_zh-CN.md)

**HyperLLM** (`app.pagefault.hyperllm`) is a commercial-grade multi-tenant LLM API gateway and billing platform built with **Java 21 LTS**, **Spring Boot 3.5**, **PostgreSQL 16 (Flyway + jOOQ)**, **Redis 7**, **Apache Kafka (KRaft + Debezium CDC)**, and **ClickHouse 24**.

## Architecture Modules

| Component | Port | Tech Stack & Responsibility |
| :--- | :---: | :--- |
| `backend/common` | — | `micro-USD` math, `UuidV7`, paging, error codes, `AesGcmCipher`, `HmacSha256`, `ApiKeyGenerator` |
| `backend/domain` | — | Flyway `V1`–`V3` schema, partitioned ledger/audit tables, `-Pjooq` generated SQL layer, jOOQ repositories |
| `backend/protocol` | — | Canonical IR, OpenAI/Anthropic protocol adapters, SSE usage extraction, `jtokkit` fallback |
| `backend/gateway` | `8080` | Reactive WebFlux data plane (`Caffeine L1 + Redis L2`), CDC invalidation, priority-weighted routing, Lua quota/billing |
| `backend/console` | `8081` | Developer control plane (MVC + Java 21 virtual threads), tenant/project RBAC, API keys, wallet queries |
| `backend/admin` | `8082` | Operator control plane (MVC + virtual threads), provider/channel CRUD, pricing versions, TOTP MFA, settlement consumer |
| `frontend/console` | `3000` | Next.js 16 + React 19 developer portal scaffold (queued after backend stabilization) |
| `frontend/admin` | `3001` | Next.js 16 + React 19 operator console scaffold (queued after backend stabilization) |

## Quickstart

### 1. Configure Environment

```bash
cp .env.example .env
```

### 2. Start Local Infrastructure

```bash
# Default core: Postgres 16 (logical WAL), Redis 7 (AOF), Kafka 3.9 KRaft, and kafka-init topic setup
docker compose -f infra/docker-compose.yaml up -d

# Optional CDC + OLAP profile (Debezium Connect + ClickHouse 24)
# Note: run console or admin once first so Flyway creates core tables before debezium-init runs
docker compose -f infra/docker-compose.yaml --profile pipeline up -d

# Optional observability (OTel Collector :4317/:4318, Prometheus :9090, Grafana :3100) and Kafka UI (:8088)
docker compose -f infra/docker-compose.yaml --profile observability --profile tools up -d
```

### 3. Build & Test Backend

```bash
# Compile all 7 Maven reactor modules & run unit tests (no Docker required)
./backend/mvnw -f backend/pom.xml test

# Run full build + Testcontainers PostgreSQL 16 integration verification
./backend/mvnw -f backend/pom.xml verify

# Regenerate jOOQ classes after editing Flyway SQL (requires Docker)
./backend/mvnw -f backend/pom.xml compile -pl common,domain -Pjooq
```

## Documentation

- [Project Proposal](./docs/PROPOSAL.md)
- [Phased Implementation TODO](./docs/TODO.md)
- [Database Entity Design](./docs/design/01-database-design.md)
- [Dependencies & Infrastructure Design](./docs/design/02-dependencies-and-infra.md)
- [Backend Contracts Design](./docs/design/03-phase1-backend-contracts.md)

## License

Licensed under the [Apache License 2.0](./LICENSE).
