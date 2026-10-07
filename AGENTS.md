# HyperLLM Agent & Engineering Guidelines

## Repository Layout

- `backend/` — Maven multi-module Java 21 LTS / Spring Boot 3.5.16 workspace (`app.pagefault.hyperllm`)
  - `common/` (`hyperllm-common`): pure primitives (`UuidV7`, `MicroUsd`, `PageQuery`/`PageResponse`, `ErrorCode`/`HyperLlmException`, `AesGcmCipher`, `HmacSha256`, `ApiKeyGenerator`)
  - `domain/` (`hyperllm-domain`): Flyway SQL migrations (`V*.sql`), jOOQ generated sources (`src/main/generated`), immutable `record` entities, repository interfaces, and jOOQ implementations
  - `protocol/` (`hyperllm-protocol`): Canonical IR & OpenAI/Anthropic protocol adapters + `jtokkit` fallback estimation
  - `gateway/` (`hyperllm-gateway`): WebFlux reactive data plane (`:8080`, Caffeine L1 + Redis L2, Debezium CDC invalidation, Lua rate-limit/billing)
  - `console/` (`hyperllm-console`): Spring MVC developer control plane (`:8081`, virtual threads, OpenAPI 3.1)
  - `admin/` (`hyperllm-admin`): Spring MVC operator control plane (`:8082`, virtual threads, TOTP MFA, settlement consumer)
- `frontend/console/` (`:3000`) & `frontend/admin/` (`:3001`) — Next.js 16 + React 19 scaffolds (deferred until backend APIs stabilize; see `docs/TODO.md`)
- `infra/` — Docker Compose profiles (`core`, `pipeline`, `observability`, `tools`), Kafka topic init, Debezium connectors, ClickHouse DDL, Prometheus/Grafana/OTel configs
- `docs/` — Product proposal, phased `TODO.md`, and detailed design specs (`docs/design/*.md`)

## Hard Architectural Invariants

1. **Money & IDs**: Represent currency exclusively as `long` `micro-USD` (`1 USD = 1_000_000 micros`) using ceiling rounding (`MicroUsd`); never use `float`/`double` for billing. Use time-ordered `UuidV7` for persisted primary keys.
2. **Cryptography & Secrets**:
   - Upstream credentials and encrypted debug payloads use `AesGcmCipher` (`[1B key_version][12B IV][ciphertext+tag]`) bound with explicit AAD (`channel_id` or `project_id:request_id`).
   - API keys (`sk-...`) are stored salted via `HmacSha256` with server pepper and `pepper_version`; plaintext keys are returned once at creation.
   - `console` and `admin` must never share JWT signing secrets, issuers, or audiences.
3. **Database & Migration Ownership**:
   - All Flyway scripts live in `backend/domain/src/main/resources/db/migration/`.
   - Only `console` and `admin` run Flyway (`spring.flyway.enabled=true`). `gateway` MUST keep `spring.flyway.enabled=false` and a small Hikari pool (`maximum-pool-size: 8`).
   - Never hand-edit `backend/domain/src/main/generated`. When adding or editing Flyway SQL, regenerate with `-Pjooq`.
4. **Gateway Reactive Hot Path**:
   - `gateway` depends directly on `domain` for cold-cache fallback only, offloaded off Netty EventLoop (`boundedElastic` / virtual threads + SingleFlight). Hot requests must stay non-blocking via L1 + Redis L2.

## Build & Verification Commands

- Pure unit tests (no Docker): `./backend/mvnw -f backend/pom.xml test`
- Full reactor build + Testcontainers integration tests (`@Tag("integration")`): `./backend/mvnw -f backend/pom.xml verify`
- Regenerate jOOQ sources from Flyway migrations (requires Docker): `./backend/mvnw -f backend/pom.xml compile -pl common,domain -Pjooq`
- Validate Compose config: `docker compose --env-file .env.example -f infra/docker-compose.yaml --profile pipeline --profile observability --profile tools config -q`
