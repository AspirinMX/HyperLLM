# 全链路性能重构与容量复测报告（2026-11-26）

承接 [2026-11-26-ir-baseline.md](./2026-11-26-ir-baseline.md) 与 [2026-11-26-frame-path-optimizations.md](./2026-11-26-frame-path-optimizations.md)。前一份报告在 3,000 并发稳态下通过强制 Full GC 定位到核心瓶颈：**109.7 万个 `CanonicalTurn / TextPart` 在整个流式响应期间存活，单流常驻约 0.85 MB（3,000 流占 2.53 GB 真实存活集）**，导致 5g 堆在 3,000 并发打满触发 Full GC，甚至把堆加到 10g 也只换来更贵的 Mixed GC。

本报告记录在 **100% 保留 Canonical IR 作为安全边界与 4×4 跨协议单一事实源、且严格保留重试窗口内跨协议切换能力** 的前提下，完成的 6 次全链路性能重构提交，以及在同台 8 vCPU ARM64 压测机上的全量对比复测（含 3,000 并发存活集审计、跨协议验证、以及 G1GC vs Generational ZGC 实测）。

---

## 结论摘要

- **真实存活集暴降 93.9%（16.5×）**：3,000 并发 100K-token 稳态下强制 Full GC 后的老年代存活集从 **2.53 GB（2,530 MB）** 降至 **153.4 MB**；活跃 `byte[]` 从 **2.48 GB** 降至 **25.0 MB（−99.0%）**；`CanonicalTurn / TextPart` 从 **1,096,825 个** 降至 **0 个**；扣除约 50 MB 框架底座后，单流常驻堆内存从 **~850 KB/流** 压降至 **~34 KB/流（缩减 25×）**。
- **G1GC 老年代彻底清空，零 Full / 零 Mixed / 零 Concurrent**：5g 堆下 `2,000 / 3,000 / 3,500` 三档老年代峰值稳定在 **153 / 158 / 171 MB**（相比基线 3,000 档的 5,120 MB 打满下降 **96.9%**）；90 秒仅发生 **6 / 8 / 9 次 YGC**（此前 3,000 档为 123 次 YGC + 2 次 Full GC），总 GC 暂停从 **5,539 ms** 降至 **578 ms（−89.6%）**。
- **单机 4C ARM64 / 5g 稳态上限抬升 +40%（2,500 → 3,500 并发）**：
  - 此前 3,000 并发即打满 5g 堆（TTFT P99 `1,917 ms`，2 次 Full GC）；重构后 3,000 并发 **0 Full GC、TTFT P99 `943.3 ms`（−50.8%）**；
  - 新增 **3,500 并发** 档位在 4C ARM64 + 5g 堆直接稳过（G1：`295.85 RPS`、TTFT P99 `1,116.0 ms`、0 Full GC；Generational ZGC：**`311.32 RPS`、`124,838 SSE 帧/s`、`624,190 OutTPS`、全周期 STW 仅 `2 ms`、全流 P99 `12.02 s`**），此时压测机 `mock-upstream` CPU 已达 `87.9%`，逼近单机 harness 上限。
- **跨协议零额外代价**：Anthropic Messages → OpenAI Chat 在 `2,000 / 3,000` 并发下吞吐与延迟与同协议完全持平（`3,000` 并发：`278.20 RPS`、TTFT P50 `673.9 ms` / P99 `943.8 ms`、0 Full GC）。
- **x86 4 vCPU（2 物理核 × 2 HT）/ 6g 堆稳态突破 5,000 并发（较基线 +56.3%）**：此前 Intel Xeon Platinum 8372C 在 8g 堆上稳态上限 3,200（3,800 即 Full GC）、10g 堆 4,000 并发 TTFT P99 飙到 `2,501 ms` + Full GC；重构后仅用 **6g 堆**，`4,000` 并发 TTFT P99 降至 **`894.0 ms`（−64.3%）**，**`5,000` 并发 0 错误、TTFT P99 `1,103.7 ms`、老年代仅 `244 MB`、0 Full / 0 Mixed / 0 Concurrent GC**（甚至压到 7,000 并发仍 0 错误、0 Full GC、老年代 318 MB，仅受 2 物理核 ~142k 帧/s CPU 平台排队限制）。

