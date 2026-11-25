#!/usr/bin/env bash
set -euo pipefail

# SSE benchmark suite for the HyperLLM gateway.
#
# Axes: concurrency (500/1000/2000) x mode (stream/nonstream) x pair (chat->chat, anthropic->chat).
# One upstream shape (OpenAI Chat Completions) described by five configurable numbers.
#
# Everything that can explain a number is recorded next to it: per-CPU %steal and busy time for the
# gateway / infra / mock / loadgen cores, gateway JVM heap and GC (jstat), direct buffers and threads
# (actuator), dependency tmpfs usage and restart counts, in-flight concurrency distribution, upstream
# shape, JVM flags, commit and storage mode. A scenario is marked invalid when the host descheduled
# the guest, the concurrency was not held, or a dependency restarted or filled its tmpfs.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BIN_DIR="${REPO_ROOT}/benchmark/bin"

# --- targets -------------------------------------------------------------------------------------
TARGET="${TARGET:-http://127.0.0.1:8080}"
HEALTH_URL="${HEALTH_URL:-${TARGET}/actuator/health}"
PROM_URL="${PROM_URL:-${TARGET}/actuator/prometheus}"
API_KEY="${API_KEY:-sk-bench0010000000000000000000000000000000000000001}"
MODEL="${MODEL:-bench-openai-chat}"
MOCK_URL="${MOCK_URL:-http://127.0.0.1:18080}"
MOCK_CPUS="${MOCK_CPUS:-5-6}"
LOADGEN_CPUS="${LOADGEN_CPUS:-7}"
SUT_CPUS="${SUT_CPUS:-0-3}"

# --- upstream shape -------------------------------------------------------------------------------
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
STEAL_MAX="${STEAL_MAX:-0.5}"
MAINTAINED_MIN="${MAINTAINED_MIN:-95}"
TMPFS_MAX="${TMPFS_MAX:-70}"
ABORT_ON_INVALID="${ABORT_ON_INVALID:-1}"

# --- infrastructure hygiene -----------------------------------------------------------------------
RESET_INFRA="${RESET_INFRA:-1}"
KAFKA_RETENTION_MS="${KAFKA_RETENTION_MS:-900000}"
KAFKA_RETENTION_BYTES="${KAFKA_RETENTION_BYTES:-268435456}"

# --- sampling -------------------------------------------------------------------------------------
JVM_SAMPLE_SEC="${JVM_SAMPLE_SEC:-2}"

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

# --------------------------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------------------------

start_mock() {
  if curl -sf "${MOCK_URL}/healthz" >/dev/null 2>&1; then return; fi
  taskset -c "${MOCK_CPUS}" "${BIN_DIR}/mock-upstream" \
    -listen 127.0.0.1:18080 -ttft-ms "${TTFT_MS}" -token-rate "${TOKEN_RATE}" \
    -tokens-per-frame "${TOKENS_PER_FRAME}" -output-tokens "${OUTPUT_TOKENS}" \
    -input-tokens "${INPUT_TOKENS}" -model "${MODEL}" \
    > "${RESULTS_DIR}/mock-upstream.log" 2>&1 &
  for _ in $(seq 1 50); do
    curl -sf "${MOCK_URL}/healthz" >/dev/null 2>&1 && return
    sleep 0.2
  done
  echo "ERROR: mock-upstream did not become healthy" >&2; exit 1
}

configure_mock() {
  local url
  url="${MOCK_URL}/_mock/config?ttft_ms=${TTFT_MS}&token_rate=${TOKEN_RATE}&tokens_per_frame=${TOKENS_PER_FRAME}"
  url+="&output_tokens=${OUTPUT_TOKENS}&input_tokens=${INPUT_TOKENS}&model=${MODEL}&reset_stats=1"
  curl -sf -X POST "${url}" >/dev/null
}

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
  docker exec "${PG}" psql -U "${PG_USER}" -d "${PG_DB}" -q -c "TRUNCATE outbox_events" >/dev/null 2>&1 \
    || echo "WARN: could not truncate outbox_events"
  docker exec "${RD}" redis-cli -n 0 flushdb >/dev/null 2>&1 || echo "WARN: could not flush redis db0"
}

