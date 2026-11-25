# HyperLLM Gateway ARM64 KVM 大 Payload 零拷贝透传与跨协议流式优化报告

- **测试日期**：2026-11-25
- **对比版本**：
  - **初始基线**：`main@1174a6f`（[2026-11-25-gateway-arm64-baseline.md](./2026-11-25-gateway-arm64-baseline.md)）
  - **第一轮 P0/P1 优化**：`main@6d230e2`（[2026-11-25-gateway-arm64-p0-p1-optimization.md](./2026-11-25-gateway-arm64-p0-p1-optimization.md)）
  - **本轮大 Payload 专项优化**：`main@83c9336`（包含 2 个核心提交：`ff88f9d` 同构零拷贝状态机 + 流式元数据解耦、`83c9336` 跨协议大 `messages[]` 流式编解码 + CoW 归一化 + EventLoop 卸载）
- **测试环境与硬核隔离拓扑**：与前两轮完全同机同配置（芬兰赫尔辛基 `debian-cax31-hel-1`，8 vCPU ARM **Neoverse-N1** KVM，15 GiB RAM，Debian 13 `trixie` / Linux 6.12，OpenJDK `21.0.13.1`，`nettyEpoll=true`，`BENCH_TMPFS=1`，全程 `mpstat %steal = 0.00%`）
  - `Core 0-3`：4C `hyperllm-gateway`（`-XX:ActiveProcessorCount=4 -Xms5g -Xmx5g -XX:+UseG1GC -XX:+AlwaysPreTouch`）
  - `Core 4-5`：PostgreSQL 16 + Redis 7 + Kafka 3.9（tmpfs）
  - `Core 6`：`mock-upstream`（Go）
  - `Core 7`：`loadgen`（Go）
- **交互式火焰图附件**（4C 500KB Agent 场景，浏览器直接打开）：
  - 同构 500KB 零拷贝透传 CPU 火焰图（`ctimer`，采样期间 `1,723 RPS`）：[cpu-hom-500kb-4c.html](./artifacts/cpu-hom-500kb-4c.html)
  - 跨协议 500KB（Anthropic → OpenAI）流式转换 CPU 火焰图（`ctimer`，采样期间 `903 RPS`）：[cpu-xproto-500kb-4c.html](./artifacts/cpu-xproto-500kb-4c.html)

---

## 核心优化成果摘要

1. **同构大上下文（Coding Agent）吞吐跃升 `2.83×`，单机 4C 跑出 `1.25 GB/s` 入站**：
   - **50KB (+16 tools, `c32`)**：`729.33`（基线）→ `1,430.91`（`6d230e2`）→ **`2,249.83 RPS`**（较 `6d230e2` **+57.2%**，较基线 **`3.08×`**），P99 `31.89ms` → **`23.18ms`**。
   - **200KB (+16 tools, `c32`)**：`509.35`（基线）→ `741.12`（`6d230e2`）→ **`2,095.43 RPS`**（较 `6d230e2` **+182.7% / `2.83×`**，较基线 **`4.11×`**），P99 `53.61ms` → **`25.62ms`**，入站吞吐 **`433 MB/s`**。
   - **500KB (~125K tokens, `c32`)**：**`1,703.87 RPS`**，P50 **`18.37ms`** / P99 **`33.58ms`**，入站 **`874 MB/s`**（loopback 双向 ~1.75 GB/s）。
   - **1MB (~250K tokens, `c32`)**：**`1,216.24 RPS`**，P50 **`25.63ms`** / P99 **`46.67ms`**，入站 **`1,245 MB/s`**（loopback 双向 ~2.5 GB/s）。
2. **跨协议大 `messages[]` 流式翻译（`Anthropic → OpenAI Chat`）反超旧版同构路径**：
   - **50KB**：**`1,454.59 RPS`**（P99 `31.15ms`），已反超优化前 `6d230e2` **同构透传**成绩（`1,430.91 RPS`）。
   - **200KB**：**`1,404.67 RPS`**（P99 `37.27ms`，入站 `290 MB/s`），达到优化前 `6d230e2` **同构透传**（`741.12 RPS`）的 **`1.90×`**。
   - **500KB / 1MB**：完整跨协议流式转译分别达到 **`922.18 RPS`**（473 MB/s）与 **`539.67 RPS`**（553 MB/s），4C 网关纯算力打满至 `98.4%–99.5%` 且 **0 错误**。
