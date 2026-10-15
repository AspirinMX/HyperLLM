# HyperLLM 阶段二（Phase 2）最终设计方案

> 本文持有**阶段二的基线与决议**，2.4 及之后的全部子任务以本文的基线与决议为准；机制、时序与验收见 [04-04-settlement-pipeline.md](./04-04-settlement-pipeline.md)。
> 各子任务设计稿（04-01、04-02、04-03）记录「当时如何设计、实际如何落地」，保留作为历史与偏差证据；与本文冲突时以本文为准。
> 本文**取代** [04-00-overview.md](./04-00-overview.md) 第 2 节的 **T3**。
> 2.4、2.5、2.6 的机制、时序、配置与验收见 [04-04-settlement-pipeline.md](./04-04-settlement-pipeline.md)；本文继续持有基线与决议。

---

## 1. 设计基线

六条基线决定读路径、写路径与一致性边界的全部上限。任何子任务不得绕过。

| 编号 | 基线 |
| :--- | :--- |
| **B1** | **键存活期间，Redis 是余额与余量的唯一权威来源**，不区分现金、赠金、资源包 |
| **B2** | Redis 一律使用**滑动 TTL**；键的生命周期由访问驱动，活跃组织的键不得中断 |
| **B3** | 网关与 console / admin 一律 **hit-Redis / miss-DB** |
| **B4** | 写方向唯一：所有余额变更**先写 Redis，再经 outbox 落到账本**；数据库回到 Redis 只发生在缓存缺失时的加载（3.4 节），不存在「账本变更主动推 Redis」 |
| **B5** | miss 时按**租户维度整体加载**（余额 + 赠金批次 + 资源包余量）并镜像到 Redis |
| **B6** | 批量与汇总查询直接读数据库；**单租户的读或写必须先加载 Redis** |

### 1.1 为什么是滑动 TTL 而不是固定 TTL

固定 TTL 会在任意时刻删掉一个正在使用的键，包括**请求进行中**。后果不是余额偏差，而是状态损坏：

1. 长流请求跑过 TTL，钱包键消失；
2. 结算脚本对不存在的键执行 HINCRBY，Redis 自动创建一个**只含金额字段、没有 status 的半截键**；
3. 下一次准入读到 status 为空，判定 ORG_SUSPENDED，**整个组织被停用**，且不会自愈（键现在存在，TTL 从此刻重新计时）。

滑动 TTL 从根上消除这个形状：键只在**组织空闲**时消失，而空闲组织的请求生命周期早已结束。

### 1.2 为什么 B1 与「miss 才重建」不冲突

重建误差的上界是**最后一次被消费之后发生的消耗量**，即消费者延迟窗口 lag 内的消耗，与 TTL 长短无关（checkpoint 由消费者逐笔推进，它一直在追 Redis，只是慢一个 lag）。

在滑动 TTL 下，键只在空闲时消失；空闲意味着这段时间没有新请求，也就没有新的结算事件，消费者早已追平。**因此稳态下重建误差趋近于零。** 唯一的例外是消费者故障（见第 7 节）。

---

## 2. 数据归属（取代原 T3）

原 T3 规定「批次余额的权威来源是 PostgreSQL 批次表」，与基线 B1 直接冲突，作废。新的三层职责：

| 层 | 承担 | 性质 | 写者 | 读者 |
| :--- | :--- | :--- | :--- | :--- |
| **Redis** | 余额、赠金批次余量、资源包余量、预占、限流状态、配置热态 | **实时权威**（键存活期间） | 网关、console、admin 共用同一个写入口原语 | 网关、console、admin |
| **账本** wallet_ledger | 逐笔资金变动，append-only、幂等键唯一 | **唯一重建依据** | admin 的结算消费者与发放事务 | 对账、审计、重建、报表 |
| **数据库快照** wallets.balance / resource_packs.remaining_units / bonus_grants.remaining_micros | 账本的**折叠结果** | 派生态，允许落后 | 同上 | 批量列表、报表、miss 加载、重建 |

三条推论，必须同时成立：

- **R1**：请求路径（准入、结算）**禁止读取** resource_packs / bonus_grants 来判断「还能用多少」与「扣哪一批」。
- **R2**：wallets 余额列可以被落后于 Redis，只要它最终收敛；它不参与扣费判定。
- **R3**：任何时刻都满足「账本折叠值 = Redis 实时值 + 未落账消耗」，差额由对账解释（第 7 节）。

---

## 3. Redis 键规范

### 3.1 键与生命周期

