#!/usr/bin/env bash
set -euo pipefail

# Starts hyperllm-gateway pinned to dedicated vCPUs via taskset and explicit ActiveProcessorCount.
# Usage:
#   GATEWAY_CPUS=0-1 GATEWAY_CPU_COUNT=2 GATEWAY_HEAP=3g ./benchmark/scripts/run-gateway.sh
#   GATEWAY_CPUS=0-3 GATEWAY_CPU_COUNT=4 GATEWAY_HEAP=5g ./benchmark/scripts/run-gateway.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

GATEWAY_CPUS="${GATEWAY_CPUS:-0-1}"
GATEWAY_CPU_COUNT="${GATEWAY_CPU_COUNT:-2}"
GATEWAY_HEAP="${GATEWAY_HEAP:-3g}"
GATEWAY_JAR="${GATEWAY_JAR:-$(ls -t "${REPO_ROOT}"/backend/gateway/target/hyperllm-gateway-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)}"

if [[ -z "${GATEWAY_JAR}" || ! -f "${GATEWAY_JAR}" ]]; then
  echo "ERROR: hyperllm-gateway jar not found under backend/gateway/target/. Run Maven package first." >&2
  exit 1
fi

export POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
export POSTGRES_PORT="${POSTGRES_PORT:-5432}"
export POSTGRES_DB="${POSTGRES_DB:-hyperllm}"
export POSTGRES_USER="${POSTGRES_USER:-hyperllm}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-hyperllm_dev_password}"

export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-}"

export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"

export HYPERLLM_AES_MASTER_KEY="${HYPERLLM_AES_MASTER_KEY:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}"
export HYPERLLM_KEY_PEPPER_V1="${HYPERLLM_KEY_PEPPER_V1:-dev-only-hyperllm-key-pepper-v1-change-in-production}"
export HYPERLLM_UPSTREAM_MAX_CONNECTIONS_PER_PROVIDER="${HYPERLLM_UPSTREAM_MAX_CONNECTIONS_PER_PROVIDER:-5000}"
export OTEL_TRACES_SAMPLING_PROBABILITY="${OTEL_TRACES_SAMPLING_PROBABILITY:-0}"
export GATEWAY_MAX_IN_MEMORY_SIZE="${GATEWAY_MAX_IN_MEMORY_SIZE:-16MB}"

ulimit -n 65535 || true

echo "Launching hyperllm-gateway on CPUs=${GATEWAY_CPUS} (ActiveProcessorCount=${GATEWAY_CPU_COUNT}, Heap=${GATEWAY_HEAP})"
echo "JAR: ${GATEWAY_JAR}"

exec taskset -c "${GATEWAY_CPUS}" java \
  "-XX:ActiveProcessorCount=${GATEWAY_CPU_COUNT}" \
  "-Xms${GATEWAY_HEAP}" \
  "-Xmx${GATEWAY_HEAP}" \
  -XX:+UseG1GC \
  -XX:+AlwaysPreTouch \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+DebugNonSafepoints \
  -Dio.netty.leakDetection.level=disabled \
  -jar "${GATEWAY_JAR}"
