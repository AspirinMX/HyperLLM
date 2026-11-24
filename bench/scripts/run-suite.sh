#!/usr/bin/env bash
set -euo pipefail

# Runs the full HyperLLM benchmark matrix (S1..S4) against a running gateway on 127.0.0.1:8080.
# Captures per-core CPU + %steal via mpstat if available and writes JSON summaries to RESULTS_DIR.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BIN_DIR="${REPO_ROOT}/bench/bin"

TARGET="${TARGET:-http://127.0.0.1:8080}"
MOCK_URL="${MOCK_URL:-http://127.0.0.1:18080}"
MOCK_CPUS="${MOCK_CPUS:-5}"
LOADGEN_CPUS="${LOADGEN_CPUS:-6-7}"
API_KEY="${API_KEY:-sk-bench0010000000000000000000000000000000000000001}"
WARMUP="${WARMUP:-5s}"
DURATION="${DURATION:-20s}"
TAG="${TAG:-2c-tmpfs}"
RESULTS_DIR="${RESULTS_DIR:-/tmp/hyperllm-bench-${TAG}-$(date +%Y%m%d-%H%M%S)}"

mkdir -p "${RESULTS_DIR}"
ulimit -n 65535 || true

# Start mock-upstream if not already listening
MOCK_PID=""
cleanup() {
  if [[ -n "${MOCK_PID}" ]]; then
    kill "${MOCK_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if ! curl -sf "${MOCK_URL}/healthz" >/dev/null 2>&1; then
  echo "==> Starting mock-upstream pinned to CPUs=${MOCK_CPUS}..."
  taskset -c "${MOCK_CPUS}" "${BIN_DIR}/mock-upstream" -listen 127.0.0.1:18080 \
    > "${RESULTS_DIR}/mock-upstream.log" 2>&1 &
  MOCK_PID=$!
  for _ in $(seq 1 20); do
    curl -sf "${MOCK_URL}/healthz" >/dev/null 2>&1 && break
    sleep 0.2
  done
fi

if ! curl -sf "${TARGET}/actuator/health" >/dev/null 2>&1; then
  echo "ERROR: hyperllm-gateway is not healthy at ${TARGET}/actuator/health" >&2
  exit 1
fi

run_case() {
  local name="$1"
  shift
  local mp_pid=""
  if command -v mpstat >/dev/null 2>&1; then
    mpstat -P ALL 1 > "${RESULTS_DIR}/${name}.mpstat.txt" 2>&1 &
    mp_pid=$!
  fi
  echo ""
  echo "================================================================================"
  echo "SCENARIO: ${name}"
  echo "================================================================================"
  taskset -c "${LOADGEN_CPUS}" "${BIN_DIR}/loadgen" \
    -target "${TARGET}" \
    -api-key "${API_KEY}" \
    -warmup "${WARMUP}" \
    -duration "${DURATION}" \
    -label "${name}" \
    -json-out "${RESULTS_DIR}/${name}.json" \
    "$@" | tee "${RESULTS_DIR}/${name}.txt"
  if [[ -n "${mp_pid}" ]]; then
    kill "${mp_pid}" 2>/dev/null || true
    wait "${mp_pid}" 2>/dev/null || true
    awk '/^Average:/ {print}' "${RESULTS_DIR}/${name}.mpstat.txt" | head -n 12
  fi
}

config_mock() {
  curl -sf -X POST "${MOCK_URL}/_mock/config?$1" >/dev/null
}

# ---- S1: Non-streaming RPS ceiling (0ms upstream) ----
config_mock "ttft_ms=0&chunks=1&interval_ms=0&reset_stats=1"
run_case "S1_openai_nonstream_c64" -protocol openai-chat -model bench-openai-chat -stream=false -concurrency 64
run_case "S1_openai_nonstream_c128" -protocol openai-chat -model bench-openai-chat -stream=false -concurrency 128

# ---- S2a: Homomorphic SSE streaming (TTFT=100ms, 30 chunks @ 30ms = ~1s stream) ----
config_mock "ttft_ms=100&chunks=30&interval_ms=30&reset_stats=1"
run_case "S2a_openai_sse_c200" -protocol openai-chat -model bench-openai-chat -stream=true -concurrency 200
run_case "S2a_openai_sse_c500" -protocol openai-chat -model bench-openai-chat -stream=true -concurrency 500
run_case "S2a_openai_sse_c1000" -protocol openai-chat -model bench-openai-chat -stream=true -concurrency 1000

# ---- S2b: Cross-protocol SSE streaming (Anthropic inbound -> OpenAI Chat upstream) ----
config_mock "ttft_ms=100&chunks=30&interval_ms=30&reset_stats=1"
run_case "S2b_anthropic_to_openai_sse_c200" -protocol anthropic -model bench-openai-chat -stream=true -concurrency 200
run_case "S2b_anthropic_to_openai_sse_c500" -protocol anthropic -model bench-openai-chat -stream=true -concurrency 500

# ---- S3: Large Coding Agent payload (50KB & 200KB + 16 tools, non-stream 0ms) ----
config_mock "ttft_ms=0&chunks=1&interval_ms=0&reset_stats=1"
run_case "S3_agent_50kb_c32" -protocol openai-chat -model bench-openai-chat -stream=false -payload-kb 50 -tools 16 -concurrency 32
run_case "S3_agent_200kb_c32" -protocol openai-chat -model bench-openai-chat -stream=false -payload-kb 200 -tools 16 -concurrency 32

# ---- S4: Mid-stream client abort & billing cleanup ----
config_mock "ttft_ms=50&chunks=30&interval_ms=30&reset_stats=1"
run_case "S4_abort_after_5_chunks_c100" -protocol openai-chat -model bench-openai-chat -stream=true -abort-after-chunks 5 -concurrency 100

echo ""
echo "==> Capturing final Prometheus snapshot and mock stats..."
curl -sf "${TARGET}/actuator/prometheus" > "${RESULTS_DIR}/gateway-prometheus.txt" || true
curl -sf "${MOCK_URL}/stats" > "${RESULTS_DIR}/mock-stats.json" || true

echo "==> All benchmark artifacts saved to: ${RESULTS_DIR}"
ls -lh "${RESULTS_DIR}"