| 键 | 类型 | 载荷 | TTL / 续期 | 缺失语义 |
| :--- | :--- | :--- | :--- | :--- |
| gw:wallet:{orgId} | HASH | cash_micros、held_cash_micros、status | 滑动 300s | MISS_WALLET，触发加载 |
| gw:pack:{orgId}:{modelCode} | ZSET | member = packId，score = expires_at epoch ms | 滑动 300s | 触发加载 |
| gw:pack:item:{packId} | HASH | remaining_units、held_units、total_units、unit_type、status、expires_at | 滑动 300s | 触发加载 |
| gw:bonus:{orgId} | ZSET | member = grantId，score = expires_at（无限期取 4102444800000） | 滑动 300s | 触发加载 |
| gw:bonus:item:{grantId} | HASH | remaining_micros、held_micros、initial_micros、models、status、expires_at | 滑动 300s | 触发加载 |
| gw:hold:{holdId} | HASH | 三条腿金额、legs JSON、served、org_id、project_id、api_key_id、model_code、created_at | PEXPIRE 为 2 倍租约 | 无 |
| gw:holds:exp | ZSET | member = holdId，score = 租约到期 epoch ms | 无 TTL | 无 |
| gw:ledger:pos:{orgId} | HASH | produced、since | **无 TTL** | 无 |
| gw:settle:evt:{eventId} | STRING | outbox payload 副本 | 300s | 由巡检重投兜底 |
| gw:settle:pending | ZSET | member = eventId，score = 结算时刻 epoch ms | 无 TTL | 无 |
| gw:hold:done:{holdId} | STRING | 结算摘要 JSON | 12 倍租约 | 无 |
| gw:wallet:idem:{eventId} | STRING | **发放类**幂等标记（消耗类复用 gw:hold:done，不再另建） | 24 小时 | 无 |
| gw:cfg:key / gw:cfg:route / gw:cfg:override | STRING | 配置 JSON | 1800s 加 CDC 驱逐 | 回源 |
| gw:rl:rpm / gw:rl:conc | STRING | 限流与并发计数 | 按分钟或租约 | 无 |
| gw:sandbox:{sha256} | STRING | Playground 临时凭证 | 300s | 无 |

本表已按 [04-04](./04-04-settlement-pipeline.md) 第 2.1 节修订，相对早期设计有三处变化：删除聚合字段 `general_bonus_micros` 与 `gw:pack:avail` / `gw:pack:held`（改为逐批次精确冻结）；删除 `gw:org:holds`（惰性回收改为按 `gw:holds:exp` 全局扫描，且它是按组织永久存在的键）；新增 `gw:ledger:pos`、`gw:settle:evt`、`gw:settle:pending`。

### 3.2 续期点（滑动 TTL 的唯一实现手段）

**任何触碰钱包与批次键族的位置都必须续期**，缺一处就会出现「键在请求中途消失」。

| 续期点 | 涉及的键 |
| :--- | :--- |
| 准入 prehold_admit.lua | gw:wallet、各腿 item 键 |
| 心跳 prehold_renew.lua | **gw:wallet、各腿 item 键**、gw:hold、并发计数 |
| 结算 prehold_settle.lua | 同上 |
| 释放 prehold_release.lua | 同上 |
| 读路径（网关冷回源、console / admin 查询、miss 加载完成时） | 被读到的键 |

**续期节流**：网关在本地记录每个键的最近续期时刻，同一键 60 秒内最多续一次；命中节流时脚本收到 `renew = 0`，不执行 `PEXPIRE`。只要「有流量就永不过期」，续期频率对正确性没有影响，而每请求写五个 `PEXPIRE` 在 AOF 上是纯写放大。

> 心跳是**长流唯一的续期机会**。当前 prehold_renew.lua 的 KEYS 里没有 gw:wallet，这是启用滑动 TTL 之前必须先修的前置项（见第 9 节 D-3）。

### 3.3 缺失键纪律

> **任何 Lua 脚本禁止在不存在的键上执行 HINCRBY 或 SET 等写命令。**

统一流程：

1. 脚本先 EXISTS；不存在则返回哨兵值（MISS_WALLET / MISS_ENTITLEMENTS），不做任何写入。
2. 应用层收到哨兵后按租户整体加载，写回 Redis。
3. 重试一次；仍缺失则按「无余额」返回 402，不进入上游调用。

### 3.4 miss 加载协议（B5）

一次加载必须同时覆盖余额、赠金批次、资源包余量，因为它们共同决定三顺位分摊：

1. 进程内 SingleFlight 去重，与 Phase 1 的 warmWalletIfMissing 同形态。
2. PostgreSQL 读：wallets 与 organizations.status；resource_packs（status = ACTIVE、expires_at 大于当前、applicable_models 包含该模型）；bonus_grants（同条件，且允许 expires_at 为空）。
3. 单次原子写入：钱包 HASH、pack 与 bonus 两个 ZSET、各 item HASH；**每个键先 EXISTS，已存在则跳过**（只补缺失）。
4. 统一 PEXPIRE 300s。

**加载只补缺失**：脚本对每个目标键先 EXISTS，已存在则跳过，绝不整租户覆盖。全量覆盖会把加载窗口内并发结算已扣掉的 delta 抹掉。

**键级互斥**：键缺失时，任何 delta 写入一律返回哨兵、不做写入；键存在时，加载不再覆盖它。两条合起来，同一个键在任一时刻只可能被一个写者碰——缺失时只有加载能创建，存在时只有 delta 能修改。重建与增量因此在结构上不会交错。

**加载失败不降级放行**：读不到批次就按「无权益」处理，只允许现金与赠金快照参与分摊；绝不因为读库失败而跳过计费。

**为什么不需要版本戳（决议 Q1）**：早期设计用 gw:ent:ver 与 gw:ent:stale 表达「镜像代际」与「需强制重建」。滑动 TTL 已承担同样职责——键的消失本身就是重建信号，而加载在单个 Lua 脚本内原子完成（DEL 旧结构、写入新结构、设置 TTL 一次做完），不存在 Java 侧逐条写入的中间态，也就不需要代际号来检测「重建期间被写过」。两者废除。

与之配套的仅有两条规则：加载必须原子；加载期间遇到并发的结算脚本时，以先完成者为准，后到者对已存在的键不再重建。