---

## 一、测试环境与形状（与前两份报告严格一致）

| 项 | 配置 |
| :--- | :--- |
| 测试主机 | `debian-cax31-hel-1`（8 vCPU ARM64 Neoverse-N1 KVM，15 GiB RAM，无 Swap，内核 `6.12.107+deb13-arm64`） |
| 硬切核拓扑 | 网关 `0-3`（4C，`-XX:ActiveProcessorCount=4 -Xms5g -Xmx5g`、`nettyEpoll=true`）；Postgres/Redis/Kafka `4`（1C）；`mock-upstream` `5-6`（2C）；`loadgen` `7`（1C） |
| 上游形状 | 100K 输入（401 KB 请求体）/ 2000 输出 / 200 tok/s / 5 tok/帧（400 帧/流、25ms 间隔）/ 上游 500ms TTFT / 单流 ~10.5s |
| 负载控制 | `ramp=20s`、`duration=60s` 稳态窗口、闭环维持 N 条在飞流；依赖栈 tmpfs，场景间 `TRUNCATE outbox_events` + `FLUSHDB` |
| 校验门禁 | 本次所有汇报场景均满足 **0 错误、`%steal = 0.00`、`in-flight maintained = 100%`、容器 0 重启、`valid = yes`** |

---

## 二、改了什么（6 次提交）

| 提交 | 核心改动 | 解决的瓶颈 |
| :-- | :-- | :-- |
| `ded129f` | 移除 Controller `@RequestBody byte[]`；新增 `DetachUpstreamOperator`、`RequestIrHolder` 与增量 `TokenEstimator.TextTokenCounter`；先检通道容量再编码，重试窗口关闭（收到 2xx/不可重试 4xx 响应头或最终一次尝试）后立即释放 IR | 消除每流 128 KB `StringBuilder` 与重试窗口外常驻的 `CanonicalRequest` 图 |
| `6e237eb` | 在纯 Java `hyperllm-protocol` 引入 `ByteSink`、`JsonUtf8Writer`、`JsonUtf8Text`（严格 RFC 3629 UTF-8 + 规范转义校验的零拷贝切片）、`ValidatedJsonObject`（跳过工具 schema / tool call 参数 DOM 构建）和融合 token 计数；4 个协议 Codec 全面切换流式解码 + `ByteSink` 直写 | 消除解析阶段数十万 `String` / `ObjectNode` 分配与二次全树 token 遍历 |
| `5189642` | 引入池化堆外直写 `NettyByteBufSink`（带 `doOnDiscard` 零泄漏防护与 `PARANOID` 级单测）和分段 `RequestByteArena`（>512 KB 切 256 KB 分页避开 G1 Humongous）；统一大请求解析与编码卸载至 `protocolTranslationScheduler` | 消除 400 KB 上游请求体堆内二次分配与大请求 G1 Humongous 晋升 |
| `ec73c6a` | 流式链路升级为零分配 Push SPI（`StreamEventSink` + `feed(byte[], off, len, ByteSink)`）；4 个协议流式编解码器去每帧 `UTF8JsonGenerator` 与中间帧对象；`bridgeStreamingResponse` 改用有界 `Sinks.many` + `switchOnFirst`（仅首帧调用 `markServed`）+ 复用 `readScratch`；修复 `SseFrameBatcher` 并发与超大帧问题 | 消除每流 400+ 帧的热路径小对象风暴与每帧 `concatMap`/`Mono.just` 包装 |
| `6f27f90` | 路由 L1 预解析 `ParsedRouteMetadata`（`thinkingStyle`、`extraHeaders`、预计算 `ProviderCapabilities`）；无锁 CAS `OutboxQueue`；共享单线程 1s 扫描 `LeaseHeartbeatRegistry`；`Lease` 内嵌 `heldLegs` 免结算 Redis `HGET`；流式 `JsonParser` 读取解密后 `apiKey`；共享上游 `LoopResources`、`TCP_NODELAY`、补齐 `linux-x86_64` Epoll；`run-gateway.sh` 支持 `GATEWAY_GC=zgc` | 消除每请求重复 DOM 解析、每流 `Flux.interval` 定时器、结算多余 Redis RTT 与 Outbox 锁竞争 |
| `9e6d413` | **现场压测 `jcmd` 审计驱动修复**：`DetachUpstreamOperator` 终结时双向清空 `upstream` 与 `actual`，在 `DataBufferUtils.join` 后立即断链并在解析 `finally` 调用 `RequestByteArena.clear()` | 切断 Netty `HttpServerOperations.inbound (FluxReceive)` 向 `MonoCallable` 的单向保留链，释放 3,000 流上被隐性钉住的 1.20 GB `RequestByteArena` 缓冲 |