container_restarts() { docker inspect -f '{{.RestartCount}}' "$1" 2>/dev/null || echo "?"; }
container_health()   { docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || echo "unknown"; }
tmpfs_pct() { docker exec "$1" df -P "$2" 2>/dev/null | awk 'NR==2 { gsub("%", "", $5); print $5 }'; }

detect_storage() {
  local pg_type kf_type commit
  pg_type="$(docker inspect -f '{{if .HostConfig.Tmpfs}}tmpfs{{else if .Mounts}}volume{{else}}overlay{{end}}' "${PG}" 2>/dev/null || echo unknown)"
  kf_type="$(docker inspect -f '{{if .HostConfig.Tmpfs}}tmpfs{{else if .Mounts}}volume{{else}}overlay{{end}}' "${KF}" 2>/dev/null || echo unknown)"
  commit="$(docker exec "${PG}" psql -U "${PG_USER}" -d "${PG_DB}" -t -A -c "show synchronous_commit" 2>/dev/null || echo unknown)"
  echo "postgres=${pg_type} kafka=${kf_type} synchronous_commit=${commit}"
}

gateway_pid() { jps -l 2>/dev/null | awk '/hyperllm-gateway/{print $1; exit}'; }

guard_preflight() {
  local fail=0
  for c in "${PG}" "${RD}" "${KF}"; do
    local health restarts
    health="$(container_health "${c}")"
    restarts="$(container_restarts "${c}")"
    echo "${restarts}" > "${RESULTS_DIR}/.baseline-${c##*-}"
    if [[ "${health}" != "healthy" ]]; then echo "!! ${c} health=${health}" >&2; fail=1; fi
  done
  [[ "${fail}" == "0" ]] || { echo "ERROR: dependency stack is not healthy" >&2; exit 1; }
}

