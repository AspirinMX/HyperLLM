# HyperLLM Benchmark Harness

A deliberately small, fully parameterised SSE/JSON benchmark for the gateway: one upstream shape, two
downstream protocols, a fixed set of concurrency levels, and strict host-level validation.

## The model under test

Every scenario reproduces one agent request profile:

| Parameter | Flag / env | Default |
| :--- | :--- | :--- |
| Prompt size (client payload) | `-payload-tokens` / `PAYLOAD_TOKENS` | `100000` tokens (~400 KB) |
| Upstream time to first token | `-ttft-ms` / `TTFT_MS` | `500` ms |
| Output tokens | `-output-tokens` / `OUTPUT_TOKENS` | `2000` |
| Token rate | `-token-rate` / `TOKEN_RATE` | `200` tok/s |
| Tokens per SSE frame | `-tokens-per-frame` / `TOKENS_PER_FRAME` | `5` |
| Reported prompt tokens (usage) | `-input-tokens` / `INPUT_TOKENS` | `100000` |

At the defaults a stream lasts `2000 / 200 = 10s`, carries `2000 / 5 = 400` frames, one frame every
`25ms`. All five knobs are settable at startup and at runtime through `POST /_mock/config`, so a
scenario can be re-shaped without restarting anything.

The mock serves **only** `POST /v1/chat/completions` (streaming and non-streaming). The upstream
protocol is therefore always OpenAI Chat Completions; the protocol pair under test is decided by what
the *client* speaks:

| Pair | Client → gateway | Gateway → upstream |
| :--- | :--- | :--- |
| `same` | OpenAI Chat Completions | OpenAI Chat Completions |
| `cross` | Anthropic Messages | OpenAI Chat Completions |

## Keeping N requests in flight for the whole window

Ten-second streams make naive load generation meaningless: launching 2000 requests at once would
measure a connection-acceptance spike, and measuring after the first wave drains would miss the steady
state entirely.

The load generator therefore:

1. **Ramps**: worker `i` of `N` starts at `ramp * i / N`, so connections arrive evenly over `-ramp`
   (default 20s) instead of all at once.
2. **Runs closed loop**: each worker sends one request, consumes it completely, then sends the next, so
   the in-flight count stays at `N` for as long as the run lasts.
3. **Measures only the steady window**: metrics cover the `-duration` window (default 60s) that starts
   after the ramp, which is >= 6 stream lifetimes at the defaults.
4. **Proves it**: the in-flight count is sampled every 100ms during the window and reported as
   `min / avg / p95 / max / maintained%`. A run whose `maintained%` is below 95 is marked invalid.

## Matrix

```
CONCURRENCIES = 500 1000 2000
MODES         = stream nonstream
PAIRS         = same cross
```

That is 12 scenarios, about 18 minutes. Each scenario's wall clock is
`RAMP + DURATION + one stream` (default `20s + 60s + ~10s`): the last request started inside the
window still has to finish before the process exits, and it is counted in the report.

Non-streaming responses deliberately take as long as a streaming one (TTFT + the same generation
time), so `stream` and `nonstream` hold concurrency under identical timing and can be compared
directly.

## Keeping the dependency stack alive

An 18-minute matrix at up to 2000 concurrent 10s streams is roughly `190 RPS x 90s x 12 ~ 200k`
requests. Every request writes a billing outbox row, a few Redis keys and one or two Kafka events, and
the bench stack is deliberately memory-backed:

| Dependency | Backing store | Growth per request | What it would do without hygiene |
| :--- | :--- | :--- | :--- |
| PostgreSQL | tmpfs **1.5 GiB** | 1 `outbox_events` row (~3 KB with indexes + WAL, measured: 92k rows = 321 MB) | ~400k rows fills the tmpfs, Postgres is OOM-killed and the run dies mid-matrix |
| Kafka | tmpfs **1 GiB** | ~0.5-1 KB per event | topics default to 7-day retention; the log never shrinks and fills the tmpfs |
| Redis | 2 GiB, `noeviction` | ~2 KB per request (hold, proof, unsettled set) | `settle:waiting` grows forever; on the next mirror rebuild the gateway answers 503 |

The suite therefore owns the stack:

1. **Kafka retention is capped** before the first scenario (`retention.ms=15m`,
   `retention.bytes=256MiB` per topic) so the log stays bounded no matter how long the matrix runs.
2. **Postgres is bounded**: `TRUNCATE outbox_events` between scenarios, while no load is running.
   One scenario is ~17k rows (~60 MB), so the 1.5 GiB tmpfs stays near 20%.
3. **Redis is flushed** between scenarios, which also prevents the entitlement mirror from being
   rebuilt against an unsettled ledger (that is what produces 503 `LEDGER_BACKLOG`).
