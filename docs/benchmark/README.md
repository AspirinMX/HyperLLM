# HyperLLM 网关性能基准测试文档

本目录收录 `hyperllm-gateway` 从原始全 DOM 实现（`V0: 073e2d1`）演进至全链路性能重构（`V4: 9e6d413`）及 P0 Thread-per-Core 深度优化版（`V5: 8b60b2f`）的**全套同口径基准测试、交互式火焰图剖析（CPU / 内存分配 / 锁竞争）、堆存活集审计与双机容量评估**。

> **统一延迟口径（`ΔTTFT`）**：压测上游 `mock-upstream` 故意在首帧前休眠 `500 ms` 模拟真实模型首字推理时间。本套文档中**所有表格与图表的 TTFT 均只记录网关自身引入的净增量 `ΔTTFT = 实测首帧时间 − 500 ms`**，不再混入上游模拟等待时间。

---

## 核心结论

1. **4C8G 单机配额稳态突破 5,000 并发长流**：
   - 在 **腾讯云 CVM（计算型 C6 | `C6.2XLARGE16`）** 上，网关硬隔离 `4 vCPU`（2 物理核 × 2 HT）/`6 GiB` G1GC 堆，在 **100K-token（401 KB）长上下文输入、2,000 输出 token、200 tok/s** 的典型 Agent 负载下，稳定维持 **5,000 个并发 SSE 连接**（`353.97 RPS`、`141,941 帧/s`、`0 错误`、`ΔTTFT` P50 `+216.6 ms` / P99 `+603.7 ms`、老年代仅 **`244 MB`**、**0 Full / 0 Mixed / 0 Concurrent GC**）。
   - 在 **Hetzner Cost-Optimized `CAX 31`（ARM Neoverse-N1）** 上，经 `V5 (8b60b2f)` P0 优化与 Rust 压测套件解绑后，网关硬隔离 `4 vCPU` / `5 GiB` 堆，跨协议稳态可稳定维持 **4,000 个全速并发 SSE 连接**（`360.92 RPS`、`144,367 帧/s`、`StreamTPS P50 = 195.6 tok/s`、`ΔTTFT` P50 `+234.4 ms` / P99 `+398.8 ms`、`0 错误`、老年代仅 **`99 MB`**、**0 Full / 0 Mixed / 0 Concurrent GC**）；峰值吞吐可进一步承压至 **5,000 并发**（`384.02 RPS`、`153,607 帧/s`、`0 错误`、`0 Full GC`）。
2. **保留 Canonical IR 的同时实现 36× 单流常驻内存缩减**：
   - 所有请求统一经过 `downstream → Canonical IR → Normalizer → upstream`（安全边界 + 4×4 协议无损互通 + 重试窗口内可换协议重试，无同协议旁路特判）；
   - 通过 **7 轮火焰图驱动重构**，3,000 并发稳态强制 Full GC 后的真实存活集从 **2,741.4 MB（V3）** 降至 **154.3 MB（V4）**；单流常驻堆内存从 **~1,265 KB/流（V0）** 暴降至 **~35 KB/流（缩减 36×）**，跨协议与同协议性能完全持平。
3. **内存分配、GC 与锁竞争逼近内核物理底噪**：
   - 15s 稳态内存分配样本（`asprof -e alloc` @ `cross c1000`）：**`14,160`（V0）→ `2,941`（V4，−79.2%）**；
   - `cross c3000` 90s 总 GC 停顿：**`6,094 ms` / 112 YGC + 4 Full GC（V3）→ `487–587 ms` / 8–9 YGC + 0 Full GC（V4/V5，−90%+）**；
   - `cross c3000` 15s 锁竞争事件：**`1,379`（V4）→ `308`（V5，−77.7%）**，Netty `PoolArena` 跨线程锁再降 **90.6%**；`V5` 网关 CPU 中 **`92.56%`** 跑在 Netty Epoll I/O（其中纯内核 `libc.so.6` 系统调用独占 **`46.88%`**），整套 IR + 跨协议编解码仅占 **`12.96%`**。

---

## 双机最新容量总览（`V4 / V5`，G1GC）

> 所有场景均为 100K-token 输入 / 2,000 输出 / 200 tok/s / 5 tok/帧，`20s ramp + 60s 稳态`，`0 错误 / %steal = 0.00 / maintained = 100%`。

