# 04. V5（P0）深度优化方案、ARM64 4,000–5,000 并发极限探测与工程复盘

本报告记录在完成全链路内存与流式重构（`V4: 9e6d413`）之后，围绕 **ARM64（Hetzner `CAX 31`，4 vCPU / 5 GiB G1GC）跨协议路径（`Anthropic → OpenAI Chat`）** 开展的新一轮 **P0 深度优化（`V5: 8b60b2f`）**、**Rust 压测套件全面重写**、**`c3000 → c5500` 物理极限探测**、**`c4500` 性能拐点排障** 以及本轮实战的**工程复盘感悟**。

> **统一口径**：本文所有压测均采用 **100K-token（401 KB）长上下文输入 / 2,000 输出 token / 200 tok/s / 5 tok/帧**、`20s ramp + 60s 稳态` 闭环负载；所有 TTFT 均只记录网关自身净增耗时 **`ΔTTFT = 实测首帧时间 − 500 ms`**（已扣除上游模拟首字等待）。

---

## 一、优化动机与不可妥协的架构红线

在 `V4 (9e6d413)` 完成 36× 存活集压缩（老年代降至 131–144 MB）后，ARM64 跨协议停在 `3,500` 并发 / `298.08 RPS` / `119.2k fps`。对其 15s 稳态火焰图（`V4 @ cross c3000`）做二次剖析，发现除内核网络收发外，仍存在五类可压缩损耗：

1. **压测基础设施逼近饱和**：Go 版 `mock-upstream` 在 `c3500` 时已吃掉 **88.9% CPU**、Go 版 `loadgen` 达 **74.7% CPU**，Go GC 与调度抖动已开始污染网关侧上限观测；
2. **EventLoop 割裂与跨核调度**：服务端 Netty、上游 `WebClient` 连接池与 Lettuce 各自维护独立 EventLoopGroup，在 4 核上争抢调度并导致 `PooledByteBuf` 跨线程释放触发 `PoolArena` 锁竞争（53 次/15s）与 `eventFdWrite` 唤醒；
3. **Kafka 逐条发送触发大量唤醒锁**：`V4 @ c3000` 锁火焰图 `1,379` 次锁事件中，**`1,232` 次（89.3%）** 来自 Kafka Producer `EPollSelectorImpl.clearInterrupt`；
4. **SSE 流式路径仍有 5 层 Reactor 算子与逐帧 `ContextPropagation`**：`Flux.handle` + `concatWith` + `doOnDiscard` + `switchOnFirst` + `doOnNext` 叠加 Micrometer/Reactor 上下文传播占掉 `~3.2%` CPU；
5. **入站长文本逐字节 UTF-8 / JSON 转义校验**：`JsonUtf8Text.scanValidatedSlice` 在 401 KB 长上下文入站时占 `3.27%` CPU。

> **严格守住的设计底线（刻意放弃 P0-3）**：本轮**拒绝任何针对单一协议（如 `OpenAI → OpenAI`）的 SSE 旁路或直通特判**，所有请求与流式帧继续统一经过协议无关的 Canonical IR，确保未来扩展新协议的简洁性与安全边界不变。

---

## 二、优化方案与分步实施清单（7 笔 Commit 演进）

