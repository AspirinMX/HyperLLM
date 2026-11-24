# HyperLLM Gateway Benchmark Suite

Zero-dependency Go benchmark tooling and CPU-pinned KVM/ARM64 harness for `hyperllm-gateway`.

## Components

- `cmd/mock-upstream`: High-concurrency mock upstream server supporting all 4 wire protocols (`/v1/chat/completions`, `/v1/responses`, `/v1/messages`, `/v1beta/models/*:generateContent` & `:streamGenerateContent?alt=sse`) using pre-serialized static byte slices and pooled zero-alloc request draining. Supports live reconfiguration via `POST /_mock/config?ttft_ms=...&chunks=...&interval_ms=...`.
- `cmd/loadgen`: High-concurrency non-streaming and SSE streaming load generator with warmup, exact latency/TTFT/TPOT percentiles (P50/P90/P95/P99/P99.9), large Coding Agent payloads (`-payload-kb` + `-tools`), slow-consumer simulation (`-slow-read-ms`), and mid-stream abort testing (`-abort-after-chunks`).
- `cmd/bench-seed`: Generates idempotent PostgreSQL seed SQL using the exact `HmacSha256` key digest and `AesGcmCipher` (`[1B ver][12B IV][AES-256-GCM + AAD]`) credential envelope format.
- `infra/docker-compose.bench.yml` + `docker-compose.bench.tmpfs.yml`: Lean host-network Postgres 16 + Redis 7 + Kafka 3.9 stack with `cpuset` isolation and optional zero-disk-jitter `tmpfs` override.
- `scripts/`: KVM sysctl tuning (`sysctl-bench.sh`), server setup (`setup-server.sh`), CPU-pinned gateway launcher (`run-gateway.sh`), full benchmark suite (`run-suite.sh`), and KVM/ARM64-safe `async-profiler` (`-e ctimer`, `-e alloc`, `-e lock`) capture (`profile.sh`).

## Scaled-Down Saturation Topology (8C16G ARM64 KVM)

| Mode | Gateway (`taskset` + `ActiveProcessorCount`) | Postgres + Redis + Kafka (`cpuset`) | Mock Upstream | Loadgen |
| :--- | :--- | :--- | :--- | :--- |
| **Phase A (2C ceiling)** | Core `0-1` (`2` CPUs, `3g` heap) | Core `2-4` (3 CPUs) | Core `5` (1 CPU) | Core `6-7` (2 CPUs) |
| **Phase B (4C scaling)** | Core `0-3` (`4` CPUs, `5g` heap) | Core `4-5` (2 CPUs) | Core `6` (1 CPU) | Core `7` (1 CPU) |

## Quick Start on Linux ARM64 Server

```bash
# 1. Build gateway JAR (skips tests)
./backend/mvnw -f backend/pom.xml package -pl common,domain,protocol,gateway -am -DskipTests

# 2. Setup infra, apply V1..V9 migrations, seed bench tenant/channels/models
BENCH_TMPFS=1 BENCH_INFRA_CPUS=2-4 ./benchmark/scripts/setup-server.sh

# 3. Start gateway pinned to 2 vCPUs (in tmux or background)
GATEWAY_CPUS=0-1 GATEWAY_CPU_COUNT=2 GATEWAY_HEAP=3g ./benchmark/scripts/run-gateway.sh

# 4. Run full S1..S4 benchmark suite
TAG=2c-tmpfs MOCK_CPUS=5 LOADGEN_CPUS=6-7 ./benchmark/scripts/run-suite.sh

# 5. Optional: capture KVM/ARM64 flamegraphs during a steady-state run
./benchmark/scripts/profile.sh ctimer 15
./benchmark/scripts/profile.sh alloc 15
./benchmark/scripts/profile.sh lock 15
```