guard_check() {
  local fail=0 msg=""
  for c in "${PG}" "${RD}" "${KF}"; do
    local health restarts baseline
    health="$(container_health "${c}")"
    restarts="$(container_restarts "${c}")"
    baseline="$(cat "${RESULTS_DIR}/.baseline-${c##*-}" 2>/dev/null || echo 0)"
    if [[ "${health}" != "healthy" ]]; then msg+=" ${c}:health=${health}"; fail=1; fi
    if [[ "${restarts}" != "${baseline}" ]]; then msg+=" ${c}:restarts=${restarts}"; fail=1; fi
  done
  local pg_pct kf_pct
  pg_pct="$(tmpfs_pct "${PG}" /var/lib/postgresql/data)"
  kf_pct="$(tmpfs_pct "${KF}" /var/lib/kafka/data)"
  if [[ -n "${pg_pct}" ]] && (( pg_pct > TMPFS_MAX )); then msg+=" pg_tmpfs=${pg_pct}%"; fail=1; fi
  if [[ -n "${kf_pct}" ]] && (( kf_pct > TMPFS_MAX )); then msg+=" kafka_tmpfs=${kf_pct}%"; fail=1; fi
  [[ -n "${msg}" ]] && echo "!! infrastructure guard:${msg}"
  GUARD_OK="${fail}"; GUARD_MSG="${msg// /}"
}

# jstat + actuator sampling: heap, GC and direct buffers move during a run and are only visible if
# sampled while it runs.
start_jvm_sampler() {
  local pid out_js out_prom samples
  pid="$(gateway_pid)"
  out_js="$1.jstat.txt"; out_prom="$1.prom.txt"
  if [[ -z "${pid}" ]]; then echo "WARN: gateway pid not found, JVM metrics disabled"; return 0; fi
  samples=$(( (${RAMP_SECS:-20} + ${DURATION_SECS:-60} + 30) / JVM_SAMPLE_SEC + 2 ))
  (
    for _ in $(seq 1 "${samples}"); do
      echo "$(date +%s) $(jstat -gc "${pid}" 2>/dev/null | tail -1)" >> "${out_js}"
      curl -sf "${PROM_URL}" 2>/dev/null \
        | grep -E '^(jvm_buffer_memory_used_bytes\{id="direct"\}|jvm_threads_live_threads|jvm_memory_used_bytes\{area="heap"|process_cpu_time_ns_total|gateway_outbox_queue_depth)' \
        >> "${out_prom}" 2>/dev/null || true
      sleep "${JVM_SAMPLE_SEC}"
    done
  ) &
  JVM_SAMPLER_PID=$!
}

stop_jvm_sampler() {
  [[ -n "${JVM_SAMPLER_PID:-}" ]] && kill "${JVM_SAMPLER_PID}" 2>/dev/null || true
  wait "${JVM_SAMPLER_PID}" 2>/dev/null || true
  JVM_SAMPLER_PID=""
}

# Reduces the samples into the numbers a performance review needs: peak heap and old gen, GC counts
# and pause time over the window, peak direct buffers and threads, GC-free allocation volume.
jvm_summary() {
  local prefix="$1"
  python3 - "$prefix" <<'PY'
import sys
prefix = sys.argv[1]

def jstat_rows(path):
    rows = []
    try:
        for line in open(path):
            f = line.split()
            if len(f) < 19:
                continue
            ts = int(f[0])
            v = [float(x) for x in f[1:19]]
            rows.append((ts, v))
    except FileNotFoundError:
        pass
    return rows

rows = jstat_rows(prefix + '.jstat.txt')
heap_max = old_max = 0.0
ygc = ygct = fgc = fgct = 0.0
alloc_span = 0.0
if rows:
    first, last = rows[0][1], rows[-1][1]
    ygc, ygct = last[12] - first[12], last[13] - first[13]
    fgc, fgct = last[14] - first[14], last[15] - first[15]
    for _, v in rows:
        s0u, s1u, eu, ou = v[2], v[3], v[5], v[7]
        heap = (s0u + s1u + eu + ou) / 1024.0
        heap_max = max(heap_max, heap)
        old_max = max(old_max, ou / 1024.0)

direct_max = threads_max = outbox_max = 0.0
cpu_delta = 0.0
cpu_samples = []
try:
    for line in open(prefix + '.prom.txt'):
        line = line.strip()
        if line.startswith('jvm_buffer_memory_used_bytes{id="direct"}'):
            direct_max = max(direct_max, float(line.rsplit(' ', 1)[1]))
        elif line.startswith('jvm_threads_live_threads'):
            threads_max = max(threads_max, float(line.rsplit(' ', 1)[1]))
        elif line.startswith('gateway_outbox_queue_depth'):
            outbox_max = max(outbox_max, float(line.rsplit(' ', 1)[1]))
        elif line.startswith('process_cpu_time_ns_total'):
            cpu_samples.append(float(line.rsplit(' ', 1)[1]))
except FileNotFoundError:
    pass
if len(cpu_samples) >= 2:
    cpu_delta = (cpu_samples[-1] - cpu_samples[0]) / 1e9

print(f"{heap_max:.0f} {old_max:.0f} {int(ygc)} {ygct*1000:.0f} {int(fgc)} {fgct*1000:.0f} "
      f"{direct_max/1048576:.1f} {int(threads_max)} {int(outbox_max)} {cpu_delta:.1f}")
PY
}

steal_and_cpu() {
  awk '
    $1 ~ /^[0-9][0-9]:[0-9][0-9]:[0-9][0-9]$/ && NF >= 13 && $3 ~ /^(all|[0-9]+)$/ {
      if ($10 > max_steal) { max_steal = $10; if ($3 != "all") { worst_cpu = $3; worst_ts = $1 } }
      if ($3 ~ /^[0-3]$/) { sut += 100 - $13; sut_n++ }
      if ($3 == "4") { infra += 100 - $13; infra_n++ }
      if ($3 == "5" || $3 == "6") { mock += 100 - $13; mock_n++ }
      if ($3 == "7") { lg += 100 - $13; lg_n++ }
      n++
    }
    END {
      if (n == 0) { print "max_steal=nan sut_busy=nan infra=nan mock=nan loadgen=nan"; exit }
      printf "max_steal=%.2f sut_busy=%.1f infra=%.1f mock=%.1f loadgen=%.1f worst_cpu=%s", max_steal,
        (sut_n ? sut / sut_n : 0), (infra_n ? infra / infra_n : 0),
        (mock_n ? mock / mock_n : 0), (lg_n ? lg / lg_n : 0), (worst_cpu ? worst_cpu : "-")
    }' "$1"
}

# --------------------------------------------------------------------------------------------------
# scenario
# --------------------------------------------------------------------------------------------------

run_case() {
  local key="$1" protocol="$2" stream="$3" concurrency="$4"
  local label="${key}_c${concurrency}"
  local text="${RESULTS_DIR}/${label}.txt" json="${RESULTS_DIR}/${label}.json"
  local mpstat="${RESULTS_DIR}/${label}.mpstat.txt"

  reset_infra
  configure_mock
  curl -sf "${MOCK_URL}/stats" > "${RESULTS_DIR}/${label}.mock-before.json" || true

  mpstat -P ALL 1 > "${mpstat}" 2>&1 &
  local mp_pid=$!
  start_jvm_sampler "${RESULTS_DIR}/${label}"

  echo ""
  echo "=============================================================================="
  echo "SCENARIO ${label}  protocol=${protocol} stream=${stream} concurrency=${concurrency}"
  echo "=============================================================================="

  ${LOADGEN_CPUS:+taskset -c "${LOADGEN_CPUS}"} "${BIN_DIR}/loadgen" \
    -target "${TARGET}" -api-key "${API_KEY}" -protocol "${protocol}" -model "${MODEL}" \
    -stream="${stream}" -payload-tokens "${PAYLOAD_TOKENS}" -max-tokens "${MAX_TOKENS}" \
    -tokens-per-frame "${TOKENS_PER_FRAME}" \
    -concurrency "${concurrency}" -ramp "${RAMP}" -duration "${DURATION}" \
    -label "${label}" -json-out "${json}" | tee "${text}"

  stop_jvm_sampler
  kill "${mp_pid}" 2>/dev/null || true
  wait "${mp_pid}" 2>/dev/null || true
  curl -sf "${MOCK_URL}/stats" > "${RESULTS_DIR}/${label}.mock-after.json" || true

  local line max_steal sut_busy infra_busy mock_busy lg_busy worst_cpu maintained
  line="$(steal_and_cpu "${mpstat}")"
  max_steal="$(sed -n 's/.*max_steal=\([0-9.]*\).*/\1/p' <<< "${line}")"
  sut_busy="$(sed -n 's/.*sut_busy=\([0-9.]*\).*/\1/p' <<< "${line}")"
  infra_busy="$(sed -n 's/.*infra=\([0-9.]*\).*/\1/p' <<< "${line}")"
  mock_busy="$(sed -n 's/.*mock=\([0-9.]*\).*/\1/p' <<< "${line}")"
  lg_busy="$(sed -n 's/.*loadgen=\([0-9.]*\).*/\1/p' <<< "${line}")"
  worst_cpu="$(sed -n 's/.*worst_cpu=\([^ ]*\).*/\1/p' <<< "${line}")"
  maintained="$(jq -r '.inflight.maintained_pct // 0' "${json}" 2>/dev/null || echo 0)"

  local jvm heap_max old_max ygc ygct fgc fgct direct_max threads_max outbox_max jvm_cpu
  jvm="$(jvm_summary "${RESULTS_DIR}/${label}")"
  read -r heap_max old_max ygc ygct fgc fgct direct_max threads_max outbox_max jvm_cpu <<< "${jvm}"

  GUARD_OK=0; GUARD_MSG=""
  guard_check

  valid="yes"
  if awk -v v="${max_steal:-0}" -v l="${STEAL_MAX}" 'BEGIN { exit !(v + 0 > l + 0) }'; then
    valid="NO"; echo "!! %steal=${max_steal} on CPU ${worst_cpu} > ${STEAL_MAX}: guest was descheduled"
  fi
  if awk -v v="${maintained:-0}" -v l="${MAINTAINED_MIN}" 'BEGIN { exit !(v + 0 < l) }'; then
    valid="NO"; echo "!! in-flight maintained only ${maintained}% (<${MAINTAINED_MIN}%)"
  fi
  if [[ "${GUARD_OK}" != "0" ]]; then valid="NO"; echo "!! dependency guard tripped:${GUARD_MSG}"; fi

  local pg_pct kf_pct
  pg_pct="$(tmpfs_pct "${PG}" /var/lib/postgresql/data)"
  kf_pct="$(tmpfs_pct "${KF}" /var/lib/kafka/data)"

  echo "   cpu: ${line}"
  echo "   jvm: heap_max=${heap_max}MB old_max=${old_max}MB ygc=${ygc} ygct=${ygct}ms fgc=${fgc} fgct=${fgct}ms direct_max=${direct_max}MB threads_max=${threads_max} jvm_cpu=${jvm_cpu}s"
  echo "   deps: pg_tmpfs=${pg_pct:-?}% kafka_tmpfs=${kf_pct:-?}% maintained=${maintained}% valid=${valid}"

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "${label}" "${protocol}" "${stream}" "${concurrency}" "${max_steal}" "${worst_cpu}" \
    "${sut_busy}" "${infra_busy}" "${mock_busy}" "${lg_busy}" "${maintained}" \
    "${heap_max}" "${old_max}" "${ygc}" "${ygct}" "${fgc}" "${fgct}" "${direct_max}" "${threads_max}" \
    "${pg_pct:-nan}" "${kf_pct:-nan}" "${valid}" >> "${RESULTS_DIR}/summary.csv"

  [[ "${valid}" == "yes" || "${ABORT_ON_INVALID}" != "1" ]] || {
    echo "ERROR: aborting suite on invalid scenario ${label}" >&2; exit 1; }
}

profile_case() {
  local label="profile_cross_stream_c${PROFILE_CONCURRENCY}"
  configure_mock
  echo "==> profiling ${label} (ctimer / alloc / lock) with jvm + cpu sampling"
  mpstat -P ALL 1 > "${RESULTS_DIR}/${label}.mpstat.txt" 2>&1 &
  local mp_pid=$!
  start_jvm_sampler "${RESULTS_DIR}/${label}"
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
  stop_jvm_sampler
  kill "${mp_pid}" 2>/dev/null || true
  echo "   cpu: $(steal_and_cpu "${RESULTS_DIR}/${label}.mpstat.txt")"
}

# --------------------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------------------

[[ "${RAMP}" == *s ]] && RAMP_SECS="${RAMP%s}" || RAMP_SECS="${RAMP}"
[[ "${DURATION}" == *s ]] && DURATION_SECS="${DURATION%s}" || DURATION_SECS="${DURATION}"

if ! curl -sf "${HEALTH_URL}" >/dev/null 2>&1; then
  echo "ERROR: target is not healthy at ${HEALTH_URL}" >&2; exit 1
fi

start_mock
guard_preflight
cap_kafka_retention
reset_infra

printf 'label,protocol,stream,concurrency,max_steal_pct,worst_cpu,sut_busy_pct,infra_busy_pct,mock_busy_pct,loadgen_busy_pct,inflight_maintained_pct,heap_max_mb,old_gen_max_mb,ygc,ygct_ms,fgc,fgct_ms,direct_max_mb,threads_max,pg_tmpfs_pct,kafka_tmpfs_pct,valid\n' > "${RESULTS_DIR}/summary.csv"

{
  echo "host=$(uname -n) kernel=$(uname -r) nproc=$(nproc)"
  echo "target=${TARGET} commit=$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "jar=$(ls -t "${REPO_ROOT}"/backend/gateway/target/hyperllm-gateway-*.jar 2>/dev/null | grep -v original | head -1)"
  echo "jvm=$(ps -o args= -p "$(gateway_pid)" 2>/dev/null | grep -oE -- '-XX:[^ ]+|-Xm[sx][^ ]+' | tr '\n' ' ')"
  echo "upstream: ttft=${TTFT_MS}ms rate=${TOKEN_RATE}tok/s frame=${TOKENS_PER_FRAME}tok out=${OUTPUT_TOKENS} in=${INPUT_TOKENS} model=${MODEL}"
  echo "load: ramp=${RAMP} duration=${DURATION} payload_tokens=${PAYLOAD_TOKENS} max_tokens=${MAX_TOKENS}"
  echo "guards: steal_max=${STEAL_MAX}% maintained_min=${MAINTAINED_MIN}% tmpfs_max=${TMPFS_MAX}% abort_on_invalid=${ABORT_ON_INVALID}"
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