### 3.5 兼容期措施

Phase 1 遗留数据中，wallets.bonus_balance_micros 可能大于所有 bonus_grants.remaining_micros 之和（有快照、无批次）。加载时把差额作为**未镜像赠金桶**追加为最后一个赠金桶，保证历史余额可消费且不重复计费。

该措施在 2.6（Outbox 投影）完成后，快照恒等于批次之和，差额自动为 0，可移除。

---

## 4. 读路径

| 入口 | 数据源 | 口径 |
| :--- | :--- | :--- |
| 网关准入与结算 | Redis | 实时权威 |
| console 单租户钱包与批次 | Redis（miss 加载） | 实时权威 |
| admin 单租户详情、改余额前置 | Redis（miss 加载） | 实时权威 |
| admin 租户列表、报表、导出 | PostgreSQL | 账本已确认值，允许落后 |

### 4.1 口径标注要求

列表与详情读的是不同的东西，界面必须显式区分，否则运营会把差异当成故障：

- 展示实时值时必须同时给出三个数：**余额、冻结（held 类字段）、可用（余额减冻结）**。只显示余额会让运营误以为可以花。
- 列表页余额需标注「账本已确认」；若同时展示实时值，需标注「含未落账消耗」。
- 单租户详情页建议同时展示实时值与账本值及差额，把 lag 可视化。

### 4.2 租户列表的内联余额

列表必须能直接看到余额，但既不能逐租户读 Redis（N 次往返），也不能逐租户查 wallets（N+1）。做法：

- 列表查询以 organizations 为主表 **LEFT JOIN wallets**，一次取出 cash_balance_micros 与 bonus_balance_micros；
- 数据库侧没有预占概念，因此列表**只展示余额，不展示冻结与可用**——这两个数只有 Redis 有；
- 列表值标注「账本已确认」，并提示「详情页为实时值」；
- 禁止在列表渲染时逐行回源 Redis；点入详情才加载实时值（对应基线 B6）。

---

## 5. 写路径

写路径**按业务方向分叉**，这是全篇最重要的修订（原 B4「写方向唯一」据此作废，详见 [04-04](./04-04-settlement-pipeline.md) 第 1 节）：

| | 消耗（扣钱） | 发放（充钱） |
| :--- | :--- | :--- |
| 先后 | 先改 Redis，再写 outbox | 先写 outbox，再改 Redis |
| 允许丢 | 是 | **否** |
| 失效方向 | 平台少收钱 | 无 |

> **扣钱先动钱，给钱先记账。**

唯一的反向存在是缓存缺失时的加载（3.4 节），它不属于写路径，也不做任何同步。

### 5.1 统一写入口

无论变更来自网关、console 还是 admin，都必须经过同一个原语：

1. 确定 eventId（调用方给出 idempotencyKey，或由服务端生成）。
2. **Redis Lua 原子执行**：先查幂等标记（消耗类为 `gw:hold:done:{holdId}`，发放类为 `gw:wallet:idem:{eventId}`），命中则直接返回 DUPLICATE 且不再向下；否则应用 delta，新增批次时同时写 ZSET 与 item HASH，**发号 `seq` 并推进 `gw:ledger:pos`**，写幂等标记，并续期被触碰的键。
3. 写 outbox（5.4 节），事件里携带同一个 eventId。**消耗路径在此处的提交方式是异步批量；发放路径是把这一步放进业务事务、先于 Redis 提交。**
4. Debezium 到单一金额 topic，消费者写账本、更新 wallets、按明细扣减批次行。

两条不可协商的规则：

- **幂等标记必须与变更在同一个 Lua 内写入**，由应用层另写一次会让重试翻倍。
- **outbox 的幂等键与 Redis 幂等键必须是同一个 eventId**，否则重放时两边判断不一致。

### 5.2 消耗方向

1. **准入**：一次 `prehold_snapshot.lua` 取回钱包与逐条腿，全部来自 Redis。
2. **分摊**：PricingResolver 加 EntitlementAllocator，纯 Java 三顺位。
3. **冻结**：prehold_admit.lua，限额加三档冻结加惰性回收加续期。
4. **转发**：首字节前失败换渠道重试；流式分片心跳续期。
5. **结算**：prehold_settle.lua，以 hold 的腿为快照重分摊，释放预占、按腿递减各 item 与钱包冻结、扣 cash（钳到非负）、写 done 标记与事件副本、续期。
6. **事件**：cashDeltaMicros、bonusDeltaMicros、packDeltaUnits、allocationDetailJson（逐批次腿，id 必填）。
7. **投递与落库**：写入 outbox，经 Debezium 到 `cdc.billing`（key = orgId），由 2.5 的消费者按明细扣批次、写账本、更新 wallets。

### 5.3 发放方向

充值、赠金、资源包发放、人工调账、对账修正走**记录优先**：在业务事务内先写 outbox 行（纯 delta），提交后再由应用者落地到 Redis（消费者为常规路径，生产者可抢先应用一次只是加速）。于是「行存在 = 这笔发放已被授权」，消费者不需要判断它是否已经生效。