3. **200 并发 500KB（125K-token）长连接流式 SSE：低 TTFT、零老年代钉死**：
   - 同构 `S5_hom_500kb_sse_c200`：**`199.22 RPS`**，TTFT P50 **`107.10ms`**（扣除 100ms Mock 仅 **`+7.10ms`**），P99 `117.17ms`，4C 网关 CPU 仅 **`28.5%`**。
   - 跨协议 `S5_xproto_500kb_sse_c200`：**`191.59 RPS`**，TTFT P50 **`110.51ms`**（完整跨协议翻译 + 鉴权 + 计费仅 **`+10.51ms`**），P99 `136.44ms`，4C 网关 CPU 仅 **`42.3%`**。
4. **`async-profiler 4.0` 验证三大架构假设完全落地**：
   - 同构 500KB 堆分配中，`StrictJsonRequestScanner` 仅占 **`0.2%`**、`PassthroughBytePatcher` 仅占 **`0.1%`**、`Netty ByteBuf Copy` **`0.0%`**，**`81.5%`** 为不可消除的 Socket 入站接收缓冲。
   - 跨协议 500KB 中，**`52.4%` CPU / `73.7%` Alloc** 运行在 `protocol-translate` 独立池（`>= 64KB` EventLoop 卸载生效）；`Jackson Databind (DOM)` 仅占 **`0.5%` CPU / `0.3%` Alloc**；`CanonicalRequestNormalizer (CoW)` 仅占 **`0.2%` CPU / `0.0%` Alloc**。
5. **极限大包压测下 GC 指标反而优于前代**：
   - 全套 15 场景累计分配 **`275.81 GB`**（是 `6d230e2` 的 `3.5×`），累计晋升老年代仅 **`74.33 MB`**（**晋升率 `0.027%`**，较 `6d230e2` 的 `0.052%` 接近腰斩）；
   - `G1 Old Gen` 稳态仅 **`75.44 MB`**（占 5G 堆 **`1.47%`**），`G1 Young` 平均停顿 **`12.0ms`**（最大 **`41ms`**，前代为 `21.2ms` / `54ms`），**`0` 次 Full GC**，Netty Direct Memory 稳定 **`34.23 MB`**，零泄漏。

---

## 一、本轮落地的架构改造明细

| Commit | 改动范围 | 核心优化要点 |
| :---: | :--- | :--- |
| `ff88f9d` | `protocol`: `StrictJsonRequestScanner`, `FastRequestProbe`, `PassthroughBytePatcher`, `ByteSlice`, `PreparedUpstreamPayload`, `StreamRequestMetadata`, `ImageDimensionProbe`, `TokenEstimator`; `gateway`: `GatewayProxyService` 及 4 个 `*PassthroughBridge` / `*StreamEncoder` | ① **同构零拷贝严格状态机**：单遍 UTF-8 扫描完成完整 RFC 8259 语法校验、顶层字段提取（`model`/`stream`/`max_tokens` 等）、O(1) 字节长度 Token 估算（含无解码 base64 图片尺寸探测）与字节偏移记录，彻底消除同构路径的 Jackson `ObjectNode` DOM 建树与重新序列化；② **零拷贝切片改写**：`PassthroughBytePatcher` 基于偏移将 `bodyBytes` 切为 `Unpooled.wrappedBuffer` 组成的 `CompositeByteBuf`，仅对改写的 `model` / `stream_options` 片段分配几十字节，发送后原子归零释放；③ **流式长连接元数据解耦**：`PassthroughBridge` / `StreamEncoder` / `TranslatingStreamBridge` 不再持有完整 `CanonicalRequest`，仅保留 ~48B `StreamRequestMetadata`，彻底根除 500KB+ SSE 长连接期间的大请求体老年代晋升。 |
| `83c9336` | `protocol`: `OpenAiChatCodec`, `AnthropicMessagesCodec`, `OpenAiResponsesCodec`, `GeminiGenerateContentCodec`, `CodecSupport`, `CanonicalRequestNormalizer`; `gateway`: `GatewayProperties`, `GatewayConfiguration`, `GatewayProxyService` | ① **跨协议流式 `JsonParser` 解码**：4 套协议编解码器直接用 Jackson `JsonParser` 流式扫描超大 `messages[]` / `input[]` / `contents[]` 数组，移除对历史 `tool_call` 参数的急切反序列化与重序列化；② **流式 `JsonGenerator` 零 DOM 编码**：`encodeRequest()` 全面改为面向预估容量 `ResizableByteBuffer` 的 `JsonGenerator` 直接写出，消除工具 Schema `.deepCopy()`、Anthropic 轮次分组零排序双遍遍历、Gemini `functionResponse` `{` 前缀预检消除异常风暴；③ **Copy-on-Write 归一化**：`CanonicalRequestNormalizer` 在干净对话历史上零拷贝、零 Map 分配快速返回；④ **EventLoop 卸载**：跨协议请求体 `>= 64KB` 时自动调度至独立并行池 `protocol-translate`（`max(2, cpus)` 线程），避免大 JSON 翻译阻塞 Netty EventLoop 上的并发 SSE 帧派发。 |

