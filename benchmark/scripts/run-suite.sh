#!/usr/bin/env bash
set -euo pipefail

# Runs the SSE benchmark matrix against a gateway.
#
# One upstream shape (OpenAI Chat Completions) and three axes:
#   concurrency : 500 / 1000 / 2000 in-flight requests
#   mode        : stream (SSE) / nonstream
#   pair        : same (chat->chat) / cross (anthropic->chat)
#
# Requests are never all started at once: workers are launched evenly over RAMP and each one runs a
# closed loop, so the target concurrency is reached gradually and then held for DURATION (>=60s) while
# every worker is already inside its ~10s stream. The load generator reports the achieved in-flight
# distribution, and a scenario is rejected when concurrency was not maintained.
#
# Long runs are only meaningful when the dependency stack survives them, so the suite also owns
# infrastructure hygiene: it caps Kafka retention, truncates the outbox and flushes Redis between
# scenarios, and fails a scenario when a container restarted or a tmpfs crossed its watermark.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BIN_DIR="${REPO_ROOT}/benchmark/bin"

# --- targets -------------------------------------------------------------------------------------
TARGET="${TARGET:-http://127.0.0.1:8080}"
HEALTH_URL="${HEALTH_URL:-${TARGET}/actuator/health}"
API_KEY="${API_KEY:-sk-bench0010000000000000000000000000000000000000001}"
MODEL="${MODEL:-bench-openai-chat}"
MOCK_URL="${MOCK_URL:-http://127.0.0.1:18080}"
MOCK_CPUS="${MOCK_CPUS:-5-6}"
LOADGEN_CPUS="${LOADGEN_CPUS:-7}"
SUT_CPUS="${SUT_CPUS:-0-3}"

# --- upstream shape (all configurable) ------------------------------------------------------------
TTFT_MS="${TTFT_MS:-500}"
TOKEN_RATE="${TOKEN_RATE:-200}"
TOKENS_PER_FRAME="${TOKENS_PER_FRAME:-5}"
OUTPUT_TOKENS="${OUTPUT_TOKENS:-2000}"
INPUT_TOKENS="${INPUT_TOKENS:-100000}"
PAYLOAD_TOKENS="${PAYLOAD_TOKENS:-${INPUT_TOKENS}}"
MAX_TOKENS="${MAX_TOKENS:-${OUTPUT_TOKENS}}"

# --- matrix ---------------------------------------------------------------------------------------
CONCURRENCIES="${CONCURRENCIES:-500 1000 2000}"
MODES="${MODES:-stream nonstream}"
PAIRS="${PAIRS:-same cross}"
RAMP="${RAMP:-20s}"
DURATION="${DURATION:-60s}"

# --- validation thresholds ------------------------------------------------------------------------
STEAL_MAX="${STEAL_MAX:-0.5}"          # %steal above this invalidates the scenario
MAINTAINED_MIN="${MAINTAINED_MIN:-95}" # % of samples that must hold the requested concurrency
TMPFS_MAX="${TMPFS_MAX:-70}"           # usage % of a dependency tmpfs above this aborts the suite

# --- infrastructure hygiene -----------------------------------------------------------------------
RESET_INFRA="${RESET_INFRA:-1}"        # truncate outbox + flush redis between scenarios
KAFKA_RETENTION_MS="${KAFKA_RETENTION_MS:-900000}"    # 15 min, bounded by design
KAFKA_RETENTION_BYTES="${KAFKA_RETENTION_BYTES:-268435456}" # 256 MiB per topic

# --- profiling ------------------------------------------------------------------------------------
PROFILE="${PROFILE:-0}"
PROFILE_CONCURRENCY="${PROFILE_CONCURRENCY:-1000}"

TAG="${TAG:-run}"
RESULTS_DIR="${RESULTS_DIR:-${REPO_ROOT}/benchmark/results/${TAG}-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "${RESULTS_DIR}/profiles"
ulimit -n 65535 || true

