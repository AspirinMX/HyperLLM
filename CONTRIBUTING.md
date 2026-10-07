# Contributing to HyperLLM

Thank you for contributing to HyperLLM! Before opening a PR, please follow this workflow so builds, schema migrations, and tests stay consistent.

## Prerequisites

- **Java 21 LTS** (tested with JDK 21) and Maven Wrapper (`backend/mvnw` / `backend/mvnw.cmd`)
- **Docker** (required for local infrastructure and `@Tag("integration")` Testcontainers tests)
- **Node.js + pnpm 12+** (when working on `frontend/`)

## Local Setup

1. Copy the root environment template:
   ```bash
   cp .env.example .env
   ```
2. Start local infrastructure as needed:
   ```bash
   # Core services (Postgres 16 logical replication, Redis 7, Kafka KRaft + topic init)
   docker compose -f infra/docker-compose.yaml up -d

   # Optional CDC + OLAP pipeline (Debezium + ClickHouse)
   docker compose -f infra/docker-compose.yaml --profile pipeline up -d

   # Optional monitoring & UI tools
   docker compose -f infra/docker-compose.yaml --profile observability --profile tools up -d
   ```

## Schema Migrations & jOOQ Codegen

All DDL lives in `backend/domain/src/main/resources/db/migration/V*.sql`. Whenever you add or modify a Flyway migration:

1. Regenerate jOOQ metadata against real PostgreSQL 16 via Testcontainers:
   ```bash
   ./backend/mvnw -f backend/pom.xml compile -pl common,domain -Pjooq
   ```
2. Review and commit both the Flyway SQL and updated files under `backend/domain/src/main/generated/`.

## Testing Conventions

- Annotate container-backed tests with `@Tag("integration")` (and name classes `*IT.java` or `*IntegrationTest.java`).
- Run unit tests only (fast, no Docker):
  ```bash
  ./backend/mvnw -f backend/pom.xml test
  ```
- Run the full suite including Testcontainers integration verification:
  ```bash
  ./backend/mvnw -f backend/pom.xml verify
  ```

## Commit Style

Use Conventional Commits (e.g., `feat(gateway): ...`, `fix(domain): ...`, `docs: ...`). Never commit `.env` or local secrets.