---

## 三、3,000 并发稳态存活集审计（为什么 `9e6d413` 极其关键）

在 3,000 并发稳态运行至第 25s（15s ramp + 10s 稳态）执行 `jcmd <pid> GC.class_histogram`（强制 Full GC 后统计实时存活对象）：

| 指标 | 基线 (`59dea37` / `d2cf567`) | 仅前 5 次提交 (`6f27f90`) | **最终版 (`9e6d413`)** | 相对基线变化 |
| :--- | --: | --: | --: | --: |
| Full GC 前老年代（稳态） | ~8,400 MB（10g 堆） | 2,885.6 MB | **1,208.3 MB** | −85.6% |
| **强制 Full GC 后真实存活集（OU）** | **2,530 MB** | 1,380.6 MB | **153.4 MB** | **−93.9%（16.5×）** |
| 活跃 `byte[]` 占用 | 2,480 MB（GC 前） | 1,238.1 MB | **25.0 MB** | **−99.0%（99×）** |
| `CanonicalTurn` / `TextPart` 实例 | 各 **1,096,825** | 各 1,095（26 KB） | **0** | **−100%** |
| `CanonicalRequest` 实例 | ~3,000 | 3（216 B） | **0** | **−100%** |
| `RequestByteArena` 实例 | — | **3,000（钉住 1.20 GB）** | **5（120 B）** | **−99.8%** |
| `TranslatingStreamBridge` / `SseEventSplitter` | ~3,000 | 2,929 / 2,929 | **2,997 / 2,997** | 仅剩真实在飞流状态 |
| **单流常驻堆内存（扣 ~50 MB 底座）** | **~850 KB / 流** | ~443 KB / 流 | **~34 KB / 流** | **−96%（25×）** |

在 `6f27f90` 中，`DetachUpstreamOperator` 切断了下游响应流向上的 `Subscription` 指针，所以 `CanonicalRequest` 已被释放；但 Netty 的 `HttpServerOperations`（整条响应流期间必须存活）持有的入站 `FluxReceive` 字段仍保留着对其 `Subscriber`（`DataBufferUtils.join` → `readAndParseRequest` → `MonoCallable`）的强引用，把 3,000 个 401 KB 的 `RequestByteArena` 顺着**下行方向**钉住了整整 10 秒。`9e6d413` 双向清空后，老年代真实存活集瞬间从 `1,380.6 MB` 归零式跌至 `153.4 MB`，**验证了“保留 IR + 重试窗口关闭即释放”的理论下界**。

---

## 四、同协议流式压测对比（4C ARM64 / 5g 堆 / G1GC）

### 4.1 全档位扫描（`6f27f90`，`500 ~ 3,500` 并发，暖态）

括号内为前一份报告中同机同协议基线（`500/1000/2000` 为 6g 暖态，`2500/3000` 为 5g `heap5g`）：