4. **Every scenario is gated** on container health, `RestartCount` deltas and tmpfs usage. A scenario
   is marked `valid=NO` when any dependency restarted, when a tmpfs crosses `TMPFS_MAX` (default
   70%), when `%steal` exceeds `STEAL_MAX`, or when the in-flight concurrency was not maintained.
   Set `ABORT_ON_INVALID=1` to stop the whole suite at the first invalid scenario instead of
   continuing with a stack that may have restarted underneath it.

Both tmpfs percentages and the guard verdict are recorded per scenario, so a run can be audited
afterwards instead of trusted.

## CPU topology (8 vCPU KVM)

| Role | CPUs | Notes |
| :--- | :--- | :--- |
| System under test | `0-3` (`SUT_CPUS`) | e.g. `-XX:ActiveProcessorCount=4 -Xms6g -Xmx6g` + `taskset` |
| PostgreSQL + Redis + Kafka | `4` (`INFRA_CPUS`) | request rate is `N/10` RPS, one core is enough |
| mock-upstream | `5-6` (`MOCK_CPUS`) | emits up to ~130k frames/s |
| loadgen | `7` (`LOADGEN_CPUS`) | reads up to ~130k frames/s |

Everything is pinned; nothing shares a core with the system under test.

## Storage mode: tmpfs vs disk

`setup-server.sh` brings the bench stack up on tmpfs (`BENCH_TMPFS=1`, the default): Postgres runs
with `fsync=off`, `synchronous_commit=off`, `full_page_writes=off`, and its data directory, plus
Kafka's, live in RAM. That removes storage latency from the measurement, which is what you want when
the question is *how much work can the gateway itself do*, but it is not production.

At the request rates this matrix produces (~`N/10` RPS, so ~190 RPS at 2000 concurrent) the byte
volume is small — roughly 650 KB/s of outbox rows — so bandwidth is never the issue. What tmpfs hides
is **commit latency and storage tail**: one `fsync` per outbox batch, checkpoints and autovacuum
spikes. On this KVM guest that tail can be much worse than on production NVMe, so a disk-backed run of
the same scenario is neither strictly better nor worse — it is a different question.

Run both, and never mix them:

| Pass | Stack | Answers |
| :--- | :--- | :--- |
| ceiling (default) | `BENCH_TMPFS=1` | gateway CPU ceiling, A/B between two gateways, repeatable within a few percent |
| realism | `BENCH_TMPFS=0` (disk-backed volumes, Postgres defaults) | does durable billing change the answer at 1000/2000 concurrent |

Every run records the mode it used: `run.info` carries `storage: postgres=... kafka=...
synchronous_commit=...`, taken from the running containers, so a result can never be attributed to
the wrong configuration.

Switching storage means recreating the data containers (`docker compose down -v`), which also drops
the `newapi` database that shares this Postgres instance; `pg_dump`/`psql` it across the switch, or
point new-api at its own instance first.

## Validation rules

A scenario is marked **invalid** in `summary.csv` when either check fails, and the number should not
be quoted:

- **`max_steal_pct > STEAL_MAX` (default 0.5%)** — the vCPU was descheduled by the host, so the
  measurement describes the hypervisor, not the gateway. `%steal` is read from `mpstat -P ALL 1`
  for every scenario.
- **`inflight_maintained_pct < 95`** — the requested concurrency was not actually held during the
  measured window.

## Running

```bash
# 1. build the tools
cd backend && ./mvnw -q -f pom.xml package -pl common,domain,protocol,gateway -am -DskipTests && cd ..
(cd benchmark && go build -o bin/mock-upstream ./cmd/mock-upstream \
                    && go build -o bin/loadgen ./cmd/loadgen \
                    && go build -o bin/bench-seed ./cmd/bench-seed)

# 2. infrastructure, migrations, seed data
BENCH_TMPFS=1 BENCH_INFRA_CPUS=4 ./benchmark/scripts/setup-server.sh

# 3. gateway pinned to cores 0-3
GATEWAY_PORT=18081 GATEWAY_CPUS=0-3 GATEWAY_CPU_COUNT=4 GATEWAY_HEAP=6g \
  ./benchmark/scripts/run-gateway.sh
# the launcher always writes a unified GC log to /tmp/hyperllm-gc.log (-Xlog:gc*); the suite slices
# it per scenario into <label>.gc.txt, which is where GC phases and humongous allocations show up

# 4. the matrix (16 scenarios, ~16 x 80s)
TAG=baseline TARGET=http://127.0.0.1:18081 ./benchmark/scripts/run-suite.sh

# 5. optionally add one profiled scenario (async-profiler, cores 0-3)
PROFILE=1 TAG=baseline-profile TARGET=http://127.0.0.1:18081 ./benchmark/scripts/run-suite.sh
```

