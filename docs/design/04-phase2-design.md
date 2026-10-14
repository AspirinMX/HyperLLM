# HyperLLM 阶段二（Phase 2）最终设计方案

> 本文是**阶段二的唯一权威设计**，2.4 及之后的全部子任务以本文为准。
> 各子任务设计稿（04-01、04-02、04-03）记录「当时如何设计、实际如何落地」，保留作为历史与偏差证据；与本文冲突时以本文为准。
> 本文**取代** [04-00-overview.md](./04-00-overview.md) 第 2 节的 **T3**。

---

## 1. 设计基线

六条基线决定读路径、写路径与一致性边界的全部上限。任何子任务不得绕过。

| 编号 | 基线 |
| :--- | :--- |
| **B1** | **键存活期间，Redis 是余额与余量的唯一权威来源**，不区分现金、赠金、资源包 |
| **B2** | Redis 一律使用**滑动 TTL**；键的生命周期由访问驱动，活跃组织的键不得中断 |
| **B3** | 网关与 console / admin 一律 **hit-Redis / miss-DB** |
| **B4** | 双向收敛：改 Redis 最终落到账本与数据库余额；改账本最终投影回 Redis |
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
| **Redis** | 余额、赠金批次余量、资源包余量、预占、限流状态、配置热态 | **实时权威**（键存活期间） | 网关 Lua；控制面仅经投影写入 | 网关、console、admin |
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
| gw:wallet:{orgId} | HASH | cash_micros、general_bonus_micros、held_cash_micros、held_bonus_micros、status | 滑动 300s | MISS_WALLET，触发加载 |
| gw:pack:{orgId}:{modelCode} | ZSET | member = packId，score = expires_at epoch ms | 滑动 300s | 触发加载 |
| gw:pack:item:{packId} | HASH | remaining_units、total_units、unit_type、status、expires_at | 滑动 300s | 触发加载 |
| gw:bonus:{orgId} | ZSET | member = grantId，score = expires_at（无限期取 9999-12-31） | 滑动 300s | 触发加载 |
| gw:bonus:item:{grantId} | HASH | remaining_micros、initial_micros、models、status、expires_at | 滑动 300s | 触发加载 |
| gw:pack:avail:{orgId}:{modelCode} | STRING | 该模型全部可用包单位之和（派生，可从 item 重建） | 与 pack 键同步 | 重建 |
| gw:pack:held:{orgId}:{modelCode} | STRING | 已被在飞请求冻结的单位之和（派生） | 与 pack 键同步 | 重建 |
| gw:hold:{holdId} | HASH | 三腿金额与单位、org_id、project_id、api_key_id、model_code、created_at | PEXPIRE 为 2 倍租约 | 无 |
| gw:holds:exp | ZSET | member = holdId，score = 租约到期 epoch ms | 无 TTL | 无 |
| gw:org:holds:{orgId} | SET | 本组织在飞 holdId | 无 TTL | 无 |
| gw:hold:done:{holdId} | STRING | 结算摘要 JSON | 12 倍租约 | 无 |
| gw:wallet:idem:{eventId} | STRING | 投影幂等标记 | 7 天 | 无 |
| gw:cfg:key / gw:cfg:route / gw:cfg:override | STRING | 配置 JSON | 1800s 加 CDC 驱逐 | 回源 |
| gw:rl:rpm / gw:rl:conc | STRING | 限流与并发计数 | 按分钟或租约 | 无 |
| gw:sandbox:{sha256} | STRING | Playground 临时凭证 | 300s | 无 |

### 3.2 续期点（滑动 TTL 的唯一实现手段）

**任何触碰钱包与批次键族的位置都必须续期**，缺一处就会出现「键在请求中途消失」。

| 续期点 | 涉及的键 |
| :--- | :--- |
| 准入 prehold_admit.lua | gw:wallet、gw:pack 系列、gw:pack:avail、gw:pack:held |
| 心跳 prehold_renew.lua | gw:wallet、gw:pack 系列、gw:hold、并发计数 |
| 结算 prehold_settle.lua | 同上 |
| 释放 prehold_release.lua | 同上 |
| 读路径（网关冷回源、console / admin 查询、miss 加载完成时） | 被读到的键 |

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
3. 单次原子写入：钱包 HASH、重建 pack 与 bonus 两个 ZSET 及 item HASH、重算 avail 与 held。
4. 统一 PEXPIRE 300s。

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

### 5.1 消耗方向（Redis 先写）

1. **准入**：读钱包、pack:avail、pack:held，全部来自 Redis。
2. **分摊**：PricingResolver 加 EntitlementAllocator，纯 Java 三顺位。
3. **冻结**：prehold_admit.lua，限额加三档冻结加惰性回收加续期。
4. **转发**：首字节前失败换渠道重试；流式分片心跳续期。
5. **结算**：prehold_settle.lua，释放预占、逐批递减 item、递减 avail、扣 cash 与 bonus（钳到非负）、写 done 标记、续期。
6. **事件**：cashDeltaMicros、bonusDeltaMicros、packDeltaUnits、allocationDetailJson（**逐批次腿，id 必填**）。
7. **落库**：2.5 按明细在 PostgreSQL 事务内扣批次、写账本、更新 wallets。

### 5.2 发放方向（数据库先写）

1. 控制面事务：写账本（幂等键）、更新 wallets 与批次、写 outbox_events。
2. 提交后：当前为「直推 Redis 增量」（Phase 1 路径）。
3. 2.6 切换：Debezium 到 cdc.wallet 等 topic，admin 幂等消费（gw:wallet:idem）后增量投影。
4. 切换完成时**必须移除**第 2 步的直推，否则同一笔会应用两次。

