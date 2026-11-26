# 01. 压测方法论、测试环境与统一口径

本套基准测试的所有结论均建立在**硬隔离资源、闭环维持稳态并发、强门禁校验**三个前提之上，确保不同历史版本与不同机型之间的数据完全同口径可比。

---

## 一、两台测试主机与硬切核拓扑

| 维度 | ARM64 主机（全六代版本纵向重跑） | x86_64 主机（V4 高并发冲顶） |
| :--- | :--- | :--- |
| **云厂商与规格** | **Hetzner Cost-Optimized `CAX 31`** | **腾讯云 CVM（计算型 C6 \| `C6.2XLARGE16`）** |
| CPU | 8 vCPU ARM **Neoverse-N1**（单 NUMA，无 SMT） | 8 vCPU **Intel Xeon Platinum 8372C @ 3.20 GHz**（4 物理核 × 2 HT） |
| 内存 / Swap | 16 GiB（可用 ~15 GiB）/ 无 Swap | 16 GiB（可用 ~15 GiB）/ 无 Swap |
| OS / 内核 | Debian 13 `trixie` / `6.12.107+deb13-arm64` | Debian 13 `trixie` / `6.12.115+deb13.1-amd64` |
| JDK / Go | OpenJDK `21.0.13.1` / Go `1.24.4` | OpenJDK `21.0.13.1` / Go `1.24.4` |
| 剖析工具 | `async-profiler 4.0`（`ctimer` / `alloc` / `lock`，JFR 转 HTML + collapsed） | —（复用同机 V4 实测数据） |
| 依赖栈 | PostgreSQL 16-alpine、Redis 7-alpine、Kafka 3.9.0 KRaft（`tmpfs`，`synchronous_commit=off`） | 同左 |

### 硬切核布局（`taskset` + Docker `cpuset`）

| 角色 | 绑定 CPU | 配置说明 |
| :--- | :--- | :--- |
| **被测网关（SUT）** | **`0-3`（4 vCPU）** | `taskset -c 0-3` + `-XX:ActiveProcessorCount=4`；ARM64 堆 `5g`、x86 堆 `6g`（均满足 **4C8G** 单机部署配额）；`nettyEpoll=true`；在 x86 上 `0-3` 对应 **2 颗完整物理核 × 2 HT**，与 `4-7` 无超线程串扰 |
| PostgreSQL + Redis + Kafka | `4`（1 vCPU） | Docker `cpuset=4`，`tmpfs` 消除云盘 I/O 抖动，纯测网关自身上限 |
| `mock-upstream` | `5-6`（2 vCPU） | Go 编写，仅提供 `POST /v1/chat/completions`，模拟上游 LLM 推理与逐帧吐字 |
| `loadgen` | `7`（1 vCPU） | Go 编写，闭环维持目标并发并统计全链路指标 |

内核参数统一通过 [sysctl-bench.sh](../../benchmark/scripts/sysctl-bench.sh) 调优（`somaxconn=65535`、`ip_local_port_range=1024 65535`、`tcp_tw_reuse=1`、`fs.file-max=1048576`、`ulimit -n 65535`）。

---

## 二、上游负载形状与协议测试规则

### 2.1 典型长上下文 Agent 负载形状

| 参数 | 配置值 | 含义 |
| :--- | --: | :--- |
| 请求体大小（`PAYLOAD_TOKENS`） | `100,000` tokens（约 **401 KB** JSON） | 模拟多轮 Agent 长上下文会话 |
| 上游模拟首字等待（`TTFT_MS`） | **`500 ms`** | `mock-upstream` 收到请求后故意休眠 500 ms 再发首帧 |
| 输出长度（`OUTPUT_TOKENS`） | `2,000` tokens | 单次流式生成总量 |
| 生成速率（`TOKEN_RATE`） | `200 tok/s` | 单条流持续约 `0.5s + 10.0s = 10.5s` |
| 每帧 token 数（`TOKENS_PER_FRAME`） | `5` | 单条流 `400` 帧 SSE，帧间隔 `25 ms`（40 帧/s/流） |
| 负载窗口（`RAMP` / `DURATION`） | `20s` ramp + `60s` 稳态 | 均匀拉起 N 个闭环 worker，仅统计稳态 60s（约 6 个流周期） |

### 2.2 `ΔTTFT` 计算口径（严格只写净增量）