PG=hyperllm-bench-postgres
RD=hyperllm-bench-redis
KF=hyperllm-bench-kafka
PG_USER="${POSTGRES_USER:-hyperllm}"
PG_DB="${POSTGRES_DB:-hyperllm}"

start_mock() {
  if curl -sf "${MOCK_URL}/healthz" >/dev/null 2>&1; then
    return
  fi
  echo "==> starting mock-upstream on CPUs=${MOCK_CPUS}"
  taskset -c "${MOCK_CPUS}" "${BIN_DIR}/mock-upstream" \
    -listen 127.0.0.1:18080 -ttft-ms "${TTFT_MS}" -token-rate "${TOKEN_RATE}" \
    -tokens-per-frame "${TOKENS_PER_FRAME}" -output-tokens "${OUTPUT_TOKENS}" \
    -input-tokens "${INPUT_TOKENS}" -model "${MODEL}" \
    > "${RESULTS_DIR}/mock-upstream.log" 2>&1 &
  for _ in $(seq 1 50); do
    curl -sf "${MOCK_URL}/healthz" >/dev/null 2>&1 && return
    sleep 0.2
  done
  echo "ERROR: mock-upstream did not become healthy" >&2
  exit 1
}

configure_mock() {
  curl -sf -X POST "${MOCK_URL}/_mock/config?ttft_ms=${TTFT_MS}&token_rate=${TOKEN_RATE}" \
    "&tokens_per_frame=${TOKENS_PER_FRAME}&output_tokens=${OUTPUT_TOKENS}&input_tokens=${INPUT_TOKENS}&model=${MODEL}&reset_stats=1" >/dev/null
}

# --- infrastructure guards ------------------------------------------------------------------------

# Retention is the only bound on Kafka growth: without it the 1 GiB tmpfs fills up mid-suite.
cap_kafka_retention() {
  for topic in cdc.billing llm.request.events llm.billing.settlement; do
    docker exec "${KF}" /opt/kafka/bin/kafka-configs.sh --bootstrap-server 127.0.0.1:9092 \
      --alter --entity-type topics --entity-name "${topic}" \
      --add-config "retention.ms=${KAFKA_RETENTION_MS},retention.bytes=${KAFKA_RETENTION_BYTES}" \
      >/dev/null 2>&1 || echo "WARN: could not cap retention on ${topic}"
  done
}

reset_infra() {
  [[ "${RESET_INFRA}" == "1" ]] || return 0
  # One scenario can create one outbox row per request; on a 1.5 GiB tmpfs Postgres that grows until
  # the container restarts, so the billing table is emptied while no load is running.
  docker exec "${PG}" psql -U "${PG_USER}" -d "${PG_DB}" -q -c "TRUNCATE outbox_events" >/dev/null 2>&1 \
    || echo "WARN: could not truncate outbox_events"
  # Redis holds the entitlement mirror, hold records and the unsettled set. Flushing it between
  # scenarios both bounds memory and keeps the mirror rebuild from rejecting requests with 503.
  docker exec "${RD}" redis-cli -n 0 flushdb >/dev/null 2>&1 || echo "WARN: could not flush redis db0"
}

container_restarts() {
  docker inspect -f '{{.RestartCount}}' "$1" 2>/dev/null || echo "?"
}

container_health() {
  docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || echo "unknown"
}

# Storage mode is part of the result: tmpfs + fsync=off measures the gateway ceiling, while a
# disk-backed stack also measures durability. Both are recorded so runs are never mixed up.
detect_storage() {
  local pg_type kf_type commit
  pg_type="$(docker inspect -f '{{if .HostConfig.Tmpfs}}tmpfs{{else if .Mounts}}volume{{else}}overlay{{end}}' "${PG}" 2>/dev/null || echo unknown)"
  kf_type="$(docker inspect -f '{{if .HostConfig.Tmpfs}}tmpfs{{else if .Mounts}}volume{{else}}overlay{{end}}' "${KF}" 2>/dev/null || echo unknown)"
  commit="$(docker exec "${PG}" psql -U "${PG_USER}" -d "${PG_DB}" -t -A -c "show synchronous_commit" 2>/dev/null || echo unknown)"
  echo "postgres=${pg_type} kafka=${kf_type} synchronous_commit=${commit}"
}