- **双写不会翻倍**：Redis 的幂等标记与 delta 在同一个 Lua 内写入，同一 eventId 无论被应用多少次都只生效一次。
- **新增批次**（购买的资源包、发放的赠金）由应用层生成完整对象（UUIDv7、到期时间、总单位、适用模型）并写入 outbox 的 payload；批次行由消费者按事件创建，Redis 的 ZSET 与 item 由应用者应用 delta 时一并写入。
- 代价一：消费者处理到之前，PostgreSQL 没有该批次行；此窗口内若组织的键恰好过期并被加载，会读不到这个新批次（窗口等于消费延迟，由 7.3 的积压监视器覆盖）。
- 代价二：租户处于冷态时应用 delta 会拿到哨兵，只能「折叠 PostgreSQL、跳过 Redis」，权益最长滞后一个 TTL 才可见。这是「键缺失时不写 delta」的既定代价，见 3.4 节。

### 5.4 outbox 写入与顺序插入优化

网关每个请求写一行 outbox，因此写入必须按「追加」设计，不能按「在线事务表」设计。

**表侧**

| 优化 | 做法 | 收益 |
| :--- | :--- | :--- |
| 主键有序 | UUIDv7 | B-tree 只在右端追加，无页分裂、无随机 IO |
| 零二级索引 | 删掉 idx_outbox_events_created | 每少一个索引少一次写放大；purge 改走主键区间 |
| 只追加 | 只有 INSERT 与按区间 DELETE，从不 UPDATE | 无 HOT 更新，页密度稳定 |
| fillfactor 100 | 表级参数 | 不留更新空间 |

**写入侧**

| 优化 | 做法 |
| :--- | :--- |
| 请求路径零 IO | 收尾只入队，不做数据库调用 |
| 单写者 | 后台一个线程批量提交；并发插入会争抢 B-tree 右端页与 WAL 锁 |
| 批量提交 | 每 20ms 或满 200 条触发一次 multi-row INSERT，单批上限 500 行 |
| 独立连接池 | outbox 写 2 个连接，冷回源 6 个；全部预算仍是 8 |
| 可选 | outbox 事务内 SET LOCAL synchronous_commit 关闭，用批量窗口换 fsync 次数 |

**背压与溢出**

| 队列状态 | 行为 | 后果 |
| :--- | :--- | :--- |
| 未满 | 入队即返回 | 无 |
| 接近满 | 加速刷、增大批量 | 延迟略增 |
| 水位 > 50% 持续 30s，或失败率 > 1% 且 1 分钟内 ≥ 50 条 | **该实例停止准入，返回 503** | 新请求一分钱没扣，重试安全 |
| 恢复正常 | 自动放开准入 | 无 |

**响应语义**：准入之前的 503 是唯一合法的 503；**钱一旦动了，响应里不再出现错误码**。结算之后 outbox 写失败不改变响应，事件走 4.3 节的事件副本与巡检重投。

**这是本方案的显式代价**：数据面从「只依赖 Redis」变成「依赖 Redis 与 PostgreSQL」。PostgreSQL 的可用性目标必须提到与网关同级；**准入闸门**不做 fail-open，因为预付费系统里放行等于免费使用，损失无上界（这与 7.3.4 节「监视器失联按 OK」是两件事：前者是「记录不下来就别接客」，后者是「监控挂了别停客」）。

### 5.5 outbox 生命周期：水位删除

outbox 是传输表，不是历史表。需要删除，但**删除只解决存储，与投递可靠性无关**：Debezium 用逻辑解码读 WAL，INSERT 的完整 tuple 在 WAL 里，删行不会让事件消失，也不会让 Debezium 漏读。

因此不设「保留期」，改为按投递水位删除：

1. 消费者每处理完一批，把已处理到的 outbox id 写入 `outbox_watermark` 单行表（选表而非 Redis：它必须与 purge 作业同时可用，且不能因 Redis 重启而回退）。
2. purge 作业每 30 秒执行：DELETE FROM outbox_events WHERE id <= 水位 LIMIT 5000，循环直到删完。
3. 因为主键是 UUIDv7 且单调，id 区间等价于时间区间，走主键索引即可，不需要 created_at 索引。

稳态表大小等于未确认事件数，约等于端到端延迟乘 QPS，与总流量无关；Debezium 或消费者停机时表自然增长，恢复后自动回落。**表长度因此成为积压的可视化指标**，与 7.3 的健康监视器同源。

唯一兜底：水位停滞超过 24 小时时仍然按时间兜底删除，防止表无限膨胀拖垮实例；此时必须打 P1 告警，且**告警依据是水位停了，不是表大了**。

**切换条件**：若压测中 purge 跟不上，或表行数持续超过 100 万，改为按日 RANGE 分区 + 校验「分区内最大 id ≤ 水位」后 DROP PARTITION。该方案需要 publication 开启 publish_via_partition_root 并实测投递链路，因此不进入首版。

### 5.6 计数字段的写语义

> **计数字段只接受增量语义，禁止绝对赋值。**

危险不在「谁在写」，而在「以什么为基准算 delta」：

| 写法 | 是否安全 | 说明 |
| :--- | :--- | :--- |
| HINCRBY cash 加 delta | 安全 | 与并发扣减可交换，顺序无关 |
| HSET cash 绝对新值 | **禁止** | 抹掉区间内全部并发扣减 |
| HSET status 新状态 | 安全 | 状态字段没有并发增量 |

**delta 的计算规则**：

- 增量操作（充值、赠予、补偿）：delta 等于请求金额，与任何当前值无关，误差恒为 0。
- 绝对目标操作（「把余额补到 X」）：delta 等于 X 减实时值，**实时值必须在写路径上当场从 Redis 读取**；禁止使用界面展示值、前置查询结果或任何数据库值作为基准。