| 并发 | RPS | 帧率 (fps) | TTFT P50 (ms) | TTFT P99 (ms) | StreamTPS P50 | YGC / 暂停 | Full GC | 堆峰值 / 老年代 (MB) | Direct (MB) | 网关 CPU | mock CPU |
| --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 500 | 47.72 (47.7) | 19,134 | 507.6 (509) | **516.5** (525) | 201.0 | **5 / 134ms** (6 / 341ms) | 0 | 3220 / **836** (5156 / 3814) | 33.0 | 46.8% | 28.3% |
| 1000 | 95.42 (95.4) | 38,262 | 509.4 (513) | **536.3** (591) | 201.0 | **9 / 376ms** (10 / 873ms) | 0 | 3820 / **2384** (4888 / 3260) | 33.0 | **67.6%** | 41.9% |
| 2000 | **189.00** (187.5) | 75,789 | **564.0** (615) | **678.8** (920) | 200.6 | **17 / 1012ms** (33 / 2259ms) | 0 | 4309 / 3706 (5114 / 4462) | 41.0 | **89.4%** | 65.8% |
| 2500 | **233.45** (230.4) | 93,613 | **603.3** (738) | **787.1** (1319) | 199.9 | **19 / 1316ms** (79 / 3298ms) | 0 | 4064 / **3434** (4878 / 4622) | 37.0 | **91.3%** | 74.4% |
| 3000 | **276.38** (268.9) | 110,830 | **681.8** (1004) | **918.1** (1917) | 199.1 | **29 / 1618ms** (123 / 4600ms) | **0** (2 / 939ms) | 4250 / 3948 (**5120 / 5120**) | 45.0 | 92.9% | 83.7% |
| 3500 | **297.95** | 119,478 | **781.3** | **1099.0** | 188.9 | **33 / 1899ms** | **0** | 4288 / 3662 | 57.0 | 93.8% | 88.4% |

### 4.2 最终版高并发复测（`9e6d413`，G1GC 5g 堆，`2,000 / 3,000 / 3,500`）

彻底切断 `FluxReceive` 下行保留链后，高并发档位的老年代与 GC 行为发生质变：

| 并发 | 版本 | RPS | 入站 MB/s | TTFT P50 / P99 (ms) | 全流耗时 P99 | YGC 次数 | 总 GC 暂停 | Full / Mixed / Conc | 堆峰值 / 老年代峰值 (MB) | Direct (MB) | 网关 CPU |
| --: | :-- | --: | --: | --: | --: | --: | --: | :--: | --: | --: | --: |
| **2000** | 基线 5g (`c6d56ac`) | 180.2 | — | 752 / 1472 | — | 58 | 2,531 ms | 0 / — / — | 4,458 / 4,012 | 32.7 | 94.4% |
| **2000** | **最终版 (`9e6d413`)** | **188.40** | 72.1 | **574.5 / 710.5** | 10.78 s | **6** (−89.7%) | **302 ms** (−88.1%) | **0 / 0 / 0** | 3,195 / **153** (−96.2%) | 53.0 | 92.1% |
| **3000** | 基线 5g (`c6d56ac`) | 268.9 | — | 1004 / 1917 | — | 123 | 5,539 ms（含 FGC） | **2** / — / — | **5,120 / 5,120 (满)** | 32.7 | 93.3% |
| **3000** | **最终版 (`9e6d413`)** | **271.63** | 103.9 | **699.7 / 943.3** | 11.59 s | **8** (−93.5%) | **578 ms** (−89.6%) | **0 / 0 / 0** | 3,189 / **158** (−96.9%) | 73.0 | 92.7% |
| **3500** | **最终版 (`9e6d413`)** | **295.85** | 113.2 | **765.6 / 1116.0** | 13.27 s | **9** | **657 ms** | **0 / 0 / 0** | 3,237 / **171** | 73.0 | 93.4% |

从 `<label>.gc.txt` 逐行确认：`2,000 / 3,000 / 3,500` 三档全部 **0 次 Full GC、0 次 Concurrent Mark Cycle、0 次 Mixed GC**，平均每 10 秒才触发 1 次 Young GC，整个 90s 压测窗口 GC 停顿占比仅 **0.3% ~ 0.7%**；Direct Buffer 稳定在 `53–73 MB`，无任何堆外泄漏。

---

## 五、跨协议流式验证（Anthropic Messages → OpenAI Chat，G1GC 5g）

客户端发送 100K-token Anthropic Messages 请求，网关完整走完 **Anthropic 解码 → Canonical IR → Normalizer → OpenAI Chat 直写编码 → 上游 OpenAI SSE → 跨协议 Anthropic SSE 翻译** 全链路：