---

## 二、4C 全量压测结果对比（`Core 0-3`, `5G Heap`）

全部 15 个场景均在充分 JIT C2 预热后执行 `5s warmup + 15s steady-state`，每轮大流量间隙执行 `TRUNCATE outbox_events` 保持 tmpfs 干净；全量 **`err = 0`**（100% HTTP 200），`mpstat %steal = 0.00%`。

### 2.1 大 Payload Agent `messages[]` 矩阵（16 Tools，`c32`）

#### 同构零拷贝透传（`OpenAI Chat → OpenAI Chat`）

| 场景（实际载荷） | 基线 (`1174a6f`) | P0/P1 (`6d230e2`) | **本轮 (`83c9336`)** | 相对 `6d230e2` | Latency P50 / P95 / P99 | 入站吞吐 | CPU: GW(0-3) / Infra(4-5) / Mock(6) / LG(7) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`S3_hom_50kb_c32`** (52,482 B) | 729.33 RPS | 1,430.91 RPS | **2,249.83 RPS** | **+57.2%** *(3.08× Base)* | 13.72 / 19.86 / **23.18 ms** | 118 MB/s | 76.3% / **86.5%** / 34.6% / 44.9% |
| **`S3_hom_200kb_c32`** (206,692 B) | 509.35 RPS | 741.12 RPS | **2,095.43 RPS** | **+182.7% (2.83×)** *(4.11× Base)* | 14.91 / 21.77 / **25.62 ms** | **433 MB/s** | 83.6% / 83.0% / 48.7% / 56.0% |
| **`S3_hom_500kb_c32`** (512,909 B, ~125K tok) | — | — | **1,703.87 RPS** | *(新增)* | 18.37 / 27.97 / **33.58 ms** | **874 MB/s** | 86.9% / 79.3% / 63.9% / 67.5% |
| **`S3_hom_1000kb_c32`** (1,024,005 B, ~250K tok) | — | — | **1,216.24 RPS** | *(新增)* | 25.63 / 39.70 / **46.67 ms** | **1,245 MB/s** | 90.5% / 69.4% / 74.1% / 75.9% |

#### 跨协议流式转译 + EventLoop 卸载（`Anthropic → OpenAI Chat`）

| 场景（实际载荷） | **本轮 (`83c9336`)** | Latency P50 / P95 / P99 | 入站吞吐 | CPU: GW(0-3) / Infra(4-5) / Mock(6) / LG(7) | 关键观察 |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **`S3_xproto_50kb_c32`** (52,570 B) | **1,454.59 RPS** | 21.44 / 26.98 / **31.15 ms** | 76.5 MB/s | 67.2% / 73.1% / 27.8% / 33.6% | `< 64KB` 在 EventLoop 直跑，已超越旧版同构 50KB |
| **`S3_xproto_200kb_c32`** (206,780 B) | **1,404.67 RPS** | 22.22 / 31.23 / **37.27 ms** | **290 MB/s** | 90.7% / 72.6% / 38.2% / 43.9% | `>= 64KB` 卸载生效，达旧版同构 200KB 的 **1.90×** |
| **`S3_xproto_500kb_c32`** (512,997 B, ~125K tok) | **922.18 RPS** | 33.59 / 50.59 / **59.30 ms** | **473 MB/s** | **98.4%** / 60.2% / 42.2% / 43.2% | 4C 网关纯 CPU 算力天花板，发压/Mock 均无瓶颈 |
| **`S3_xproto_1000kb_c32`** (1,024,093 B, ~250K tok) | **539.67 RPS** | 54.64 / 103.52 / **124.42 ms** | **553 MB/s** | **99.5%** / 49.4% / 42.1% / 44.0% | 吞吐随字节长度近线性缩放，无内存抖动 |

#### 500KB 高并发 Agent 流式 SSE（`S5`, `c200`, Mock TTFT=100ms, 30 chunks @ 30ms）

| 场景（实际载荷） | RPS | SSE Events/s | TTFT P50 / P95 / P99 | 网关 TTFT 附加开销 (P50) | TPOT P50 / P99 | CPU: GW(0-3) / Infra(4-5) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **`S5_hom_500kb_sse_c200`** (512,908 B) | **199.22 RPS** | 6,024 ev/s | **107.10** / 113.50 / **117.17 ms** | **+7.10 ms** | 29.74 / 43.77 ms | **28.5%** / 29.1% |
| **`S5_xproto_500kb_sse_c200`** (512,996 B) | **191.59 RPS** | 6,708 ev/s | **110.51** / 123.82 / **136.44 ms** | **+10.51 ms** | 26.32 / 29.23 ms | **42.3%** / 32.3% |