### 5.3 计数字段的写语义

> **计数字段只接受增量语义，禁止绝对赋值。**

危险不在「谁在写」，而在「以什么为基准算 delta」：

| 写法 | 是否安全 | 说明 |
| :--- | :--- | :--- |
| HINCRBY cash 加 delta | 安全 | 与并发扣减可交换，顺序无关 |
| HSET cash 绝对新值 | **禁止** | 抹掉区间内全部并发扣减 |
| HSET status 新状态 | 安全 | 状态字段没有并发增量 |

**delta 的计算规则**：

- 增量操作（充值、赠予、补偿）：delta 等于请求金额，与任何当前值无关，误差恒为 0。
- 绝对目标操作（「把余额补到 X」）：delta 等于 X 减实时值，**实时值必须在写路径上当场从 Redis 读取**；禁止使用界面展示值、前置查询结果或任何数据库值作为基准。误差等于未落账消耗量，所以必须读实时值。

### 5.4 投影路径唯一性

同一笔变更，向 Redis 的投影路径**有且仅有一条**（提交后直推，或 Outbox 消费，二选一）。两条并存的结果是余额翻倍，且无法通过重建修正。

### 5.5 控制面写 Redis 的唯一合法通道

console 与 admin 不得直接修改 Redis 计数字段。它们只能：

1. 在数据库事务内落账本；
2. 经投影（直推或 Outbox）向 Redis 推**增量**。

读操作可任意进行，但读不改变状态。

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
| 10 | 发射 llm.billing.settlement 与 llm.request.events | Kafka | 失败仅记日志，不阻塞响应 |

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

| 状态 | 含义 | 网关视为 |
| :--- | :--- | :--- |
| armed 不存在 | 功能未启用，或监视器从未部署 | OK（正常滑动过期） |
| armed 存在、数据键已过期 | 监视器曾运行但现在停止（admin 宕机、被隔离、积压作业卡死） | **DEGRADED** |

同时把级别暴露为 Micrometer gauge（0 为 OK，1 为 DEGRADED），供 Prometheus 采集与告警。

#### 7.3.4 网关动作

网关在每次触碰钱包与批次键时读取信号（本地缓存 5 秒，避免每请求多一次往返）：

| 信号 | 动作 |
| :--- | :--- |
| OK | PEXPIRE 300，正常滑动 |
| DEGRADED | PERSIST（摘掉 TTL），并累加 gateway_cache_freeze_total |
| 数据键缺失但 armed 存在 | 同 DEGRADED |
| armed 不存在 | 同 OK |

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

网关扣减 Redis 成功、但进程在事件发出前死亡：Redis 已扣、账本无记录。这是**唯一无自动补偿的窗口**，由 2.10 的周期性对账发现并按账本定向修正。

### 7.5 对账职责

对账是偏差的唯一收敛手段，负责：

1. 校验账本折叠值等于数据库快照（发现消费者丢事件）；
2. 校验数据库快照与 Redis 实时值的差额可解释（等于未落账消耗）；
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
| **P9** | 同一笔变更只允许一条 Redis 投影路径 |
| **P10** | 控制面只通过投影写 Redis，不得直接修改计数字段 |
| **P11** | 配置类表变更必须同时刷新 Redis L2 并经 CDC 驱逐 L1，缺一即缺陷 |
| **P12** | 数据库中的余额与批次余量是账本的折叠结果，不作为请求路径的读取源 |

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
| **D-8** | 结算快照未扣他人 held，可能吃掉他人冻结额度 | 结算快照构造 | 2.4 |
| **D-9** | gw:pack:avail 使用固定 TTL，超卖差额被钳位吞掉（违反 P2） | ensurePackAvailability | 2.4 |
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
| **2.6** Outbox 与投影 | 正向变更幂等投影；移除直推路径 | P9 的完整实现 |
| 2.7 至 2.10 | 支付、限流预算、OLAP、告警对账 | 无 |

---

## 11. 已确认决议

| # | 议题 | 决议 |
| :--- | :--- | :--- |
| **Q1** | gw:ent:ver 与 gw:ent:stale 是否废除 | **废除**。滑动 TTL 承担回收与重建职责，miss 加载在单个 Lua 内原子完成，无需代际号（见 3.4 节）。04-02 第 4 节的版本戳协议不再实现。 |
| **Q2** | 消费者积压时的 TTL 联动 | **实现**。以 AdminClient 的记录级 lag 为主信号、排空时间为主判据，admin 发布健康信号，网关在 DEGRADED 期对触碰到的键 PERSIST（见 7.3 节）。 |
| **Q3** | admin 租户列表是否内联余额 | **内联**。以 organizations LEFT JOIN wallets 一次取出，避免 N 加 1；列表不展示冻结与可用，并标注「账本已确认」（见 4.2 节）。 |
| **Q4** | 批次是否按模型分片加载 | **暂不**。按租户整体加载（基线 B5）。待单组织批次数超过阈值时再评估分片。 |

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
| 04-01-v4-schema.md | 仍有效（表结构未变） |
| 04-02-entitlements.md | 第 3 与 5 至 7 节的分摊算法与明细契约仍有效；第 4 节的批次镜像协议（含 gw:ent:ver、gw:ent:stale，已按决议 Q1 废除）与第 13 节的落地记录已被本文取代 |
| 04-03-prehold-settle.md | 第 3 至 7 节的预占、结算、回收语义仍有效；第 4.2 节的结算脚本人批职责与第 16 节的口径已被本文取代 |