| 并发 | 版本 | RPS | 入站 MB/s | 帧率 (fps) | TTFT P50 / P99 (ms) | StreamTPS P50 | YGC / 暂停 | Full GC | 网关 CPU |
| --: | :-- | --: | --: | --: | --: | --: | --: | --: | --: |
| 2000 | 基线 (`59dea37`) | 187.50 | — | — | 620.0 / 910.0 | — | 36 | 0 | 91.8% |
| **2000** | **重构后 (`6f27f90`)** | **188.03** | 71.9 | 75,213 | **555.8 / 684.7** | **200.0** | **16 / 1,010ms** | **0** | **89.1%** |
| **3000** | **重构后 (`6f27f90`)** | **278.20** | 106.4 | 111,280 | **673.9 / 943.8** | **198.1** | **25 / 1,635ms** | **0** | **92.5%** |

跨协议在 `2,000` 和 `3,000` 并发下吞吐、延迟、CPU 占用与同协议几乎完全一致，证明保留并优化 Canonical IR 管道并未引入额外性能税收。

---

## 六、G1GC 5g vs Generational ZGC 5g（`9e6d413`）

在同机同配置（4C / 5g）切换 `GATEWAY_GC=zgc`（`-XX:+UseZGC -XX:+ZGenerational`）对比高并发档位：

| 并发 | GC | RPS | 入站 / SSE 帧率 | TTFT P50 / P99 (ms) | 全流耗时 P50 / P99 (s) | StreamTPS P50 | 90s 总 STW 暂停 | 老年代峰值 (MB) | Direct (MB) | 网关 CPU | mock CPU |
| --: | :-- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| **2000** | G1GC | 188.40 | 72.1 MB/s / 75.5k/s | 574.5 / 710.5 | 10.56 / 10.78 | 200.4 | 302 ms (6 次) | **153** | 53.0 | 92.1% | 65.4% |
| **2000** | **Gen ZGC** | **188.80** | 72.2 MB/s / 75.7k/s | **568.2 / 694.4** | **10.52 / 10.72** | **200.9** | **1 ms** (18 次) | 168 | **37.0** | **90.2%** | 61.4% |
| **3000** | G1GC | 271.63 | 103.9 MB/s / 108.9k/s | **699.7 / 943.3** | 10.91 / 11.59 | 196.0 | 578 ms (8 次) | **158** | 73.0 | **92.7%** | 82.9% |
| **3000** | **Gen ZGC** | **272.48** | 104.2 MB/s / 109.3k/s | 711.5 / 990.2 | **10.89 / 11.39** | **196.9** | **1 ms** (42 次) | 410 | **53.0** | 93.7% | 79.9% |
| **3500** | G1GC | 295.85 | 113.2 MB/s / 118.6k/s | **765.6 / 1116.0** | 11.46 / 13.27 | 187.2 | 657 ms (9 次) | **171** | **73.0** | **93.4%** | 87.9% |
| **3500** | **Gen ZGC** | **311.32** | **119.1 MB/s / 124.8k/s** | 879.3 / 1252.9 | **11.04 / 12.02** | **197.0** | **2 ms** (71 次) | 778 | 85.0 | 94.7% | 87.7% |

选型结论：
- **G1GC（默认）**：4 核高负载下 CPU 略低 1–1.3%，TTFT P50/P99 更优，老年代极度紧凑（153–171 MB），适合核数较紧张、追求最小内存占用或需要稳定压低首字节延迟的场景。
- **Generational ZGC（`GATEWAY_GC=zgc`）**：90 秒总 STW 停顿压缩到 **1–2 ms**（单次微秒级），彻底消除长流传输期间每 10 秒一次 ~70ms 停顿对流速率的打断；在 3,500 并发下实际有效吞吐高出 **+5.2%**（`311.32` vs `295.85 RPS`、`StreamTPS P50 197.0` vs `187.2 tok/s`、全流耗时 P99 快 **1.25 秒**），更适合 ≥4 核且追求平滑出帧体验的生产集群。

---

## 七、第二台机器（x86 Intel Xeon Platinum 8372C）5,000 并发复测