### 2.2 核心常规场景回归（无回归且小包进一步提速）

| 场景 | 基线 (`1174a6f`) | P0/P1 (`6d230e2`) | **本轮 (`83c9336`)** | 相对 `6d230e2` | P50 / P99 (或 TTFT) | CPU: GW(0-3) / Infra(4-5) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **`S1_c64`** (219B) | 857.29 RPS | 2,560.34 RPS | **2,642.86 RPS** | **+3.2%** | 22.79 / 40.40 ms | 71.8% / **90.0%** |
| **`S1_c128`** (219B) | 1,073.41 RPS | 2,746.85 RPS | **2,845.31 RPS** | **+3.6%** | 42.01 / 83.97 ms | 77.9% / **88.1%** |
| **`S2a_sse_c1000`** (218B) | 877.19 RPS | 939.44 RPS | **946.51 RPS** | **+0.8%** *(29,377 ev/s)* | TTFT 116.33 / 178.29 ms | 80.3% / 61.1% |
| **`S2b_xproto_sse_c500`** (199B) | 478.26 RPS | 483.07 RPS | **479.37 RPS** | Mock 限速 *(16,797 ev/s)* | TTFT **108.63** / 128.38 ms | 51.8% / 40.3% |
| **`S4_abort_c100`** (218B) | 532.58 RPS | 561.35 RPS | **560.05 RPS** | Mock 限速 | TTFT 55.97 / 65.04 ms | 39.8% / 49.1% |

---

## 三、`async-profiler 4.0` 500KB 热点拆解（CPU `ctimer` & `alloc`）

采样配置：`24s` 持续压测期间，分别采集 `8s` HTML CPU 火焰图、`6s` collapsed CPU 样本与 `6s` collapsed Alloc 样本（均开启 `-t` 按线程分组）。

### 3.1 同构 500KB 零拷贝路径（`hom_500kb`，采样期间 `1,722.95 RPS`）

| 维度 | 分类 / 栈帧 | CPU (`ctimer`, `2,089` 样本) | Alloc (`bytes`, `9,958` 样本) | 验证结论 |
| :--- | :--- | :---: | :---: | :--- |
| **线程** | `reactor-http-epoll` (Netty EventLoop) | 70.9% | 78.5% | 同构不卸载，纯异步零拷贝 |
| **线程** | `lettuce-epoll` (Redis) / `outbox/db` | 21.2% / 3.6% | 14.6% / 6.0% | 正常计费与落库 |
| **子系统** | Netty Epoll / Linux Socket I/O | **63.4%** | **81.5%** | 1.75 GB/s loopback TCP 收发本身成为头号开销 |
| **子系统** | `StrictJsonRequestScanner`（严格状态机扫描） | **18.2%** *(`scanString` 15.5%)* | **0.2%** | **零分配扫描验证通过** |
| **子系统** | HyperLLM Gateway 业务逻辑（鉴权/计费/路由） | 14.1% | 15.8% | 固定单请求业务成本 |
| **子系统** | `PassthroughBytePatcher`（零拷贝切片改写） | **0.1%** | **0.1%** | **几乎零成本** |
| **子系统** | `Jackson Databind`（仅 350B 响应侧 usage 提取） | **1.3%** | **1.1%** | **请求侧 DOM 彻底消失** |
| **子系统** | `Netty ByteBuf Copy` / `G1 GC` | 0.0% / **0.2%** | **0.0%** / 0.0% | **零额外缓冲区拷贝** |

### 3.2 跨协议 500KB 流式路径（`xproto_500kb`，Anthropic → OpenAI，采样期间 `902.73 RPS`）