### 5.7 只有一个入口

console 与 admin 不得绕过统一写入口直接修改 Redis 计数字段；网关同样不得另起一套写逻辑。三条路径的差异只允许出现在 outbox 的提交方式上（网关走批量队列，业务事务内可走同事务），事件契约、幂等键与消费者必须完全一致。

---

## 6. 请求生命周期

| 步 | 动作 | 数据源 | 失败处理 |
| :--: | :--- | :--- | :--- |
| 1 | 解析 sk- 与 sk-hl-sb- 凭证并计算哈希 | Redis L2、L1、冷回源 | 401 |
| 2 | 校验 key、project、org 状态，IP 白名单，模型白名单 | 缓存 | 401 或 403 |
| 3 | 解析路由与生效定价 | 缓存 | 404，或 503 MODEL_PRICING_MISSING |
| 4 | 估算：jtokkit 精估 prompt；输出上限取 maxTokens 或默认 8192 | 本地 | 无 |
| 5 | 计价：阶梯按估算 prompt 选档；两次 ceiling；乘折扣；乘 1.15 安全系数 | 本地 | 无 |
| 6 | 读快照并按三顺位分摊 | Redis | 快照缺失则加载后重试 |
| 7 | 冻结：限额、三档预占、惰性回收 | prehold_admit.lua | 402 或 429 或 403 |
| 8 | 转发：首字节前换渠道重试，最多 2 次 | 上游 | 全部耗尽则释放且不收费 |
| 9 | 结算：释放预占、按实扣、逐批递减、写 done 标记 | prehold_settle.lua | 重复则幂等返回；已回收则照扣并打标 |
| 10 | 结算事件入 outbox 队列（异步批量）；用量遥测仍直投 Kafka | outbox / Kafka | 结算事件的失败由巡检重投兜底；遥测失败仅记日志 |

---

## 7. 一致性与兜底

### 7.1 稳态

账本折叠值等于 Redis 实时值加未落账消耗，差额为消费者延迟窗口内的消耗，量级为秒。

### 7.2 重建误差

重建（miss 加载）从 checkpoint 读到的值高估真实值，误差上界等于该组织在 lag 窗口内的消耗。滑动 TTL 下键只在空闲时消失，此时新消耗为零，误差趋近于零（见 1.2 节）。

### 7.3 消费者积压：检测与 TTL 联动

积压是唯一需要主动干预的场景：TTL 到期会用滞后 checkpoint 覆盖 Redis，而积压越严重、覆盖造成的偏差越大。因此把 TTL 从「固定过期」改为「受健康度驱动」。

#### 7.3.1 检测信号

三种可选信号，本项目取「记录级 lag 为主、排空时间为判据、事件时间作辅助」：

| 方案 | 数据来源 | 优点 | 缺点 | 取舍 |
| :--- | :--- | :--- | :--- | :---: |
| 记录级 lag | AdminClient：分区 endOffset 减已提交 offset | 权威、无需埋点、覆盖全部分区 | 需要 broker 访问与消费组权限 | **主信号** |
| 客户端 records-lag-max | Kafka 客户端 Micrometer 指标 | 零成本 | 每实例视角、需指标管道 | 备用与告警 |
| 事件时间 lag | 消费者把「最后处理事件时间」写入 Redis | 语义最贴近 checkpoint 滞后 | 系统空闲时无限增长会误报 | **辅助** |

事件时间单独使用会误报：空闲系统没有新事件，距上次处理的时间会一直增长，但此时 lag 为零、checkpoint 与 Redis 完全一致。因此它必须与「是否还有未消费记录」联合判定。

#### 7.3.2 判定规则

@@@
lagRecords   = 求和（各分区 endOffset 减已提交 offset）
drainRate    = 最近窗口的消费速率（条每秒，由监视器两次采样求得）
drainSeconds = lagRecords / max(drainRate, 0.1)

level = DEGRADED  当 lagRecords 大于阈值（默认 5000）
                  或 drainSeconds 大于阈值（默认 60 秒）
      = OK        其余情况
@@@

选择「排空时间」而不只是记录数，是因为真正决定 checkpoint 落后多少的是时间：5000 条在每秒 1000 条的消费速率下只落后 5 秒，无需干预。记录数阈值只用于捕捉速率骤降或消费停摆的瞬间。

#### 7.3.3 信号发布

admin 侧新增定时作业（建议 15 秒），计算级别后写入 Redis：

| 键 | 内容 | TTL |
| :--- | :--- | :--- |
| gw:health:ledger-lag | JSON：level、lagRecords、drainSeconds、updatedAt | 60s |
| gw:health:ledger-lag:armed | 常量标记，表示监视器曾经成功运行过 | 无 |

**缺失语义必须区分两种情况**，否则会在部署初期让所有键永不过期，或让监视器挂掉后失去保护：

| 状态 | 含义 | 网关视为 | 理由 |
| :--- | :--- | :--- | :--- |
| armed 不存在 | 功能未启用，或监视器从未部署 | OK（正常滑动过期） | 没有证据表明有积压 |
| armed 存在、数据键已过期 | 监视器曾运行但现在停止（admin 宕机、滚动发布、作业卡死） | **OK，并打 P1** | **监控组件故障不得转化为客户故障** |