`loadgen` 测得的端到端首字节时间 `measured_ttft_ms` 是从发出 HTTP POST 到收到响应首条 `data:` 行之间的总耗时，天然包含上游故意休眠的 `500 ms`。为了直接反映**网关自身引入的延迟开销**，本套文档所有表格与图表统一采用：

$$\Delta\text{TTFT} = \max(0,\ \text{measured\_ttft\_ms} - 500\text{ ms})$$

例如：实测首帧 P50 = `559.3 ms`、P99 = `656.3 ms`，报告中严格记为 **`+59.3 ms` / `+156.3 ms`**。

### 2.3 历史版本的协议测试规则（公平对比原则）

由于上游 `mock-upstream` 固定提供 OpenAI Chat Completions 接口，客户端通过切换下游协议决定是否发生跨协议转换：
- **`same`（同协议）**：客户端 OpenAI Chat (`/v1/chat/completions`) → 网关 → 上游 OpenAI Chat
- **`cross`（跨协议）**：客户端 Anthropic Messages (`/v1/messages`) → 网关 → 上游 OpenAI Chat

历史版本演进中曾短暂存在"同协议零拷贝直通（绕过 Canonical IR）"的支线（`ff88f9d` / `83c9336`），后因安全审查与统一协议规范于 `b490694` 正式退役。因此本次 ARM64 全版本重跑严格遵循以下公平对比规则：
1. **统一 IR 之前（`V0: 073e2d1`、`V1: 6d230e2`、`V1.5: 83c9336`）**：**只测 `cross`（跨协议）**，避免被已退役的同协议直通旁路污染演进基线；
2. **统一 IR 之后（`V2: 207e61a`、`V3: 59dea37`、`V4: 9e6d413`）**：**`same` 与 `cross` 两个协议路径全测**，同时验证同/跨协议是否同价。

---

## 三、有效性门禁与同口径保障

每个场景前后均由 [run-suite.sh](../../benchmark/scripts/run-suite.sh) 自动执行：
1. 场景前 `TRUNCATE outbox_events` + `redis-cli FLUSHDB` + 重置 `mock-upstream` 计数；
2. 场景中并行采集：`mpstat -P ALL 1`（分核 CPU 与 `%steal`）、`jstat -gc`（每 2s 采样堆/老年代/GC）、`/actuator/prometheus`（Direct Buffer、活跃线程、进程 CPU）、以及按字节偏移切片的统一 GC 日志（`-Xlog:gc=info,gc+heap=info,gc+ergo=info,gc+phases=debug,gc+humongous=debug`）；
3. 门禁判定：若 `%steal > 0.5%`、或 `inflight_maintained_pct < 95%`、或依赖容器重启、或 `tmpfs > 70%`，标记 `valid=NO` 并作废；
4. **火焰图同源采样**：使用 `async-profiler 4.0` 在稳态窗口内分别采集 15s `ctimer`（CPU）、`alloc`（内存分配）与 `lock`（锁竞争）JFR，并由 `jfrconv` 从**同一份 JFR** 同时导出交互式 HTML 火焰图与 `.collapsed` 栈统计，确保文档热点百分比与原始 HTML 火焰图 100% 一致；
5. **真实存活集审计**：在稳态并发窗口内执行 `jcmd <pid> GC.class_histogram`（触发一次 Full GC 后输出全部存活对象直方图），精确测定不可回收的真实存活集与单流常驻内存。

---

## 四、一键复现命令

```bash
# 1. 启动被测网关（可通过 GATEWAY_JAR 指定不同历史版本 JAR，GATEWAY_GC 切换 g1 / zgc）
GATEWAY_PORT=18081 GATEWAY_CPUS=0-3 GATEWAY_CPU_COUNT=4 GATEWAY_HEAP=5g GATEWAY_GC=g1 \
  HYPERLLM_UPSTREAM_MAX_CONNECTIONS_PER_PROVIDER=10000 \
  ./benchmark/scripts/run-gateway.sh

# 2. 执行双协议流式矩阵（示例：V4 全档位）
TARGET=http://127.0.0.1:18081 PAIRS="same cross" MODES="stream" \
  CONCURRENCIES="500 1000 2000 2500 3000 3500" RAMP=20s DURATION=60s \
  ./benchmark/scripts/run-suite.sh

# 3. 稳态抓取 CPU / Alloc / Lock 交互式火焰图
./benchmark/scripts/profile.sh all 15 /tmp/hyperllm-profiles
```