| 编号 | Commit | 优化主题 | 核心改动与机制 |
| :--: | :-- | :--- | :--- |
| **P0-0** | `ab1688c` | **压测三件套全面 Rust 重写** | 用 Rust（Tokio + Hyper HTTP/1.1 + 预构建 SSE 帧模板 + 零分配 chunk 读取）完整重写 `mock-upstream`、`loadgen`、`bench-seed`（CLI 参数、JSON 汇总、PostgreSQL 灌数 100% 兼容）；编译后产物仅 389–709 KB，彻底消除 Go GC 与调度干扰。 |
| **P0-1a** | `39b48d9` | **Thread-per-Core 共享 Epoll EventLoop + Kafka 批量遥测** | 在 `GatewayConfiguration` 中为 Netty Server 与 `UpstreamWebClientPool` 共享 4 线程 `hyperllm-io-epoll` 事件循环；为上游连接池引入按当前 EventLoop 绑定的 `PinnedLoopResources` 亲和桶，实现“同连接入站读、编码、出站写、回包读、下游写”全程停留在同一物理核，并显式开启 `TCP_NODELAY=true`；Kafka Producer 开启 `acks=1`、`batch-size=65536`、`linger.ms=20`。 |
| **P0-2** | `cc8d087` | **协议无关 `DirectStreamBridgeOperator`** | 新增自定义 Reactor `CoreSubscriber` 融合算子，直接驱动 `StreamBridge` 的流式转换、首帧单次 `markServed` 门控、异常/取消时的 `ByteBuf` 零泄漏释放，替代原有 5 层 Reactor 算子链，绕过逐帧 `ContextPropagation`。 |
| **P0-5** | `4d5a83f` | **协议无关 `JsonUtf8Text` 8 字节 SWAR 校验** | 在 `JsonUtf8Text.scanValidatedSlice` 中加入 8 字节（`long`）SWAR 快路径：通过按位掩码一次性验证 8 个纯可见 ASCII 字节（无 `< 0x20` 控制字符、无 `"`、无 `\`、无 `>= 0x80` 非 ASCII），遇到边界/多字节 UTF-8/转义字符再无缝回退逐字节状态机，并补齐 8 字节全偏移位安全单测。 |
| **P0-4** | `d9f94b2` | **零拷贝 Direct `ByteBuf` / `CompositeByteBuf` 帧合并** | 升级 `SseFrameBatcher` 与 `SseFrameCoalescer` 直接持有 Netty 池化 Direct `ByteBuf` 并用 `CompositeByteBuf` 零拷贝拼接输出，移除旧 `HeapByteSink` 路径。 |
| **Fix-1** | `cceca74` | **Linux Epoll Channel 类型解析修复 + 合并器惰性启动** | 修复 `PinnedLoopResources.onChannelClass()` 传入单个 `EpollEventLoop` 导致 Reactor Netty 回退 `NioSocketChannel` 报 `incompatible event loop type`（503）的线上兼容性问题，透传父 `EpollEventLoopGroup`；同时将 `FRAME_COALESCER` 改为仅在 `coalesce-window-ms > 0` 时惰性初始化，默认 0 后台线程。 |
| **Fix-2** | `8b60b2f` | **Lettuce Redis I/O 独立隔离 + 合并器并发 FIFO 修复** | **关键根因修复**：初版将 Lettuce 也并入 `hyperllm-io-epoll` 后，Lettuce 唯一一条多路复用 Redis TCP 连接被钉死在 `hyperllm-io-epoll-3` 上，与 ~1,000 条活跃 SSE 长流（~35,500 fps）排队争抢，导致首字前 3 次串行 Redis RPC 发生严重队头阻塞（`ΔTTFT min` 抬升 `+97~169 ms`）。将 `lettuceClientResources()` 独立为最小专有池（`ioThreadPoolSize=2` 下界，单连接仅懒启动 1 个 `lettuce-epollEventLoop-5-1` 线程），保持 Server + Upstream 4 核亲和不变；同步修复 `SseFrameCoalescer` 在并发 `pollBuf/drainBuf` 下先解锁再 `sink.emit` 引起的偶发帧乱序竞态。 |

---

## 三、ARM64 跨协议实测结果与极限探测（`c3000 → c5500`）

测试环境：Hetzner Cost-Optimized `CAX 31`（8 vCPU ARM Neoverse-N1 / 16 GiB RAM，内核 `6.12.107+deb13-arm64`），网关硬隔离 `taskset -c 0-3` + `-XX:ActiveProcessorCount=4 -Xms5g -Xmx5g -XX:+UseG1GC -XX:+AlwaysPreTouch`；Infra（PG/Redis/Kafka）占 CPU 4，Rust `mock-upstream` 占 CPU 5–6，Rust `loadgen` 占 CPU 7。

> 所有场景均满足 **`err = 0`、`%steal = 0.00`、`in-flight maintained = 100.0%`、`Full GC = 0`、`valid = yes`**。

| 版本 / 配置 | 并发 | RPS | 帧率 (fps) | 入站吞吐 | `ΔTTFT` P50 / P95 / P99 | StreamTPS P50 | YGC / 停顿 | Conc / Mixed / Full | 老年代峰值 | 网关 / Infra / Mock / LG CPU |
| :--- | --: | --: | --: | --: | --: | --: | --: | :--: | --: | --: |
| `V4` (`9e6d413`, Go 压测) | 3,000 | 277.75 | 111,100 | 106.25 MB/s | +174.6 / +301.8 / +370.0 ms | 198.9 | 8 / 487 ms | 0 / 0 / 0 | 141 MB | 93.3 / 64.4 / 84.3 / 76.2% |
| `V4` (`9e6d413`, 旧上限) | 3,500 | 298.08 | 119,233 | 114.03 MB/s | +255.5 / +421.8 / +532.5 ms | 185.3 | 10 / 659 ms | 0 / 0 / 0 | 144 MB | 94.6 / 65.8 / 88.9 / 74.7% |
| `V5` (`8b60b2f`, 刚预热) | 3,000 | 275.70 | 110,280 | 105.47 MB/s | +157.7 / +285.0 / +380.5 ms | 196.9 | 9 / 587 ms | 0 / 1 / 0 | **91 MB** | 94.3 / 57.8 / 66.8 / 58.7% |
| `V5` (`8b60b2f`, 充分预热 40s) | 3,000 | **278.93** | **111,570** | 106.70 MB/s | **+143.4 / +191.0 / +237.6 ms** | **199.0** | — | — | — | — |
| **`V5` (`8b60b2f`, `coalesce=0`)** | **3,500** | **318.80** (+7.0%) | **127,520** | 121.95 MB/s | **+182.6 / +255.3 / +315.9 ms** | **196.7** | 10 / 671 ms | 0 / 0 / 0 | **96 MB** | 94.5 / 61.1 / 75.6 / 61.6% |
| **`V5` (`8b60b2f`, 稳态推荐上限)** | **4,000** | **360.92** (+21.1%) | **144,367** | 138.07 MB/s | **+234.4 / +333.8 / +398.8 ms** | **195.6** | 11 / 811 ms | 0 / 0 / 0 | **99 MB** | 95.2 / 64.3 / 83.1 / 59.8% |
| `V5` (`8b60b2f`, `coalesce=35ms`) | 4,000 | 360.80 | 144,320 | 138.02 MB/s | +221.7 / +427.1 / +604.7 ms | 195.6 | 13 / 983 ms | 0 / 1 / 0 | 164 MB | 94.9 / 61.3 / 81.9 / 50.7% |
| `V5` (`8b60b2f`, 饱和拐点) | 4,500 | **381.32** (+27.9%) | **152,527** | 145.87 MB/s | +382.3 / +698.0 / +862.6 ms | 187.9 | 15 / 1,399 ms | 0 / 0 / 0 | 327 MB | 97.0 / 65.3 / 87.0 / 47.8% |
| **`V5` (`8b60b2f`, 吞吐峰值上限)** | **5,000** | **384.02** (**+28.8%**) | **153,607** | **146.90 MB/s** | +357.1 / +523.6 / +608.1 ms | 169.5 | 13 / 1,227 ms | 0 / 0 / 0 | 337 MB | 96.7 / 66.4 / 87.6 / 54.7% |
| `V5` (`8b60b2f`, 过载回落) | 5,500 | 367.75 | 147,100 | 140.68 MB/s | +384.1 / +579.6 / +687.4 ms | 144.0 | 12 / 1,198 ms | 0 / 0 / 0 | 348 MB | 96.8 / 68.1 / 89.3 / 52.9% |

**关键观测**：
- **4 核线程完全均衡**：`c4000` 结束后 `jcmd Thread.print` 显示 `hyperllm-io-epoll-1..4` 累计 CPU 分别为 `235.2s / 237.6s / 235.8s / 238.2s`（极差 `< 1.3%`），`lettuce-epollEventLoop-5-1` 独立承担 `47.2s`，彻底根治初版 `epoll-3` 单核过载和 Redis 首字队头阻塞。
- **`c4000` 是 ARM64 4C 的高质量稳态上限**：相比 `V4` 的 `c3500` 旧上限，并发多扛 500 条长流、吞吐高 `+21.1%`，但 `ΔTTFT` P50/P99（`+234.4 / +398.8 ms` vs `+255.5 / +532.5 ms`）、单流速率（`195.6` vs `185.3 tok/s`）和老年代峰值（`99 MB` vs `144 MB`）依然全面胜出。
- **`c5000` 跑出 4 核 ARM64 吞吐峰值**：`384.02 RPS / 153,607 fps`（`768k OutTPS`），较 `V4` 极限提升 **`+28.8%`**；到 `c5500` 虽出现吞吐回落，但全程仍为 **`0 错误 / 0 Full GC / 0 Mixed GC`**，背压退化优雅。
- **`coalesce=35ms` 实测证伪**：在 5 tok/帧（40 fps/流）下，35ms 窗口仅平均合并 ~1.4 帧，但独立 `sse-frame-coalescer` 线程耗掉 `15.7s` CPU 且每次 `flushDue` 跨线程唤醒 EventLoop，导致 `ΔTTFT` P99 反向恶化（`+398.8 → +604.7 ms`），仅让压测端 `loadgen` CPU 降低（`59.8% → 50.7%`）。因此**默认关闭（`coalesce=0`）是最优策略**。

---

## 四、专项排障：为什么 `c4500` 性能下降，甚至延迟比 `c5000` 还差？

对比上表可以看到两个耐人寻味的现象：
1. **宏观趋势**：`c4000 → c4500` 开始出现明显掉速（`StreamTPS P50` 从 `195.6 → 187.9 tok/s`），`ΔTTFT P50` 从 `+234.4` 跳升至 `+382.3 ms`；
2. **局部反常**：`c4500` 的 `ΔTTFT P99`（`+862.6 ms`）、YGC 停顿（`15 次 / 1,399 ms`）和 Direct 峰值（`104.6 MB`）**反而比更高并发的 `c5000`（`+608.1 ms`、`13 次 / 1,227 ms`、`76.6 MB`）更差**。

调取两轮原始 `gc.txt` 与吞吐曲线后，根因完全水落石出——是**硬件物理饱和拐点**与 **G1GC 自适应 Eden 收缩震荡**双重叠加导致的：

### 1. 物理层：超过 `c4000` 后正式撞上 4 核 ARM64 的 `~153.6k fps` 吞吐硬墙

- 在 **`c3000 → c3500 → c4000`** 区间，网关处于**线性扩展区**：并发每增加 500，RPS 严格稳定增加 `+43.1` / `+42.1`（`275.7 → 318.8 → 360.9 RPS`），单流速率紧贴上游 `200 tok/s`（`196.9 → 196.7 → 195.6`）。
- 到了 **`c4500`**，若维持全速理论需求约 `405 RPS / 162k fps`，但 4 颗 ARM Neoverse-N1 核心执行 Linux TCP 收发 + 跨协议编解码的**物理极限就是 `~384 RPS / 153.6k fps`**（`sut_busy` 在 `c4500` 瞬间顶到 **`97.0%`**，连 3% 调度余量都不剩）。
- 由排队论 Little's Law（$L = \lambda W$）可知：当吞吐 $\lambda$ 封顶而并发 $L$ 继续攀升，多出的并发**必然全部转化为排队等待时间 $W$ 与单流降速**：
  - `c4500`：RPS 增量直接腰斩（仅 `+20.4`，`360.9 → 381.3`），单流平均耗时从 `10.94s` 拉长至 `11.51s`（`StreamTPS 187.9`）；
  - `c5000`：RPS 彻底走平（`381.3 → 384.0`，`+0.7%`），单流拉长至 `12.63s`（`StreamTPS 169.5`）；
  - `c5500`：过载连接与上下文开销导致总吞吐反降（`384.0 → 367.8 RPS`），`StreamTPS` 掉到 `144.0`。

### 2. 运行时层：`c4500` 作为新进程首轮重压触发 G1GC Eden 剧烈收缩与过早晋升

`c4500 / c5000 / c5500` 是在单独一轮极限探测脚本中重启 JVM 后顺序执行的：`c4500` 之前仅跑了 25s 轻量 `c1000` 预热，而 `c4000` 前面已经历 `c1000 → c3000 → c3500` 三轮充分预热。查看 `c4500` 原始 GC 日志直接抓住铁证：

```text
# c4000（充分预热后）：Eden 稳定锁在 733–749 个 Region（~2.94 GB），每 ~6.6s 才 1 次 YGC
[285.421s] GC(40) Pause Young (Normal) 3166M->170M(5120M)  42.563ms   Eden regions: 736->0(749)
[293.765s] GC(41) Pause Young (Normal) 3166M->224M(5120M)  75.255ms   ...