**为什么是 fail-open**：admin 每次滚动发布都会让数据键在 60 秒后过期，fail-closed 等于每次发布制造一轮冷启动 503；监视器长期挂掉时更是持续 503。而「监视器停了」与「有积压」是两件事，前者不该触发后者才该触发的惩罚。代价是「监视器死 + 真有积压」时重建会多给钱——该方向是可承受的一侧，由 P1 告警与对账兜住。

同时把级别暴露为 Micrometer gauge（0 为 OK，1 为 DEGRADED），供 Prometheus 采集与告警。

#### 7.3.4 网关动作

网关在每次触碰钱包与批次键时读取信号（本地缓存 5 秒，避免每请求多一次往返）：

| 信号 | 键存在 | 键缺失（需要重建） |
| :--- | :--- | :--- |
| OK | PEXPIRE 300，正常滑动 | 允许加载 |
| DEGRADED | PERSIST（摘掉 TTL），累加 `gateway_cache_freeze_total` | **503 `LEDGER_BACKLOG`**，`Retry-After` 取 `drainSeconds` |
| armed 不存在 | 同 OK | 同 OK |
| armed 存在但数据键过期 | **同 OK**，并打 P1 | **同 OK** |

判据是**全局的**，不引入任何逐租户的准入状态：命中面只有「DEGRADED 且键缺失」这一格，热键租户永远正常服务——它手里的 Redis 就是权威本身。

**监视器失联按 OK 处理**：admin 停机或滚动发布会让数据键过期，若按 DEGRADED 处理，每次发布都会制造一轮客户故障。监控组件故障不得转化为客户故障，代价是「监视器死 + 真有积压」时重建会多给钱，由 P1 告警与对账兜住。

该设计的关键优点是**不需要全局扫描**：PERSIST 幂等且廉价，只在被触碰的键上生效，因此冻结过程是渐进的、对热点组织立即生效。恢复为 OK 后，下一次触碰自动重新 PEXPIRE 300，键回到滑动过期。

#### 7.3.5 积压期仍然发生的重建

若键在进入 DEGRADED 之前就已到期，或该组织积压期首次访问，重建仍会发生且会高估。处理原则：

1. **允许重建**——可用性优先，且高估方向不会造成「付了钱却不能用」；
2. 累加 gateway_rebuild_while_degraded_total（按组织打标）；
3. 把该组织写入待对账集合，由 2.10 在对账时按账本定向修正。

#### 7.3.6 告警

| 告警 | 条件 | 级别 |
| :--- | :--- | :---: |
| 结算积压 | hyperllm_ledger_lag_level 等于 1 持续 5 分钟 | P1 |
| 监视器失联 | armed 存在且数据键连续过期超过两个周期 | P1 |
| 积压期重建 | gateway_rebuild_while_degraded_total 增长 | P2 |

#### 7.3.7 手动开关

| 配置 | 作用 |
| :--- | :--- |
| hyperllm.cache.sliding-ttl.freeze | 全局强制 DEGRADED 行为（全部键 PERSIST） |
| hyperllm.cache.sliding-ttl.freeze-orgs | 按组织强制 DEGRADED，用于定向排障 |

用于计划内维护、数据修复、对账纠正期间，避免 TTL 在人为操作窗口内触发重建。

### 7.4 事件丢失

引入 outbox 后，投递链路上的故障都不再丢事件：Debezium 或 Kafka 停机时事件留在表里或 topic 里，恢复后追上；Kafka 的 7 天保留期是最后一层缓冲。

**唯一仍有风险的窗口在入队之前**：Redis 已改、事件尚未落进 outbox。触发条件是进程在 Lua 成功与批量刷盘之间崩溃，窗口等于刷盘间隔（默认 20ms），丢失量上界为 QPS 乘刷盘间隔。

方向与后果：

- 消耗路径丢失：Redis 已扣、账本无记录，用户被多扣；
- 发放路径丢失：Redis 已加、账本无记录，用户白得。

两者都由 2.10 的周期性对账发现并按账本定向修正。相比改造前的直投 Kafka（Kafka 不可用即丢整个窗口），这个窗口小两个数量级。


### 7.5 对账职责

对账是偏差的唯一收敛手段，负责：

1. 校验账本折叠值等于数据库快照（发现消费者丢事件）；
2. 校验数据库快照与 Redis 实时值的差额可解释且方向正确：消耗未落账时快照应高于 Redis，发放未落账时应低于 Redis；方向错误即判定为异常（如重复应用或凭空加额）；
3. 校验批次余量之和等于 wallets.bonus_balance_micros；
4. 发现 shortfall 与 settledAfterReclaim 两类差异并解释，不归为「账不平」。

---

## 8. 不变量