tmpfs_pct() { # $1 container, $2 path
  docker exec "$1" df -P "$2" 2>/dev/null | awk 'NR==2 { gsub("%", "", $5); print $5 }'
}

guard_preflight() {
  local fail=0
  for c in "${PG}" "${RD}" "${KF}"; do
    local health restarts
    health="$(container_health "${c}")"
    restarts="$(container_restarts "${c}")"
    echo "${restarts}" > "${RESULTS_DIR}/.baseline-${c##*-}"
    if [[ "${health}" != "healthy" ]]; then
      echo "!! ${c} health=${health}" >&2
      fail=1
    fi
  done
  [[ "${fail}" == "0" ]] || { echo "ERROR: dependency stack is not healthy" >&2; exit 1; }
}

# A restarted dependency silently changes the comparison, and a full tmpfs turns into a container
# restart a few seconds later. Both abort the suite instead of producing plausible-looking numbers.
guard_check() {
  local fail=0 msg=""
  for c in "${PG}" "${RD}" "${KF}"; do
    local health restarts baseline
    health="$(container_health "${c}")"
    restarts="$(container_restarts "${c}")"
    baseline="$(cat "${RESULTS_DIR}/.baseline-${c##*-}" 2>/dev/null || echo 0)"
    if [[ "${health}" != "healthy" ]]; then msg+=" ${c}:health=${health}"; fail=1; fi
    if [[ "${restarts}" != "${baseline}" ]]; then msg+=" ${c}:restarts ${baseline}->${restarts}"; fail=1; fi
  done
  local pg_pct kf_pct
  pg_pct="$(tmpfs_pct "${PG}" /var/lib/postgresql/data)"
  kf_pct="$(tmpfs_pct "${KF}" /var/lib/kafka/data)"
  if [[ -n "${pg_pct}" ]] && (( pg_pct > TMPFS_MAX )); then msg+=" postgres_tmpfs=${pg_pct}%"; fail=1; fi
  if [[ -n "${kf_pct}" ]] && (( kf_pct > TMPFS_MAX )); then msg+=" kafka_tmpfs=${kf_pct}%"; fail=1; fi
  [[ -n "${msg}" ]] && echo "!! infrastructure guard:${msg}"
  GUARD_MSG="${msg//,/ }"
  GUARD_OK="${fail}"
  return 0
}

# --- scenario ------------------------------------------------------------------------------------

steal_and_cpu() {
  awk '
    # mpstat -P ALL rows: $1=time $2=AM/PM $3=cpu $4=%usr ... $10=%steal ... $13=%idle
    $1 ~ /^[0-9][0-9]:[0-9][0-9]:[0-9][0-9]$/ && NF >= 13 {
      if ($10 > max_steal) { max_steal = $10 }
      if ($3 ~ /^[0-3]$/) { sut += 100 - $13; sut_n++ }
      if ($3 == "4") { infra += 100 - $13; infra_n++ }
      if ($3 == "5" || $3 == "6") { mock += 100 - $13; mock_n++ }
      if ($3 == "7") { lg += 100 - $13; lg_n++ }
      n++
    }
    END {
      if (n == 0) { print "max_steal=nan sut_busy=nan infra=nan mock=nan loadgen=nan"; exit }
      printf "max_steal=%.2f sut_busy=%.1f infra=%.1f mock=%.1f loadgen=%.1f", max_steal,
        (sut_n ? sut / sut_n : 0), (infra_n ? infra / infra_n : 0),
        (mock_n ? mock / mock_n : 0), (lg_n ? lg / lg_n : 0)
    }' "$1"
}

run_case() {
  local key="$1" protocol="$2" stream="$3" concurrency="$4"
  local label="${key}_c${concurrency}"
  local text="${RESULTS_DIR}/${label}.txt"
  local json="${RESULTS_DIR}/${label}.json"
  local mpstat="${RESULTS_DIR}/${label}.mpstat.txt"

  reset_infra
  configure_mock
  curl -sf "${MOCK_URL}/stats" > "${RESULTS_DIR}/${label}.mock-before.json" || true

  mpstat -P ALL 1 > "${mpstat}" 2>&1 &
  local mp_pid=$!

  echo ""
  echo "=============================================================================="
  echo "SCENARIO ${label}  protocol=${protocol} stream=${stream} concurrency=${concurrency}"
  echo "=============================================================================="

  taskset -c "${LOADGEN_CPUS}" "${BIN_DIR}/loadgen" \
    -target "${TARGET}" -api-key "${API_KEY}" -protocol "${protocol}" -model "${MODEL}" \
    -stream="${stream}" -payload-tokens "${PAYLOAD_TOKENS}" -max-tokens "${MAX_TOKENS}" \
    -tokens-per-frame "${TOKENS_PER_FRAME}" \
    -concurrency "${concurrency}" -ramp "${RAMP}" -duration "${DURATION}" \
    -label "${label}" -json-out "${json}" | tee "${text}"

  kill "${mp_pid}" 2>/dev/null || true
  wait "${mp_pid}" 2>/dev/null || true
  curl -sf "${MOCK_URL}/stats" > "${RESULTS_DIR}/${label}.mock-after.json" || true

  local line max_steal sut_busy infra_busy mock_busy lg_busy maintained valid
  line="$(steal_and_cpu "${mpstat}")"
  max_steal="$(sed -n 's/.*max_steal=\([0-9.]*\).*/\1/p' <<< "${line}")"
  sut_busy="$(sed -n 's/.*sut_busy=\([0-9.]*\).*/\1/p' <<< "${line}")"
  infra_busy="$(sed -n 's/.*infra=\([0-9.]*\).*/\1/p' <<< "${line}")"
  mock_busy="$(sed -n 's/.*mock=\([0-9.]*\).*/\1/p' <<< "${line}")"
  lg_busy="$(sed -n 's/.*loadgen=\([0-9.]*\).*/\1/p' <<< "${line}")"
  maintained="$(jq -r '.inflight.maintained_pct // 0' "${json}" 2>/dev/null || echo 0)"

  GUARD_OK=0; GUARD_MSG=""
  guard_check

  valid="yes"
  if awk -v v="${max_steal:-0}" -v l="${STEAL_MAX}" 'BEGIN { exit !(v + 0 > l + 0) }'; then
    valid="NO"; echo "!! %steal=${max_steal} > ${STEAL_MAX}: host descheduled the guest"
  fi
  if awk -v v="${maintained:-0}" -v l="${MAINTAINED_MIN}" 'BEGIN { exit !(v + 0 < l) }'; then
    valid="NO"; echo "!! in-flight maintained only ${maintained}% (<${MAINTAINED_MIN}%)"
  fi
  if [[ "${GUARD_OK}" != "0" ]]; then
    valid="NO"; echo "!! dependency guard tripped:${GUARD_MSG}"
  fi

  local pg_pct kf_pct
  pg_pct="$(tmpfs_pct "${PG}" /var/lib/postgresql/data)"
  kf_pct="$(tmpfs_pct "${KF}" /var/lib/kafka/data)"
  echo "   ${line} maintained=${maintained}% pg_tmpfs=${pg_pct:-?}% kafka_tmpfs=${kf_pct:-?}% valid=${valid}"

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "${label}" "${protocol}" "${stream}" "${concurrency}" "${max_steal}" "${sut_busy}" \
    "${infra_busy}" "${mock_busy}" "${lg_busy}" "${maintained}" "${pg_pct:-nan}" "${kf_pct:-nan}" \
    "${valid}" "${GUARD_MSG// /}" >> "${RESULTS_DIR}/summary.csv"

  if [[ "${valid}" != "yes" && "${ABORT_ON_INVALID:-0}" == "1" ]]; then
    echo "ERROR: aborting suite on invalid scenario ${label}" >&2
    exit 1
  fi
}

profile_case() {
  local label="profile_cross_stream_c${PROFILE_CONCURRENCY}"
  configure_mock
  echo "==> profiling ${label} (ctimer / alloc / lock)"
  taskset -c "${LOADGEN_CPUS}" "${BIN_DIR}/loadgen" \
    -target "${TARGET}" -api-key "${API_KEY}" -protocol anthropic -model "${MODEL}" \
    -stream=true -payload-tokens "${PAYLOAD_TOKENS}" -max-tokens "${MAX_TOKENS}" \
    -tokens-per-frame "${TOKENS_PER_FRAME}" \
    -concurrency "${PROFILE_CONCURRENCY}" -ramp "${RAMP}" -duration "${DURATION}" \
    -label "${label}" -json-out "${RESULTS_DIR}/${label}.json" > "${RESULTS_DIR}/${label}.txt" 2>&1 &
  local lg_pid=$!
  sleep 5
  bash "${SCRIPT_DIR}/profile.sh" all 15 "${RESULTS_DIR}/profiles" || true
  wait "${lg_pid}" || true
}

if ! curl -sf "${HEALTH_URL}" >/dev/null 2>&1; then
  echo "ERROR: target is not healthy at ${HEALTH_URL}" >&2
  exit 1
fi

start_mock
guard_preflight
cap_kafka_retention
reset_infra

printf 'label,protocol,stream,concurrency,max_steal_pct,sut_busy_pct,infra_busy_pct,mock_busy_pct,loadgen_busy_pct,inflight_maintained_pct,pg_tmpfs_pct,kafka_tmpfs_pct,valid,guard\n' > "${RESULTS_DIR}/summary.csv"
{
  echo "host=$(uname -n) target=${TARGET}"
  echo "upstream: ttft=${TTFT_MS}ms rate=${TOKEN_RATE}tok/s frame=${TOKENS_PER_FRAME}tok out=${OUTPUT_TOKENS} in=${INPUT_TOKENS}"
  echo "load: ramp=${RAMP} duration=${DURATION} payload_tokens=${PAYLOAD_TOKENS}"
  echo "guards: steal_max=${STEAL_MAX}% maintained_min=${MAINTAINED_MIN}% tmpfs_max=${TMPFS_MAX}% reset_infra=${RESET_INFRA}"
  echo "kafka: retention.ms=${KAFKA_RETENTION_MS} retention.bytes=${KAFKA_RETENTION_BYTES}"
  echo "storage: $(detect_storage)"
} | tee "${RESULTS_DIR}/run.info"

for pair in ${PAIRS}; do
  case "${pair}" in
    same)  protocol="chat" ;;
    cross) protocol="anthropic" ;;
    *) echo "unknown pair ${pair}"; exit 1 ;;
  esac
  for mode in ${MODES}; do
    case "${mode}" in
      stream)    stream=true ;;
      nonstream) stream=false ;;
      *) echo "unknown mode ${mode}"; exit 1 ;;
    esac
    for concurrency in ${CONCURRENCIES}; do
      run_case "${pair}_${mode}" "${protocol}" "${stream}" "${concurrency}"
    done
  done
done

if [[ "${PROFILE}" == "1" ]]; then
  profile_case
fi

echo ""
echo "==> summary (${RESULTS_DIR}/summary.csv)"
cat "${RESULTS_DIR}/summary.csv"
echo ""
echo "==> artifacts: ${RESULTS_DIR}"