在另一台 8 vCPU x86 机器上（`VM-16-11-debian` / `Intel Xeon Platinum 8372C @ 3.20GHz`，**4 物理核 × 2 HT = 8 vCPU** / 15 GiB RAM / 内核 `6.12.115+deb13.1-amd64`）复现与 [2026-11-26-frame-path-optimizations.md](./2026-11-26-frame-path-optimizations.md) 第八节完全一致的核布局与上游形状：
- 核拓扑（`lscpu -e` 实测）：网关绑定 `0-3`（**独占 2 颗完整物理核 × 2 HT**，`-XX:ActiveProcessorCount=4 -Xms6g -Xmx6g -XX:+UseG1GC`，`nettyEpoll=true`、`upstreamMaxConnPerProvider=10000`）；infra 核 `4`；`mock-upstream` 核 `5-6`；`loadgen` 核 `7`。
- 下列所有档位均满足 **0 错误、`%steal = 0.00`、`in-flight maintained = 100%`、`valid = yes`**。

| 并发 | 版本 / 堆 | RPS | 入站 / SSE 帧率 | TTFT P50 / P99 (ms) | StreamTPS P50 | YGC / 暂停 | Full / Mixed / Conc | 老年代峰值 (MB) | Direct (MB) | 网关 CPU | mock CPU |
| --: | :-- | --: | --: | --: | --: | --: | :--: | --: | --: | --: | --: |
| 3,000 | 基线 x86 (8g) | 270.3 | — | 643 / 1,117 | — | 42 / 3,646 ms | 0 / — / — | 7,162（堆峰） | — | 85.7% | — |
| **3,000** | **重构后 (6g)** | 255.10 | 97.6 MB/s / 102.3k/s | **587.6 / 881.4** | 183.8 | **8 / 565 ms** | 0 / 4 / 2 | **288** | 48.8 | 86.0% | 60.4% |
| 3,800 | 基线 x86 (8g) | 318.6 | — | 960 / 1,946 | — | 60 / 4,691 ms | **1 (511ms)** | **8,191（满）** | — | 89.1% | — |
| 4,000 | 基线 x86 (10g) | 333.9 | — | 1,092 / 2,501 | — | 53 / 4,942 ms | **1 (601ms)** | 9,283（堆峰） | — | 89.6% | — |
| **4,000** | **重构后 (6g)** | **349.87** | **133.8 MB/s / 140.3k/s** | **646.7 / 894.0** | **188.9** | **9 / 647 ms** | **0 / 0 / 0** | **234** | 52.8 | 87.8% | 80.6% |
| **4,500** | **重构后 (6g)** | **350.72** | **134.2 MB/s / 140.6k/s** | **695.8 / 1,029.0** | 173.7 | **9 / 724 ms** | **0 / 0 / 0** | **241** | 44.8 | 89.6% | 85.3% |
| **5,000** | **重构后 (6g)** | **353.97** | **135.4 MB/s / 141.9k/s** | **716.6 / 1,103.7** | 156.9 | **10 / 833 ms** | **0 / 0 / 0** | **244** | 48.8 | 89.9% | 86.5% |
| 6,000* | 重构后 (6g) | 338.80 | 129.6 MB/s / 135.9k/s | 800.1 / 1,412.3 | 131.9 | 10 / 1,105 ms | 0 / — / — | 245 | 64.8 | 91.4% | 88.4% |
| 7,000* | 重构后 (6g) | 333.72 | 127.7 MB/s / 133.8k/s | 866.7 / 1,569.1 | 114.0 | 10 / 1,212 ms | 0 / — / — | 318 | 40.8 | 92.4% | 89.9% |

> `*` `6,000 / 7,000` 档在 0 错误、0 Full GC、老年代仅 245–318 MB 下仍可跑完，但受 2 物理核 ~354 RPS / ~142k 帧/s 吞吐平台限制，单流速率下降且 TTFT P99 超过 1.4s，不作为稳态推荐容量。

结论：
- **4,000 并发是舒适黄金档**：相比此前 10g 堆的 4,000 并发，堆减小 4 GB 的同时 TTFT P99 从 `2,501 ms` 暴降至 **`894.0 ms`（−64.3%）**，YGC 从 53 次降至 **9 次（−83%）**，Full GC 从 1 次归零，老年代仅 **234 MB**；
- **5,000 并发是该 4 vCPU（2 物理核）x86 节点的可用稳态上限**：比重构前 3,200 并发提升 **+56.3%**，吞吐达 **`353.97 RPS` / `141,941 SSE 帧/s` / `709,703 OutTPS`**，TTFT P99 仍压在 **`1,103.7 ms`**（低于重构前 3,000 并发的 `1,117 ms`），老年代仅 **244 MB**，**0 Full / 0 Mixed / 0 Concurrent GC**。