| 编号 | 不变量 |
| :--- | :--- |
| **P1** | 键存活期间 Redis 是余额与余量的唯一权威来源；请求路径禁止读批次表判断可用量 |
| **P2** | 全部键族使用滑动 TTL；3.2 节列出的续期点必须全部实现 |
| **P3** | miss 加载按租户维度整体进行；加载完成前不得放行请求 |
| **P4** | 任何 Lua 禁止在缺失键上写入，缺失一律返回哨兵值 |
| **P5** | 计数字段只接受增量；绝对赋值只允许用于状态与属性字段 |
| **P6** | delta 必须在写路径上当场计算；禁止以展示值、前置查询值或数据库值作为基准 |
| **P7** | 每次 Redis 扣减都必须产生账本事件；现金、赠金、资源包任一非零即发 |
| **P8** | 分摊明细（含逐批次腿与其 id）必须随事件下发并最终落库 |
| **P9** | 同一笔变更只允许一个写入口；Redis 变更与 outbox 记录必须使用同一个 eventId，缺一即缺陷 |
| **P10** | console 与 admin 只能经统一写入口写 Redis，网关不得另起一套写逻辑；三条路径的差异只允许在 outbox 提交方式上 |
| **P11** | 配置类表变更必须同时刷新 Redis L2 并经 CDC 驱逐 L1，缺一即缺陷 |
| **P12** | 数据库中的余额与批次余量是账本的折叠结果，不作为请求路径的读取源 |
| **P13** | 键缺失时不写 delta；键存在时加载不覆盖。二者共同保证重建与增量在同一个键上永不交错 |
| **P14** | 事件只携带实扣量，落库一律加法，禁止钳位；出现负余额即判错账 |
| **P15** | fold(wallet_ledger) 恒等于 wallets 快照，二者在同一事务内写入 |
| **P16** | 消耗走「先 Redis 后 outbox」，发放走「先 outbox 后 Redis」；同一 eventId 贯穿两处 |
| **P17** | 重建闸门是全局判据，不得引入任何逐租户的准入状态 |
| **P18** | 钱一旦动了，响应里不再出现错误码；503 只出现在准入之前 |
| **P19** | 批次过期不写 Redis，视为权威值随时间衰减，P9 对此显式豁免 |

---

## 9. 与 2.1 至 2.3 已交付实现的偏差清单

按本文基线，下列已交付实现为**偏差**，必须收敛。编号供 TODO 引用。

| 编号 | 偏差 | 涉及位置 | 收敛阶段 |
| :--- | :--- | :--- | :---: |
| **D-1** | 结算读 PostgreSQL 判定可用批次与抵扣顺序（违反 P1） | GatewayConfigCacheService.resolveEntitlements | 2.4 |
| **D-2** | 逐批次镜像从未建立，仅有钱包与包可用量聚合 | 网关缓存层 | 2.4 |
| **D-3** | 全部 Lua 未设置 TTL，且心跳脚本 KEYS 中无 gw:wallet（违反 P2） | 5 个 prehold 脚本与 deduct_wallet_split | 2.4 |
| **D-4** | 仅资源包抵扣的请求不发结算事件（违反 P7） | GatewayTelemetryService | 2.4 |
| **D-5** | allocation_detail_json 被丢弃，明细与 shortfall 不可观测（违反 P8） | BillingSettlementConsumer | 2.5 |
| **D-6** | 数据库批次余量无生产代码扣减 | ResourcePackRepository 与 BonusGrantRepository | 2.5 |
| **D-7** | 结算落库的事务注解自调用失效，实际无事务 | BillingSettlementConsumer | 2.5 |
| ~~**D-8**~~ | ~~结算快照未扣他人 held，可能吃掉他人冻结额度~~ | 结算快照构造 | **作废**：改为逐批次冻结后，结算不再重新分摊，不存在「吃掉他人冻结」的形状 |
| ~~**D-9**~~ | ~~gw:pack:avail 使用固定 TTL，超卖差额被钳位吞掉~~ | ensurePackAvailability | **作废**：聚合字段整体废除，改为逐批次精确冻结 |
| **D-10** | 心跳续期把 orgId 当作 projectId 传入，并发计数未被续期 | renewHoldIfDue | 2.4 |
| **D-11** | console 单租户钱包直接读数据库；admin 无单租户钱包读接口（违反 B3 与 B6） | 控制面 | 2.4 |
| **D-12** | admin 租户列表 DTO 不含余额字段（B6 的列表侧未实现） | AdminOrgDto | 2.4 |
| **D-13** | 无任何定时作业：分区预建、僵尸预占周期回收、过期 sweeper 全缺 | 全仓 | 2.5 |

---

## 10. 阶段落地映射

| 阶段 | 交付 | 关闭的偏差 |
| :--- | :--- | :--- |
| **2.4** Redis 权威层 | 逐批次镜像与 miss 加载协议；滑动 TTL 全续期点；空键纪律；结算改读镜像；事件门禁；控制面读 Redis；积压监视器与 TTL 联动；租户列表内联余额 | D-1、D-2、D-3、D-4、D-8、D-9、D-10、D-11、D-12 |
| **2.5** 结算落库与账本事务 | 按明细扣批次；明细落库；事务边界；维护作业（分区、回收、sweeper） | D-5、D-6、D-7、D-13 |
| **2.6** Outbox 投递与生命周期 | 三条路径统一写 outbox；批量顺序插入与背压；Debezium 投递；水位删除 | P9、P10 |
| 2.7 至 2.10 | 支付、限流预算、OLAP、告警对账 | 无 |

---

## 11. 已确认决议