# c4500（仅 c1000 预热后直接猛拉 4,500 并发）：Eden 遭遇断崖式收缩！
[ 81.350s] GC(21) Pause Young (Normal) 3154M->194M(5120M)  86.211ms   Eden regions: 765->0(474)
[ 85.568s] GC(22) Pause Young (Normal) 2126M->234M(5120M)  92.551ms   <-- Eden 被砍至 474 Region
[ 88.529s] GC(23) Pause Young (Normal) 1622M->279M(5120M) 108.700ms   <-- Eden 继续被砍！
[ 89.955s] GC(24) Pause Young (Normal)  839M->270M(5120M)  66.198ms   <-- 仅隔 1.4s！Eden 缩至 ~550 MB！
[ 94.654s] GC(25) Pause Young (Normal) 2626M->342M(5120M)  65.449ms   <-- 老年代因过早晋升飙升！
[ 97.972s] GC(26) Pause Young (Normal) 1950M->380M(5120M)  78.407ms
...
# c5000（接在 c4500 之后，Eden 重新恢复稳定）：
[174.597s] GC(36) Pause Young (Normal) 3397M->351M(5120M)  25.754ms   Eden regions: 726->0(762)
```

连锁反应非常清晰：
1. `c4500` 刚进稳态（`t=81s`）时，活跃对象复制耗时因并发陡增（4.5× 预热负载）升至 `86 → 109 ms`，G1GC 的自适应停顿预测器（默认目标 `MaxGCPauseMillis=200ms`）为了保停顿，**反向把 Eden 从 `3.0 GB`（765 Region）一口气缩到 `~550 MB`（~130 Region）**；
2. 缩小的 Eden 在 **9 秒内连续触发 4 次密集 YGC（最短间隔仅 1.4 秒，累计 STW 354 ms）**，直接把 `c4500` 的 `ΔTTFT P99` 砸到 `+862.6 ms`；
3. 密集 YGC 让大量寿命仅 10 秒的流对象在 `Survivor` 区装不下而**过早晋升（Premature Promotion）**，把老年代从 `84 MB` 一次性推高到 **`327 MB`**（且因远未达 45% IHOP 阈值，后续 `c5000/c5500` 一直保留在 `337–348 MB`）；
4. 轮到 `c5000` 运行时，G1 预测模型与 C2 JIT 均已收敛，Eden 回到 `726–763 Region`，YGC 反降回 `13 次 / 1,227 ms`，所以 `c5000` 的 `ΔTTFT P99`（`+608.1 ms`）反而显著好于首轮冲击的 `c4500`。

> **生产启示**：对于突发性极高的网关节点，可显式配置 `-XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=40`（兜底最小年轻代下限），防止突发流量瞬间触发 G1 过度收缩 Eden 引发过早晋升风暴。

---

## 五、火焰图同口径闭环验证（`V4` vs `V5` @ `cross c3000`, 15s）

原始交互式火焰图已归档至 [`figures/flamegraphs/`](./figures/flamegraphs/)：
- **CPU (`ctimer`, 5,873 样本)**：[v5-8b60b2f-cross-c3000-cpu.html](./figures/flamegraphs/v5-8b60b2f-cross-c3000-cpu.html)（对比 [V4 CPU](./figures/flamegraphs/v4-9e6d413-cross-c3000-cpu.html)）
- **Alloc (`512k`, 8,815 样本)**：[v5-8b60b2f-cross-c3000-alloc.html](./figures/flamegraphs/v5-8b60b2f-cross-c3000-alloc.html)（对比 [V4 Alloc](./figures/flamegraphs/v4-9e6d413-cross-c3000-alloc.html)）
- **Lock (`10us`, 308 事件)**：[v5-8b60b2f-cross-c3000-lock.html](./figures/flamegraphs/v5-8b60b2f-cross-c3000-lock.html)（对比 [V4 Lock](./figures/flamegraphs/v4-9e6d413-cross-c3000-lock.html)）

| 观测指标（同口径解析 15s 稳态采样） | `V4 (9e6d413)` | `V5 (8b60b2f)` | 变化与归因 |
| :--- | --: | --: | :--- |
| **总锁竞争事件（Lock）** | `1,379` | **`308`** | **−77.7%**，仅余 9 条调用栈 |
| Kafka Producer `EPollSelectorImpl.clearInterrupt` | `1,232` (89.3%) | **`230`** (74.7%) | **−81.3%**（`linger.ms=20` + `batch-size=65536` 批量刷盘） |
| Netty `PoolArena` 跨线程释放锁 | `53` (3.8%) | **`5`** (1.6%) | **−90.6%**（Server+Upstream 同核亲和命中 `PoolThreadCache`） |
| `JsonUtf8Text.scanValidatedSlice` CPU | `3.27%` (186) | **`1.40%`** (82) | **−55.9%**（8 字节 SWAR 生效，self 仅 `0.77%`） |
| Kafka Producer/Consumer CPU | `1.28%` (73) | **`0.48%`** (28) | **−61.6%** |
| `ContextPropagation / ThreadLocalAccessor` CPU | `3.22%` | **`0.09%`** (5) | **−97.2%**（`DirectStreamBridgeOperator` 绕过逐帧上下文传播） |
| `eventFdWrite` / 跨 EventLoop 任务投递 | `0.47%` / `0.76%` | **`0.31%` / `0.43%`** | Thread-per-Core 亲和压低跨核唤醒 |
| Netty Epoll I/O / 纯内核 `libc.so.6` CPU 占比 | `88.93%` / `40.89%` | **`92.56%` / `46.88%`** | 框架与业务开销继续退让，近半算力直接跑在 Linux 内核收发上 |

---

## 六、本轮实战的深度工程复盘与感悟

回顾从 `V4` 推向 `V5` 的全过程——包括中途一次令人困惑的“改完反而变慢”、一次 Epoll 线上 503、一次 `coalesce=35ms` 负收益实验——收获最大的不是某一个技巧，而是五条非常扎实的工程认知：

1. **压测工具本身就是被测系统的一部分：先证伪“测试端瓶颈”，再谈系统上限**
   - `V4` 时代在 ARM64 跑 `c3500` 时，`StreamTPS` 掉到 `185.3`、我们一度以为 3,500 就是网关的硬上限；但事实上当时 Go 版 `mock-upstream`（88.9% CPU）和 `loadgen`（74.7% CPU）已经先喘不过气了。
   - 换成 Rust 重写后，单机可测上限直接打开到 `4,000` 全速稳态 / `5,000` 吞吐峰值——本轮 `+28.8%` 的吞吐跃升里，大约一半归功于 Rust 压测解除了测试端假天花板，另一半归功于网关 P0 改造。另外 Rust 程序不自带 Go runtime 的自动拉高 `RLIMIT_NOFILE`，实测撞到 `EMFILE (1024)` 也提醒我们压测脚本必须显式 `ulimit -n`。

2. **“共享线程减少切换”绝不是银弹：警惕大象流对老鼠流的队头阻塞（Head-of-Line Blocking）**
   - 初版 P0 把 Server、Upstream、Lettuce 三者全合并到同一个 4 线程 `hyperllm-io-epoll` 后，`c3000` 的 `ΔTTFT` 不降反升（min 从 `+30 ms` 暴涨到 `+126 ms`）。
   - 用 `jcmd Thread.print` 一查才发现：Lettuce 只有一条多路复用 Redis TCP 连接，落在了 `epoll-3` 上——而每个新请求首字前要串行做 3 次 Redis RPC（小包、低延迟敏感的“老鼠流”），全部被迫排在 1,000 条 35,500 fps 的 SSE 长流（高吞吐的“大象流”）后面！
   - 把 Lettuce 拆回独立的最小 1 线程 EventLoop，同时保留 Server + Upstream 4 核亲和后，`c4000` 延迟瞬间腰斩。**数据面同核亲和、控制面低延迟 I/O 独立隔离**，才是响应式网关正确的线程拓扑。

3. **脱离具体工作负载谈“批处理/合并”容易做出负优化**
   - 理论上把多次 `writev` 合并为一次一定省 CPU，但我们在 5 tok/帧（40 fps/流）下实测 `coalesce=35ms`，结果 `ΔTTFT P99` 反升 200 ms、网关 CPU 几乎持平（`95.2% → 94.9%`）。
   - 原因很朴素：35ms 窗口对 25ms/帧的流平均只攒了 ~1.4 帧，但独立定时线程扫队列 + 加锁 + 跨线程回写 EventLoop 触发 `eventFdWrite` 的代价，刚好把省下来的那一点 `writev` 吃光了。性能优化必须**实测闭环**，只有 EventLoop 本地定时合并 + 1 tok/帧高频流场景才值得启用合并窗。

4. **做 JVM 压测必须剥离冷启动与 GC 自适应抖动，永远看原始日志而非只盯 CSV**
   - 同一份代码，`c3000` 冷启动首轮跑出 `ΔTTFT P99 +380.5 ms`，充分预热后降到 `+237.6 ms`；`c4500` 首轮重压撞上 G1 Eden 从 `3.0 GB` 缩到 `550 MB` 的自适应震荡，延迟甚至比后面的 `c5000` 还差 `254 ms`。
   - 如果只看汇总表，很容易得出完全错误的因果结论；只有把 `jcmd Thread.print`、`gc.txt`、`asprof` 折叠栈摊开交叉印证，才能看清真因。

5. **敬畏阿姆达尔定律，清楚“边际递减边界”在哪里，学会适时收手**
   - `V0 → V4` 把单流内存砍掉 36×、GC 停顿砍掉 92%–95%，属于高杠杆的结构性红利；到了 `V5`，火焰图已经给出极清晰的物理答案——**`Netty Epoll I/O` 占 `92.56%`，纯 Linux 内核 `libc.so.6` 系统调用独占 `46.88%`**，整个 `hyperllm-protocol` 只剩 `12.96%`，业务锁归零。
   - 这意味着在不更换协议栈/内核（如 io_uring/DPDK）或不牺牲帧延迟的前提下，Java 数据面剩余可榨空间只剩最后一层（主要是每帧新建 `UTF8StreamJsonParser` 贡献的 ~30% 堆分配，若改为按流复用的轻量字节级 JSON 扫描器，预计可再省 ~5–8% CPU）。
   - 在 **4 核 ARM64 跨协议已能稳定支撑 4,000 全速并发（老年代 99 MB、0 Full GC）、突发承压 5,000 并发（153.6k fps）** 的当下，网关数据面性能已足够扎实，继续死磕微优化的投入产出比远不如推进控制面、前端控制台与生产可观测性建设。