| 机型与规格 | 网关硬隔离配额 | 协议路径 / 版本 | 并发 | RPS | 帧率 (fps) | `ΔTTFT` P50 / P99 | YGC / 总暂停 | Full GC | 老年代峰值 | 网关 CPU |
| :--- | :--- | :-- | --: | --: | --: | --: | --: | --: | --: | --: |
| **腾讯云 CVM `C6.2XLARGE16`**<br>8C16G x86_64（Xeon 8372C） | 4 vCPU（2 物理核）<br>6 GiB 堆 | Same (`chat→chat`, V4) | 3,000 | 255.10 | 102,295 | +87.6 / +381.4 ms | 8 / 565 ms | **0** | 288 MB | 86.0% |
| 腾讯云 CVM `C6.2XLARGE16` | 4 vCPU / 6 GiB 堆 | Same (`chat→chat`, V4) | 4,000 | 349.87 | 140,297 | +146.7 / +394.0 ms | 9 / 647 ms | **0** | 234 MB | 87.8% |
| **腾讯云 CVM `C6.2XLARGE16`** | **4 vCPU / 6 GiB 堆** | **Same (`chat→chat`, V4)** | **5,000** | **353.97** | **141,941** | **+216.6 / +603.7 ms** | **10 / 833 ms** | **0** | **244 MB** | **89.9%** |
| **Hetzner `CAX 31`**<br>8C16G ARM64（Neoverse-N1） | 4 vCPU / 5 GiB 堆 | Cross (`anth→chat`, V5) | 3,000 | 275.70 | 110,280 | +157.7 / +380.5 ms *(充分预热 +143.4/+237.6)* | 9 / 587 ms | **0** | **91 MB** | 94.3% |
| Hetzner `CAX 31` | 4 vCPU / 5 GiB 堆 | Cross (`anth→chat`, V5) | 3,500 | 318.80 | 127,520 | +182.6 / +315.9 ms | 10 / 671 ms | **0** | **96 MB** | 94.5% |
| **Hetzner `CAX 31`** | **4 vCPU / 5 GiB 堆** | **Cross (`anth→chat`, V5)** | **4,000** | **360.92** | **144,367** | **+234.4 / +398.8 ms** | **11 / 811 ms** | **0** | **99 MB** | **95.2%** |
| Hetzner `CAX 31` *(吞吐峰值)* | 4 vCPU / 5 GiB 堆 | Cross (`anth→chat`, V5) | 5,000 | 384.02 | 153,607 | +357.1 / +608.1 ms | 13 / 1,227 ms | **0** | 337 MB | 96.7% |

---

## 文档与数据导航

| 文档 / 目录 | 说明 |
| :--- | :--- |
| **[01-methodology.md](./01-methodology.md)** | 硬件环境、硬切核拓扑、上游形状、`ΔTTFT` 计算口径、协议选择规则（直通期仅跨协议 vs 统一 IR 双协议）、有效性门禁与复现命令。 |
| **[02-evolution-arm64.md](./02-evolution-arm64.md)** | **ARM64（Hetzner `CAX 31`）六代版本演进报告（`V0 → V1 → V1.5 → V2 → V3 → V4 G1/ZGC`）**：全量 43 场景数据、6 张核心 SVG 图表、每代“火焰图定位 → 代码改造 → 火焰图验证”闭环、3,000 并发存活集审计与锁竞争分析。 |
| **[03-capacity-x86-and-arm64.md](./03-capacity-x86-and-arm64.md)** | **双机极限容量对比报告**：Hetzner `CAX 31`（ARM64，4,000 稳态 / 5,000 峰值）vs 腾讯云 `C6.2XLARGE16`（x86_64，5,000 稳态）容量边界、G1GC vs Generational ZGC 选型与生产 4C8G 部署建议。 |
| **[04-v5-p0-optimization-and-reflections.md](./04-v5-p0-optimization-and-reflections.md)** | **V5（`8b60b2f`）P0 深度优化方案、ARM64 `c3000 → c5500` 极限探测、`c4500` 性能拐点与 G1 Eden 收缩排障，以及本轮工程复盘感悟**。 |
| **[data/arm64-campaign-summary.csv](./data/arm64-campaign-summary.csv)** | ARM64 七代版本（`V0 → V5`）全 49 场景结构化汇总数据集（所有 TTFT 均为 `ΔTTFT`）。 |
| **[figures/](./figures/)** | 7 张高分辨率矢量对比图表（`.svg`）与 `figures/flamegraphs/` 下 **33 份原始 `async-profiler 4.0` 交互式 HTML 火焰图**（覆盖 `V0..V5` 的 CPU / Alloc / Lock，下载后用浏览器打开即可缩放、搜索与下钻）。 |
