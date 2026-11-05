# End-to-end scripts

Live-stack verification that runs against real infrastructure (PostgreSQL, Redis, Kafka,
WireMock) instead of Testcontainers-in-JVM. Complements the integration tests: those prove
each service in isolation, this proves the wiring between them.

## phase1-smoke.sh

One command that exercises the whole Phase 1 loop:

1. admin login (MFA must be disabled)
2. create channel -> virtual model -> binding -> pricing rule
3. console signup with the debug OTP echo
4. admin wallet topup
5. create an API key on the default project
6. call `POST /v1/chat/completions` through the gateway against the WireMock upstream
7. wait for the asynchronous Kafka settlement to land in `wallet_ledger`
8. print the resulting balances

### Prerequisites

```bash
cp .env.example .env
docker compose -f infra/docker-compose.yaml --profile tools up -d
```

WireMock is part of the `tools` profile and listens on `${WIREMOCK_PORT:-8099}`. Its stub
mappings live in [infra/wiremock/mappings](../infra/wiremock/mappings) and are loaded at
startup, so the script never has to call the WireMock admin API.

Start the three services with these non-production switches:

```bash
# console (:8081) - echo the OTP so the script can register without a mail server
HYPERLLM_OTP_DEBUG_ECHO=true ./backend/mvnw -f backend/pom.xml -pl console spring-boot:run

# admin (:8082) - skip TOTP and seed the first super admin
HYPERLLM_ADMIN_MFA_REQUIRED=false \
HYPERLLM_BOOTSTRAP_ADMIN_EMAIL=ops-root@hyperllm.dev \
HYPERLLM_BOOTSTRAP_ADMIN_PASSWORD='RootAdminPass123!' \
./backend/mvnw -f backend/pom.xml -pl admin spring-boot:run

# gateway (:8080)
./backend/mvnw -f backend/pom.xml -pl gateway spring-boot:run
```

### Run

```bash
bash scripts/e2e/phase1-smoke.sh
```

Exit code 0 and a `SMOKE PASSED` block mean the full loop worked. On failure the script prints
the step name, the HTTP status it got, and the response body.

### Overrides

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `ADMIN` / `CONSOLE` / `GATEWAY` / `WIREMOCK` | `http://localhost:8082` / `:8081` / `:8080` / `:8099` | Service base URLs |
| `ADMIN_LOGIN` / `ADMIN_PASSWORD` | bootstrap credentials | Admin account |
| `TOPUP_MICROS` | `1000000` | Wallet credit in micro-USD |

### Notes

- Every run creates fresh rows (`smoke-<epoch>` model code, new email), so it is safe to repeat
  without resetting the database.
- The script intentionally asserts only that a ledger row appears, not the exact charged amount.
  Exact arithmetic is covered by `GatewayE2EIntegrationTest`; here the goal is proving the
  cross-service wiring.

## phase2-smoke.sh

The commercial loop, on top of the Phase 1 checks:

1. admin login, provider, a failing and a healthy channel, two models (JSON and SSE) with pricing
2. console signup, a project with monthly soft/hard budgets, an API key
3. wallet topup through the outbox, a voucher code issued by admin and redeemed by console
4. a non-stream call that retries the failing channel, and a real SSE call
5. settlement into `wallet_ledger`, the live project budget, the usage report and reconciliation
6. low-balance notification endpoint and rule, then Configuration CDC eviction of the revoked key

### Prerequisites

The pipeline profile is required so ClickHouse and Debezium are up; WireMock must load the
Phase 2 mappings (`ok-`, `fail-` and `sse-` chat completions):

```bash
cp .env.example .env
docker compose -f infra/docker-compose.yaml --profile pipeline --profile tools up -d
```

The three services use the same switches as Phase 1. Admin additionally needs the ClickHouse JDBC
URL (default `jdbc:clickhouse://localhost:8123/hyperllm`), which the `pipeline` profile provides.

### Run

```bash
bash scripts/e2e/phase2-smoke.sh
```

The usage step polls for up to two minutes because it waits for the request event consumer and the
hourly rollup. Set `SKIP_USAGE=1` when the warehouse is not part of the environment under test.

### Overrides

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `ADMIN` / `CONSOLE` / `GATEWAY` / `WIREMOCK` / `CLICKHOUSE` | `:8082` / `:8081` / `:8080` / `:8099` / `:8123` | Service base URLs |
| `ADMIN_LOGIN` / `ADMIN_PASSWORD` | bootstrap credentials | Admin account |
| `TOPUP_MICROS` | `5000000` | Wallet credit in micro-USD |
| `VOUCHER_MICROS` | `1000000` | Voucher value in micro-USD |
| `SKIP_USAGE` | `0` | Skip the ClickHouse-dependent report step |