| # | 议题 | 决议 |
| :--- | :--- | :--- |
| **Q1** | gw:ent:ver 与 gw:ent:stale 是否废除 | **废除**。滑动 TTL 承担回收与重建职责，miss 加载在单个 Lua 内原子完成，无需代际号（见 3.4 节）。04-02 第 4 节的版本戳协议不再实现。 |
| **Q2** | 消费者积压时的 TTL 联动 | **实现**。以 AdminClient 的记录级 lag 为主信号、排空时间为主判据，admin 发布健康信号，网关在 DEGRADED 期对触碰到的键 PERSIST（见 7.3 节）。 |
| **Q3** | admin 租户列表是否内联余额 | **内联**。以 organizations LEFT JOIN wallets 一次取出，避免 N 加 1；列表不展示冻结与可用，并标注「账本已确认」（见 4.2 节）。 |
| **Q4** | 批次是否按模型分片加载 | **暂不**。按租户整体加载（基线 B5）。待单组织批次数超过阈值时再评估分片。 |
| **Q6** | 写路径是否继续「方向唯一」 | **分叉**。消耗「先 Redis 后 outbox」，发放「先 outbox 后 Redis」，理由与后果见 04-04 第 1 节；原 B4 据此修订 |
| **Q7** | 积压时的降级粒度 | **全局判据**。命中面只有「DEGRADED 且键缺失」，热键租户照常服务；监视器失联按 OK 处理并打 P1（见 7.3.4） |
| **Q8** | 丢事件如何收敛 | **对账工单四级阶梯**，最后一级无条件终止；工单不参与请求路径（见 04-04 第 5.3 节） |
| **Q5** | outbox 的写入方式与生命周期 | **三条路统一写 PostgreSQL outbox**。网关每请求写一行，按追加表设计：UUIDv7 主键、零二级索引、单写者批量提交、队列满降级同步插入、仍失败返回 503。不设保留期，按消费者回传的投递水位删除，24 小时兜底并告警（见 5.4、5.5 节）。 |

### 11.1 由决议引出的新增配置

| 配置 | 默认 | 说明 |
| :--- | :--- | :--- |
| hyperllm.billing.lag-monitor.interval-seconds | 15 | 监视器采样周期 |
| hyperllm.billing.lag-monitor.signal-ttl-seconds | 60 | 健康信号在 Redis 的存活时间 |
| hyperllm.billing.lag-monitor.records-threshold | 5000 | 记录级 lag 的降级阈值 |
| hyperllm.billing.lag-monitor.drain-seconds-threshold | 60 | 排空时间降级阈值 |
| hyperllm.cache.sliding-ttl.freeze | false | 全局强制 DEGRADED |
| hyperllm.cache.sliding-ttl.freeze-orgs | 空 | 按组织强制 DEGRADED |
| hyperllm.cache.sliding-ttl.ttl-seconds | 300 | 滑动 TTL 时长 |
| hyperllm.outbox.flush-interval-ms | 20 | 批量刷盘间隔，即崩溃丢失窗口 |
| hyperllm.outbox.batch-size | 200 | 触发刷盘的条数 |
| hyperllm.outbox.max-batch-rows | 500 | 单次 multi-row INSERT 的行数上限 |
| hyperllm.outbox.queue-capacity | 100000 | 有界队列容量 |
| hyperllm.outbox.admission-pause-ratio | 0.5 | 队列水位超过该值即停准入 |
| hyperllm.outbox.retry-deadline-seconds | 600 | 单事件重试截止，超时后放弃并计入孤儿指标 |
| hyperllm.outbox.purge-interval-seconds | 30 | 水位删除作业周期 |
| hyperllm.outbox.purge-batch-rows | 5000 | 单次删除行数上限 |
| hyperllm.outbox.watermark-stall-hours | 24 | 水位停滞多久后按时间兜底删除并告警 |


## 12. 术语

| 术语 | 定义 |
| :--- | :--- |
| **批次（batch）** | resource_packs 或 bonus_grants 中的一行，自带余量、到期时间与适用模型 |
| **分摊项（leg）** | 一次请求的费用按三顺位切分后的每一条独立扣减，类型为 PACK（带 units）、BONUS 或 CASH；满足「求和加 shortfall 等于 chargedMicros」 |
| **三顺位** | 定向资源包、赠金批次（到期最早优先）、现金，顺序不可配置 |
| **预占（hold）** | Redis 中的临时冻结额度，带 hold_id 与租约，未结算则由回收作业释放 |
| **结算（settle）** | 按实测 usage 把预占换成实扣，并发射结算事件 |
| **投影（projection）** | 把已提交的账本变更以增量形式写入 Redis |
| **checkpoint** | 数据库中的余额与批次余量，等于账本的折叠结果 |

---

## 13. 文档状态

| 文档 | 与本方案的关系 |
| :--- | :--- |
| 04-00-overview.md | 索引与跨任务不变量；其 T3 已被本文第 2 节取代 |
| 04-01-v4-schema.md | 表结构仍有效；`V4_2`（删 outbox 时间索引、`fillfactor`、账本新增两列、`wallets.last_applied_event_id`、`outbox_watermark`）见 04-04 第 8 节 |
| 04-02-entitlements.md | 第 3 与 5 至 7 节的分摊算法与明细契约仍有效；第 4 节的批次镜像协议（含 gw:ent:ver、gw:ent:stale，已按决议 Q1 废除）、决策 D12（`general_bonus_micros` 聚合字段已废除）与第 13 节的落地记录已被本文取代 |
| 04-04-settlement-pipeline.md | 2.4、2.5、2.6 的机制、时序、配置与验收；本文的 B4 与 §5.4 由它修订 |
| 04-03-prehold-settle.md | 第 3 至 7 节的预占、结算、回收语义仍有效；**其第 3 节的键布局（`gw:pack:avail` / `gw:pack:held` / `gw:org:holds` / 钱包聚合字段）已被 04-04 第 2.1 节取代**；第 4.2 节的结算脚本人批职责与第 16 节的口径已被本文取代 |
