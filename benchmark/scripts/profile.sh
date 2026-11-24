#!/usr/bin/env bash
set -euo pipefail

# Captures KVM/ARM64-safe async-profiler flamegraphs against the running hyperllm-gateway JVM.
# Usage:
#   ./benchmark/scripts/profile.sh [event=ctimer|alloc|lock|all] [duration_sec=15] [output_dir=/tmp/hyperllm-profiles]

EVENT="${1:-ctimer}"
DURATION="${2:-15}"
OUT_DIR="${3:-/tmp/hyperllm-profiles}"
ASPROF="${ASPROF:-/opt/async-profiler/bin/asprof}"

mkdir -p "${OUT_DIR}"

if [[ ! -x "${ASPROF}" ]]; then
  echo "ERROR: async-profiler not found at ${ASPROF}." >&2
  exit 1
fi

PID="$(jps -l | awk '/hyperllm-gateway/{print $1; exit}' || true)"
if [[ -z "${PID}" ]]; then
  echo "ERROR: hyperllm-gateway process not running." >&2
  exit 1
fi

TS="$(date +%Y%m%d-%H%M%S)"

run_one() {
  local ev="$1"
  local out="${OUT_DIR}/${ev}-${TS}.html"
  echo "==> Profiling PID=${PID} event=${ev} duration=${DURATION}s -> ${out}"
  "${ASPROF}" -e "${ev}" -d "${DURATION}" -f "${out}" "${PID}"
}

if [[ "${EVENT}" == "all" ]]; then
  run_one ctimer
  run_one alloc
  run_one lock
else
  run_one "${EVENT}"
fi

ls -lh "${OUT_DIR}"/*"${TS}"*.html
