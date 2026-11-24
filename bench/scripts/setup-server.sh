#!/usr/bin/env bash
set -euo pipefail

# Sets up the benchmark stack on an ARM64/x86_64 Linux host:
#   1. Applies KVM sysctl tuning
#   2. Builds Go binaries (mock-upstream, loadgen, bench-seed) if Go is available
#   3. Starts lean bench Docker Compose (Postgres, Redis, Kafka; optional BENCH_TMPFS=1)
#   4. Applies Flyway SQL migrations (V1..V9) in order
#   5. Seeds idempotent benchmark tenant, wallet ($100M), API key, 3 channels, 4 virtual models

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BENCH_DIR="${REPO_ROOT}/bench"
BIN_DIR="${BENCH_DIR}/bin"

BENCH_TMPFS="${BENCH_TMPFS:-1}"
BENCH_INFRA_CPUS="${BENCH_INFRA_CPUS:-2-4}"
export BENCH_INFRA_CPUS

HYPERLLM_AES_MASTER_KEY="${HYPERLLM_AES_MASTER_KEY:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}"
HYPERLLM_KEY_PEPPER_V1="${HYPERLLM_KEY_PEPPER_V1:-dev-only-hyperllm-key-pepper-v1-change-in-production}"
UPSTREAM_URL="${UPSTREAM_URL:-http://127.0.0.1:18080}"

echo "==> [1/5] Applying kernel sysctl tuning..."
if [[ "$(id -u)" -eq 0 ]]; then
  bash "${SCRIPT_DIR}/sysctl-bench.sh"
else
  echo "WARN: Not root, skipping sysctl-bench.sh"
fi

echo "==> [2/5] Building Go benchmark binaries..."
mkdir -p "${BIN_DIR}"
if command -v go >/dev/null 2>&1; then
  (
    cd "${BENCH_DIR}"
    CGO_ENABLED=0 go build -o "${BIN_DIR}/mock-upstream" ./cmd/mock-upstream
    CGO_ENABLED=0 go build -o "${BIN_DIR}/loadgen" ./cmd/loadgen
    CGO_ENABLED=0 go build -o "${BIN_DIR}/bench-seed" ./cmd/bench-seed
  )
elif [[ -x "${BIN_DIR}/mock-upstream" && -x "${BIN_DIR}/loadgen" && -x "${BIN_DIR}/bench-seed" ]]; then
  echo "Using prebuilt Go binaries in ${BIN_DIR}"
else
  echo "ERROR: Go toolchain not found and prebuilt binaries missing in ${BIN_DIR}." >&2
  exit 1
fi

echo "==> [3/5] Starting bench Docker Compose (BENCH_TMPFS=${BENCH_TMPFS}, BENCH_INFRA_CPUS=${BENCH_INFRA_CPUS})..."
COMPOSE_ARGS=(-f "${BENCH_DIR}/infra/docker-compose.bench.yml")
if [[ "${BENCH_TMPFS}" == "1" ]]; then
  COMPOSE_ARGS+=(-f "${BENCH_DIR}/infra/docker-compose.bench.tmpfs.yml")
fi
docker compose "${COMPOSE_ARGS[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose "${COMPOSE_ARGS[@]}" up -d

echo "Waiting for Postgres, Redis, and Kafka to become healthy..."
for i in $(seq 1 60); do
  pg_ok=$(docker inspect -f '{{.State.Health.Status}}' hyperllm-bench-postgres 2>/dev/null || echo "starting")
  rd_ok=$(docker inspect -f '{{.State.Health.Status}}' hyperllm-bench-redis 2>/dev/null || echo "starting")
  kf_ok=$(docker inspect -f '{{.State.Health.Status}}' hyperllm-bench-kafka 2>/dev/null || echo "starting")
  if [[ "${pg_ok}" == "healthy" && "${rd_ok}" == "healthy" && "${kf_ok}" == "healthy" ]]; then
    echo "All infra containers healthy."
    break
  fi
  if [[ "${i}" -eq 60 ]]; then
    echo "ERROR: Timed out waiting for infra containers (pg=${pg_ok} redis=${rd_ok} kafka=${kf_ok})." >&2
    exit 1
  fi
  sleep 2
done

# Ensure kafka-init finished cleanly
docker wait hyperllm-bench-kafka-init >/dev/null 2>&1 || true

echo "==> [4/5] Applying SQL migrations in Flyway order..."
MIG_DIR="${REPO_ROOT}/backend/domain/src/main/resources/db/migration"
MIGRATIONS=(
  V1__init_auth_and_tenants.sql
  V2__init_models_channels_pricing.sql
  V3__init_wallets_ledger_and_audit.sql
  V4__init_commercial_billing_outbox.sql
  V4_1__add_wallet_ledger_hold_id.sql
  V4_2__billing_authority_columns.sql
  V4_3__grant_ledger_type.sql
  V5__payment_catalog_and_references.sql
  V6__project_monthly_budgets.sql
  V7__analytics_ingestion_control.sql
  V8__alert_delivery_and_reconciliation.sql
  V9__protocol_wire_capabilities.sql
)

for mig in "${MIGRATIONS[@]}"; do
  echo "  -> ${mig}"
  docker exec -i hyperllm-bench-postgres \
    psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-hyperllm}" -d "${POSTGRES_DB:-hyperllm}" \
    < "${MIG_DIR}/${mig}" >/dev/null
done

echo "==> [5/5] Seeding benchmark tenant, wallet, API key, channels, and models..."
"${BIN_DIR}/bench-seed" \
  -aes-key "${HYPERLLM_AES_MASTER_KEY}" \
  -pepper "${HYPERLLM_KEY_PEPPER_V1}" \
  -upstream-url "${UPSTREAM_URL}" \
  | docker exec -i hyperllm-bench-postgres \
      psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-hyperllm}" -d "${POSTGRES_DB:-hyperllm}" >/dev/null

docker exec hyperllm-bench-redis redis-cli -h 127.0.0.1 FLUSHALL >/dev/null

echo "Benchmark environment ready."