---

## 八、当前瓶颈判断与后续建议

1. **内存与 GC 已不再是瓶颈**：ARM64 3,500 并发老年代仅 171 MB，x86 5,000 并发老年代仅 244 MB（7,000 并发也仅 318 MB），GC 停顿占比 <0.9%（ZGC <0.003%）。内存维度已不再制约高并发扩展。
2. **当前上限完全由物理 CPU 核数（内核网络收发 + 事件循环）决定**：ARM64 4C 约 `311 RPS / 125k 帧/s`（3,500 并发），x86 2 物理核（4 HT vCPU）约 `354 RPS / 142k 帧/s`（4,000–5,000 并发）；若分配更多物理核或用独立发压机验证 SSE 帧合并窗口（`-Dgateway.stream.coalesce-window-ms=12`），可进一步线性抬高吞吐上限。
3. **小堆部署可行性**：由于 3,000 并发真实存活集仅 153 MB、5,000 并发老年代仅 244 MB，生产环境可显著下调单副本 JVM 堆配额（如 2g–4g），大幅提高单机部署密度。

---

## 九、复现命令与原始数据

```bash
# 1. 启动网关（G1GC 默认；切换 Generational ZGC 仅需 GATEWAY_GC=zgc；5,000+ 并发需调高单上游连接池上限）
GATEWAY_PORT=18081 GATEWAY_CPUS=0-3 GATEWAY_CPU_COUNT=4 GATEWAY_HEAP=6g GATEWAY_GC=g1 \
  HYPERLLM_UPSTREAM_MAX_CONNECTIONS_PER_PROVIDER=10000 \
  ./benchmark/scripts/run-gateway.sh

# 2. 同协议流式高并发扫描（ARM64 5g / x86 6g）
TARGET=http://127.0.0.1:18081 CONCURRENCIES="3000 4000 4500 5000" MODES=stream PAIRS=same \
  TAG=x86-g1-sweep ./benchmark/scripts/run-suite.sh

# 3. 跨协议流式扫描（Anthropic Messages -> OpenAI Chat）
TARGET=http://127.0.0.1:18081 CONCURRENCIES="2000 3000" MODES=stream PAIRS=cross \
  TAG=refactor-g1-cross ./benchmark/scripts/run-suite.sh

# 4. Generational ZGC 对比
GATEWAY_PORT=18081 GATEWAY_CPUS=0-3 GATEWAY_CPU_COUNT=4 GATEWAY_HEAP=5g GATEWAY_GC=zgc \
  ./benchmark/scripts/run-gateway.sh
TARGET=http://127.0.0.1:18081 CONCURRENCIES="2000 3000 3500" MODES=stream PAIRS=same \
  TAG=refactor-zgc-full ./benchmark/scripts/run-suite.sh
```

原始数据保留于两台压测机（不随仓库提交）：
- ARM64 (`tp8tp6n2.pagefault.app`)：
  - `/opt/HyperLLM/benchmark/results/refactor-g1-same-20261126-202722/`（`6f27f90`，G1 5g 500–3500）
  - `/opt/HyperLLM/benchmark/results/refactor-g1-cross-20261126-203812/`（`6f27f90`，跨协议 G1 5g 2000–3000）
  - `/opt/HyperLLM/benchmark/results/refactor-g1v2-same-20261126-204823/`（`9e6d413`，G1 5g 2000–3500）
  - `/opt/HyperLLM/benchmark/results/refactor-zgc-full-20261126-205952/`（`9e6d413`，Generational ZGC 5g 2000–3500）
  - `/tmp/liveset-v2-hist.txt`（`9e6d413`，3,000 并发稳态 `jcmd GC.class_histogram`）
- x86 (`110.40.188.131`)：
  - `/opt/HyperLLM/benchmark/results/x86-g1-sweep-20261126-212210/`（`d9f3293`，G1 6g 3000–5000）
  - `/opt/HyperLLM/benchmark/results/x86-g1-push-20261126-212902/`（`d9f3293`，G1 6g 6000–7000）
