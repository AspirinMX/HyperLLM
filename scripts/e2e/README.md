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