| 维度 | 分类 / 栈帧 | CPU (`ctimer`, `2,279` 样本) | Alloc (`bytes`, `18,791` 样本) | 验证结论 |
| :--- | :--- | :---: | :---: | :--- |
| **线程** | `protocol-translate` (独立卸载池) | **52.4%** | **73.7%** | **`>= 64KB` EventLoop 卸载完全生效** |
| **线程** | `reactor-http-epoll` (Netty EventLoop) | **33.4%** | **21.3%** | EventLoop 仅处理网络 I/O 与路由，不再被大包卡顿 |
| **线程** | `lettuce-epoll` / `outbox/db` / `G1 GC` | 9.3% / 2.1% / 0.5% | 3.0% / 1.6% / 0.0% | 健康低位 |
| **子系统** | `OpenAiChatCodec`（P1 `JsonGenerator` 流式编码） | **36.7%** | **35.1%** | `writeAssistantTurn` 17.3% + `writeUserOrToolTurn` 16.3% |
| **子系统** | `AnthropicMessagesCodec`（P1 `JsonParser` 流式解码） | **14.1%** | **38.4%** | `readStreamingMessages` 12.5% CPU / 35.9% Alloc |
| **子系统** | Netty Epoll / Linux Socket I/O | 30.3% | 21.7% | 946 MB/s 双向网络收发 |
| **子系统** | HyperLLM Gateway 业务逻辑 / `StrictJsonRequestScanner` | 8.5% / 7.9% | 4.3% / **0.0%** | 前置快速校验仅占 7.9% CPU 且 0 分配 |
| **子系统** | `Jackson Databind (DOM)` | **0.5%** | **0.3%** | **跨协议 `messages[]` DOM 建树彻底清零** |
| **子系统** | `CanonicalRequestNormalizer (CoW)` | **0.2%** | **0.0%** | **干净历史零开销验证通过** |

---

## 四、全套 15 场景累计 JVM GC、内存与日志健康度（Prometheus 实测）

| 指标（整轮 15 场景结束后快照） | `6d230e2` *(仅小包 + 50/200KB 同构)* | **`83c9336`** *(含 500KB/1MB 同构+跨协议 + 200 并发大流式)* | 说明 |
| :--- | :---: | :---: | :--- |
| 累计堆分配 (`jvm_gc_memory_allocated_bytes_total`) | 78.22 GB | **275.81 GB** | 总数据处理强度提升 **3.5×** |
| 累计晋升 (`jvm_gc_memory_promoted_bytes_total`) | 41.03 MB (**0.052%**) | **74.33 MB** (**0.027%**) | 晋升率反而**下降近一半**，大包全在 Eden 秒收 |
| `G1 Old Gen` 稳态占用 | 41.7 MB (0.81%) | **75.44 MB** (**1.47%** / 5,120 MB) | 200 并发 500KB SSE 零老年代钉死 |
| `G1 Young Evacuation` 停顿 | 26 次 / 均值 `21.2 ms` / 最大 `54 ms` | 89 次 / 总 `1.072s` / 均值 **`12.0 ms`** / 最大 **`41 ms`** | 平均与最大 STW 全面降低 |
| `Full GC` 次数 | **0** | **0** | 零 Full GC |
| Netty Direct Memory (`direct`) | 25.8 MB | **34.23 MB** | `Unpooled.wrappedBuffer` / `CompositeByteBuf` 零泄漏 |
| 活跃 / 峰值线程 | — | **46 / 49** | `protocol-translate` 并行池严格有界 |
| 日志 `ERROR` 条数 | 0 *(GatewayExceptionHandler)* | **1** *(仅 `S4_abort_c100` 8,402 次断流中 1 条 Reactor Netty `onErrorDropped: client response body has been released already due to cancellation`)* | 0 业务/计费/OOM/Handler 异常 |

---

## 五、后续 P2 潜在优化方向（火焰图实测洞察）

当前架构在 4C 上已达到同构 1.25 GB/s、跨协议 553 MB/s 的极高水位，若未来需要进一步榨干单核极限，本次 `async-profiler` 直接指明了两个高确定性的 P2 微优化点：

1. **消除跨协议输出 `ResizableByteBuffer.toExactByteArray()` 尾部拷贝（立减 ~17% Alloc）**：
   - 在 `xproto_500kb` Alloc 剖面中，`ResizableByteBuffer.<init>` 占 `17.7%`、`toExactByteArray()`（`Arrays.copyOf(buf, count)`）占 **`16.9%`**；让 `encodeRequest` 直接返回 `(byte[] array, int length)` 或 `ByteSlice` 交由 `Unpooled.wrappedBuffer(buf, 0, len)` 发送，可直接消除跨协议场景下整份 500KB/1MB 输出数组的一次全量拷贝。
2. **`StrictJsonRequestScanner.scanString` 8 字节 SWAR 批扫描（同构大包 CPU 降 10%+）**：
   - 同构 500KB CPU 剖面中 `scanString` 占 `15.5%`（当前逐字节检查 `"`、`\` 与 `< 0x20`）；用 Java `VarHandle` / `long` SWAR 8 字节步进扫描无转义纯 ASCII 文本段，可将扫描 CPU 再压低 3–4×。