Useful overrides: `CONCURRENCIES`, `MODES`, `PAIRS`, `RAMP`, `DURATION`, `PAYLOAD_TOKENS`,
`TTFT_MS`, `TOKEN_RATE`, `TOKENS_PER_FRAME`, `OUTPUT_TOKENS`, `INPUT_TOKENS`, `STEAL_MAX`,
`MAINTAINED_MIN`, `TMPFS_MAX`, `RESET_INFRA`, `KAFKA_RETENTION_MS`, `KAFKA_RETENTION_BYTES`,
`ABORT_ON_INVALID`, `API_KEY`, `MODEL`, `HEALTH_URL`.

## What is recorded

A number is only useful next to the conditions that produced it, so every scenario stores the
following, and `summary.csv` carries the per-scenario subset:

| Group | Metrics | Source |
| :--- | :--- | :--- |
| Load | concurrency, ramp, duration, payload bytes/tokens, max_tokens, protocol pair | loadgen report |
| Throughput | RPS, SSE frames/s, aggregate output TPS, inbound/read MB/s | loadgen |
| Latency | TTFT p50/p90/p95/p99, TPOT, per-stream TPS, total latency, status counts | loadgen |
| Concurrency proof | in-flight min/avg/p95/max and the % of the window that held the target | loadgen sampler |
| CPU | per-role busy % (gateway 0-3, infra 4, mock 5-6, loadgen 7), `max_steal_pct`, worst-offending CPU | `mpstat -P ALL 1` |
| JVM | heap used peak, old-gen peak, YGC count/pause, Full GC count/pause, direct-buffer peak, live threads, process CPU seconds | `jstat -gc` + `/actuator/prometheus` |
| Dependencies | Postgres and Kafka tmpfs %, container health, `RestartCount` delta | `docker inspect` / `df` |
| Context | commit, jar path, JVM flags, kernel, nproc, storage mode and `synchronous_commit` | `run.info` |

## Outputs

`benchmark/results/<tag>-<timestamp>/`:

| File | Contents |
| :--- | :--- |
| `summary.csv` | one row per scenario, 22 columns: CPU split, `max_steal_pct`/`worst_cpu`, `inflight_maintained_pct`, `heap_max_mb`, `old_gen_max_mb`, `ygc`/`ygct_ms`, `fgc`/`fgct_ms`, `direct_max_mb`, `threads_max`, `pg_tmpfs_pct`, `kafka_tmpfs_pct`, `valid` |
| `<label>.json` | full report: RPS, SSE frames/s, aggregate output TPS, TTFT/TPOT/stream-TPS percentiles, latency, status counts, in-flight stats |
| `<label>.txt` | the same report as printed text |
| `<label>.mpstat.txt` | per-CPU samples (source of the steal and CPU columns) |
| `<label>.jstat.txt` | `jstat -gc` samples every 2s (heap, eden, old, YGC/YGCT, FGC/FGCT) |
| `<label>.prom.txt` | actuator samples: direct buffers, threads, outbox depth, process CPU |
| `<label>.mock-before/after.json` | upstream counters (leftover streams, aborted streams, bytes out) |
| `profiles/*.html` | async-profiler flamegraphs (`PROFILE=1`) |
| `run.info` | host, commit, jar, JVM flags, upstream shape, load parameters, guards, storage mode |

## Reading a result

1. `valid=yes` → the run counts. `valid=NO` → discard it and fix the host or the ramp.
2. Compare `rps` with `concurrency / 10.1s` — a lower value means requests were not queued at the
   configured rate (ramp too short, or the gateway is rejecting work).
3. `stream_tps.p50` must stay near the configured `TOKEN_RATE`; a drop means the gateway is
   buffering or the upstream is back-pressured.
4. Check `ttft_ms.p50/p99` for added latency versus the configured `TTFT_MS`.
5. Use the CPU columns to attribute the bottleneck. If `mock_busy` or `loadgen_busy` approaches
   100%, the harness — not the gateway — is the limit for that scenario.
6. Check `heap_max_mb` against `-Xmx`: a peak within ~10% of the heap means GC headroom is gone even
   when `fgc` is still zero, and the next concurrency step will pay for it in TTFT tails.
7. Compare `ygct_ms` with the window: pause time is a direct share of the measured wall clock.
8. Open `<label>.gc.txt` when `cgc` (concurrent cycles) climbs: that file shows whether the heap is
   being marked because old-gen occupancy is high, and whether any allocation went humongous
   (`G1 Humongous Allocation`), which is a different problem from a high promotion rate.

## Harness limits

Measured on the reference 8 vCPU ARM64 KVM box: mock ~60-68k frames/s per core (2 cores) and loadgen
~130k frames/s per core (1 core), i.e. roughly **130-150k frames/s** in total. At 200 frames/s per
stream that is ~600-750 concurrent streams; at 40 frames/s it is ~3,000-3,750. Beyond that, add a
second load-generating host or a second mock instance instead of reading gateway numbers out of a
harness-bound run.
