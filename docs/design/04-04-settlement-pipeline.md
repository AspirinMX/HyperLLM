# 阶段二 2.4 至 2.6：结算流水线详细设计

> 定位：[`04-phase2-design.md`](./04-phase2-design.md) 持有六条基线与总体决议，本文持有 2.4、2.5、2.6 三个阶段的机制、时序、配置与验收手段。
> 本文**修订** 04 的基线 **B4**（写方向唯一）与 **§5.4**（失败时的响应语义）：写方向按业务方向分叉，见第 1 节。
> 阅读顺序：04 的 B1 至 B6、本文第 1 节，然后按阶段看。

---

## 1. 总原则：写方向按业务方向分叉

### 1.1 不对称原则

> **扣钱先动钱，给钱先记账。**

| 维度 | 消耗（扣钱） | 发放（充钱） |
| :--- | :--- | :--- |
| 谁先落笔 | Redis（Lua 原子扣减） | PostgreSQL（outbox 行） |
| 事件的语义 | 「刚刚发生了一次扣减」 | 「这笔发放已被授权」 |
| 允许丢吗 | 允许 | **不允许** |
| 丢失的后果 | 平台少收钱 | 用户付钱没到账 |
| 失效方向 | 单一，且可承受 | 不存在 |

### 1.2 为什么方向必须不对称

两条路的风险不同，用一种写序服务两种风险必然有一边吃亏：

- 消耗是**我们主动从用户身上扣钱**。先扣再记，丢的是我们的收入；反过来先记再扣，一旦扣失败就会出现「账本扣了、实际没扣」，那是要退钱的客诉。
- 发放是**用户付了钱换权益**。先记后加，丢的是用户的权益；反过来先加再记，一旦事件丢失，下一次重建会把加到 Redis 的钱抹回去，同一个用户会先看到到账、再看到消失。

因此：**消耗走「效果优先」，发放走「记录优先」。** 这不是两套实现，是同一写入口的两种提交方式（见 1.4）。

### 1.3 权威层级

| 层 | 承担 | 性质 |
| :--- | :--- | :--- |
| Redis | 余额、逐批次余量、预占、限流状态 | 键存活期间的**实时权威** |
| `wallet_ledger` | 逐笔 delta，append-only、幂等键唯一 | **唯一重建依据** |
| `wallets` / `resource_packs` / `bonus_grants` | 账本的折叠结果 | 派生态，允许滞后 |

推论（与 04 第 2 节一致，此处只强调分叉带来的两条）：

- **R1** 请求路径禁止读批次表判断可用量与抵扣顺序。
- **R2** 「行存在即已授权」：发放方向的消费者不需要判断某笔变更「是否已经生效」，它只需要把授权落地。

### 1.4 同一个写入口，两种提交方式

消耗和发放共用事件契约及 `cdc.billing`。两者都在调用 Lua 前生成 `eventId`，重试复用原 ID。

- 消耗由 Lua 原子应用实扣 delta 并留存事件，随后异步写入 outbox。
- 人工充值和注册赠金在原业务事务中追加 outbox 授权。消费者持钱包行锁写账本和批次，并在提交前用 Lua 应用 Redis delta。Redis 钱包或所需索引缺失时，下一次冷加载从已折叠的数据补齐。
- Redis 保留钱包版本下界，冷加载脚本拒绝读于发放之前的旧快照。同一钱包的 Redis 发放标记随镜像保留，覆盖 Redis 投影已完成而 PostgreSQL 提交失败的重试窗口。
- 人工充值接口返回 HTTP 202 和 `eventId`。请求使用 common 的 `@Idempotent`，数据库审计在同一事务保留授权身份，支持 Redis 失效和 outbox 清理后的重试。
- 发放授权持钱包行锁，余额上限同时计入尚未入账的授权。镜像余额最多为 Redis Lua 可精确计算的 `2^53 − 1` micro-USD，累计发放额需在 `long` 范围内。

两条不可协商的规则：

- 幂等标记必须与 delta 在**同一个 Lua** 内写入，应用层补写会让重试翻倍。
- `outbox_events.id`、`wallet_ledger.id`、Redis 幂等标记必须指向**同一个 eventId**。

---

## 2. 2.4 Redis 权威层

### 2.1 键规范（终态）

| 键 | 类型 | 载荷 | TTL |
| :--- | :--- | :--- | :--- |
| `gw:wallet:{orgId}` | HASH | `cash_micros`、`held_cash_micros`、`status` | 滑动 300s |
| `gw:pack:{orgId}:{modelCode}` | ZSET | member = packId，score = 到期 epoch ms | 滑动 300s |
| `gw:pack:item:{packId}` | HASH | `remaining_units`、`held_units`、`total_units`、`unit_type`、`status`、`expires_at` | 滑动 300s |
| `gw:bonus:{orgId}` | ZSET | member = grantId，score = 到期 epoch ms（无限期取 4102444800000） | 滑动 300s |
| `gw:bonus:item:{grantId}` | HASH | `remaining_micros`、`held_micros`、`initial_micros`、`models`、`status`、`expires_at` | 滑动 300s |
| `gw:hold:{holdId}` | HASH | 三条腿金额、`legs` JSON、`served`、org / project / key / model / created_at | PEXPIRE 2 × 租约 |
| `gw:holds:exp` | ZSET | member = holdId，score = 租约到期 epoch ms | 无 |
| `gw:hold:done:{holdId}` | STRING | 结算摘要 JSON | 12 × 租约 |
| `gw:ledger:pos:{orgId}` | HASH | `produced`、`since`、重建所需的 `credit_version`、`debit_version` | **无 TTL** |
| `gw:settle:evt:{eventId}` | STRING | outbox 实扣事件副本 | 1 小时 |
| `gw:settle:proof:{eventId}` | STRING | 实扣影响的 Redis 键与其镜像标识 | 1 小时 |
| `gw:settle:pending` | ZSET | member = eventId，score 用于安排下次检查 | 无 TTL |
| `gw:settle:owners` | HASH | eventId 对应组织及结算时刻，工单关闭时清理 | 无 TTL |
| `gw:settle:waiting:{orgId}` | SET | 未确认实扣事件 ID，清空后 Redis 自动删键 | 无 TTL |
| 钱包中的 `credit:{eventId}` | HASH 字段 | 该钱包镜像已应用发放 | 随钱包 |
| `gw:health:ledger-lag` | STRING | `{level,lagRecords,drainSeconds,updatedAt}` | 60s |
| `gw:health:ledger-lag:armed` | STRING | 常量标记，表示监视器成功运行过 | 无 TTL |
| `gw:cfg:key` / `gw:cfg:route` / `gw:cfg:override` | STRING | 配置 JSON | 1800s + CDC 驱逐 |
| `gw:rl:rpm:{scope}:{id}:{minute}` | STRING | 分钟计数 | 120s |
| `gw:rl:conc:{scope}:{id}` | STRING | 并发租约计数 | 租约 |
| `gw:sandbox:{sha256}` | STRING | Playground 临时凭证 | 300s |

**相对 04 §3.1 的三处变化**：

1. 删除 `gw:pack:avail`、`gw:pack:held` 与钱包的 `general_bonus_micros`（见 2.2）。
2. 删除 `gw:org:holds:{orgId}`：它只服务惰性回收，而 `gw:holds:exp` 已按到期时间索引全部在飞预占，回收改为全局扫描。**该键是按组织永久存在的，留着会把内存拉回正比于租户总数。**
3. 新增 `gw:ledger:pos`、`gw:settle:evt`、`gw:settle:pending`（5.2 与 5.3 使用）。

### 2.2 逐批次冻结

**冻结必须精确到批次，不能只冻结聚合量。**

聚合冻结下，两个并发请求可以各自分摊同一批的同一份余量；结算逐批递减时必然击穿，只能钳到 0 或记超卖指标，两者都是资损方向。

- 准入时 `EntitlementAllocator` 产出**逐条腿**（PACK 带 `packId` 与 `units`，BONUS 带 `grantId`，CASH 无 id）。
- `prehold_admit.lua` 接收腿清单：每条 PACK / BONUS 腿在对应 item 上加 `held_units` / `held_micros`，CASH 腿加钱包的 `held_cash_micros`；`legs` 原样存入 hold。
- 结算**不再重新分摊**：从 hold 读出 `legs`，以其为快照对实扣金额重跑 `EntitlementAllocator`，再按结果释放与扣减。不读 PostgreSQL，也不需要第二次读 Redis。

**由此废除三个聚合字段**，并连带解决三个问题：

| 原字段 | 废除后 |
| :--- | :--- |
| `general_bonus_micros` | 赠金总额由快照脚本按 `score > now` 现算；**「TTL 被冻结时过期赠金仍可花」的漏洞随之关闭** |
| `gw:pack:avail` | 可用量由 item 余量求和；不再有「固定 TTL 与滑动 TTL 并存」的问题（04 的 D-9 作废） |
| `gw:pack:held` | 冻结量按 item 精确计；不再有超卖（04 的 D-8 作废） |

```
KEYS 约定（admit / settle / release / reclaim 一致）：
  [1] gw:wallet:{org}
  [2] gw:hold:{holdId}
  [3] gw:holds:exp
  [4] gw:hold:done:{holdId}
  [5..] 各腿对应的 item 键，顺序与 ARGV 中的 legs 数组一致
限流相关键固定在腿键之后传入。
```

### 2.3 快照读原语

一次分摊需要「钱包 + 该模型的包 ZSET + 每个包 item + 赠金 ZSET + 每个赠金 item」。新增只读脚本 `prehold_snapshot.lua`，一次往返返回：

```
{ cash, heldCash, status,
  packCount, (packId, remaining, held, unitType, expiresAt) × packCount,
  bonusCount, (grantId, remaining, held, expiresAt) × bonusCount }
```

网关准入、结算重分摊、console 单租户查询、admin 单租户查询**共用这一个脚本**。

> **该脚本先 `ZRANGE` 再按成员拼接 item 键，因此与 Redis Cluster 不兼容。** 计费实例必须按租户分 cell（同 cell 内单实例或主从），不得拆成 Cluster 分片。这是硬约束，不是实现偏好。

### 2.4 加载协议：只补缺失 + 键级互斥

加载三件事必须同时成立：

1. **只补缺失**：脚本对每个目标键先 `EXISTS`，已存在则跳过，绝不整租户覆盖。全量覆盖会抹掉加载窗口内并发结算已扣掉的 delta。
2. **键级互斥**：**键缺失时，任何 delta 写入一律返回哨兵、不做写入**；键存在时，加载不再覆盖它。两条合起来，同一个键在任一时刻只可能被一个写者碰——缺失时只有加载能创建，存在时只有 delta 能修改。
3. **失败不降级放行**：读不到批次就按「无权益」处理，只允许现金与赠金快照参与分摊；绝不因为读库失败而跳过计费。

加载写入必须在单个 Lua 内完成（DEL 旧结构 → 写新结构 → 统一 `PEXPIRE`），不允许 Java 侧逐条写，否则会出现「钱包已写、批次未写」的中间态。

兼容期（Phase 1 遗留数据，快照大于批次之和）仍保留「未镜像赠金桶」：合成 id、`virtual = true`、排在最末。2.6 完成后差额恒为 0，该分支保留但不再触发。

**残余代价**：租户处于冷态（键缺失）时收到发放，应用会拿到哨兵，只能「折叠 PostgreSQL、跳过 Redis」。该笔权益最长滞后一个 TTL（300 秒）才可见。这是「缺失不写」的必然代价，明确接受。

### 2.5 续期：滑动 TTL + 实例本地节流

快照读取维护滑动 TTL。预占与心跳单独保护有冻结的键，给回收作业预留时间。

| 续期点 | 涉及键 |
| :--- | :--- |
| 快照读取 | 钱包、赠金索引及批次、当前模型的资源包索引及批次 |
| 准入与心跳 | 钱包与冻结腿延长到至少两倍预占期限，心跳也续期并发计数 |
| 加载完成时 | 新建键 |

网关实例按租户和模型维护独立的续期窗口，默认 60 秒。命中节流时脚本只读余额，账本降级时仍对触碰的键执行 `PERSIST`。正常续期会保留预占保护留下的较长 TTL。

理由：每请求 `PEXPIRE` 五个键在 AOF 上是纯写放大；而只要「有流量就永不过期」，续期频率对正确性没有影响。

### 2.6 控制面读路径

| 入口 | 数据源 | 口径 |
| :--- | :--- | :--- |
| 网关准入与结算 | Redis（快照脚本） | 实时权威 |
| console 单租户钱包与批次 | Redis（快照脚本，miss 则加载） | 实时权威 |
| admin 单租户详情、改余额前置 | Redis（快照脚本，miss 则加载） | 实时权威 |
| admin 租户列表、报表、导出 | PostgreSQL | 账本已确认，允许落后 |

- 实时值必须同时给出**余额、冻结、可用**三个数，只给余额会让运营误以为可以花。
- 列表以 `organizations LEFT JOIN wallets` 一次取出，标注「账本已确认」，并提示「详情页为实时值」；列表不展示冻结与可用，那两个数只有 Redis 有。
- **改余额之前必须先加载 Redis**；delta 的目标值必须在写路径上当场从 Redis 读，禁止用界面展示值或数据库值作为基准。

### 2.7 事件门禁

`cashDeltaMicros != 0 || bonusDeltaMicros != 0 || packDeltaUnits != 0` 任一成立即发事件。

修复 04 的 D-4：纯资源包抵扣的请求此前不发结算事件，导致「扣了单位但没有账本行」。

---

## 3. 2.5 结算落库

### 3.1 消费者事务

一个事件一个事务，顺序固定：

```
1. INSERT wallet_ledger (id = eventId, 实扣三桶, shortfall, allocation_detail_json, hold_id,
                        settled_after_reclaim, created_at = 事件时间,
                        idempotency_key = 消耗取 req:{requestId}，发放取 outbox:{eventId})
   ON CONFLICT (id, created_at) DO NOTHING
2. 影响行数 == 0  ⇒ 已处理过，直接 ack
3. UPDATE wallets SET cash_balance_micros = cash_balance_micros + ?,
                      bonus_balance_micros = bonus_balance_micros + ?,
                      累计列按事件类型 + ?（消耗加 total_consumed_*，充值加 total_recharged_micros，
                                       赠金发放加 total_bonus_granted_micros）,
                      last_applied_event_id = ?, version = version + 1
4. 逐腿处理批次：
   消耗事件 ⇒ UPDATE ... SET remaining = remaining - ?（余量归零且 ACTIVE ⇒ EXHAUSTED）
   发放事件 ⇒ 新批次 INSERT（UUIDv7、到期时间、总单位、适用模型来自 payload）+ 写初始余量
5. COMMIT
6. COMMIT 之后：ZREM gw:settle:pending {eventId}、清理 gw:settle:evt
```

要点：

- **第 1 步是幂等门禁**，第 3、4 步只在门禁通过后执行，绝不允许先改余额再插账本。
- 第 4 步必须按**逐腿明细**处理，不能只按三桶 delta 估算；这是 04 的 D-6。消耗与发放共用同一套腿结构，消费者按事件类型选择「递减」或「新建」。
- `wallet_ledger.id` 使用 outbox 主键，因此主键即幂等键；`idempotency_key` 保留 `req:{requestId}` 供人工检索。
- 事务注解必须落在独立 Bean 上，避免自调用绕过代理（04 的 D-7、G7）。

### 3.2 不钳位与折叠恒等

> **事件只携带实扣量（Redis 钳位之后的金额），落库一律做加法，禁止 `GREATEST(0, ...)`。**

原因：钳位意味着「账本 delta 之和 ≠ 快照变化」，这是错账；而后端不允许出现负余额的 `CHECK` 约束会把问题变成无限重试。

由「只加实扣量 + 幂等」可以推出两条恒等式，对账以此为准：

- **任意时刻 `fold(wallet_ledger) == wallets.*`**（两者在同一事务内用同一个 delta 写入）。
- **同一 `eventId` 至多应用一次**（主键唯一）。

若某次落库触发了负余额，判定为 **order 或实扣量出错**，必须告警并冻结该租户，不得静默钳位。

### 3.3 `folded` 落在 PostgreSQL

消费者在**同一事务**内推进 `wallets.last_applied_event_id`，不写 Redis。

理由：若 `folded` 存在 Redis，就会出现「PG 已提交、Redis 标记没写成功」的第三种卡死成因；放进 PG 后，该成因结构上不存在，而加载路径本来就要读 `wallets`，比较是零额外查询。

### 3.4 定时作业

| 作业 | 周期 | 职责 |
| :--- | :--- | :--- |
| 僵尸预占回收 | 30s | 按 `gw:holds:exp` 全局扫描到期预占；`served = 1` 走兜底结算（5.4），否则全额释放 |
| 批次过期 sweeper | 300s | 持钱包行锁对当前赠金余量记 `BONUS_EXPIRE`。若已过期批次随后收到实扣，同一钱包事务保留原始扣款并冲回重叠的过期金额。 |
| 分区预建 | 每日 | `wallet_ledger` 与 `audit_logs` 预建未来 2 个月 |
| outbox 巡检重投 | 30s | 见 4.3 |
| outbox 水位删除 | 30s | 见 4.5 |
| 对账工单推进 | 30s | 与 outbox 巡检同一作业，见 5.3 |

**过期不写 Redis**：批次过期是「权威值随时间自动衰减」，不是一次余额变更，因此不适用「先改 Redis」的规则（04 的 P9 显式豁免）。分摊按 `score > now` 过滤，过期腿天然不可花；PostgreSQL 侧由 sweeper 修正即可。

### 3.5 投递水位回传

消费者每处理完一批，把「已处理到的最大 outbox id」写入 `outbox_watermark`（单行表，含 `updated_at`）。

选择表而非 Redis 的原因：它必须与 purge 作业同时可用、且不能因为 Redis 重启而回退；单行表的写入成本可以忽略。

---

## 4. 2.6 Outbox 投递

### 4.1 Topic 拓扑与顺序键

- **单一金额 topic `cdc.billing`**，Kafka key = `orgId`（Debezium EventRouter 的 `aggregateid`）。
- `OutboxAggregateType` 收敛为**一个值**，connector 的 `route.topic.replacement` 固定为 `cdc.billing`。

理由：一次结算同时改现金、赠金、资源包三桶，按 `aggregatetype` 拆分无处安放；而同一租户的消耗与发放落在不同 topic 时，消费者无法保证「先建批次、后扣批次」。

- `llm.request.events`（纯遥测、不动钱）继续直投 Kafka，不进 outbox。
- 单消费者、分区内串行；分区数决定消费并行度，与顺序保证无关（顺序由 key 决定）。

### 4.2 消耗路径的批量写入

| 项 | 取值 |
| :--- | :--- |
| 请求路径 | 只入队，零数据库调用 |
| 队列 | 有界，容量 100000 |
| 单写者 | 后台单线程批量提交 |
| 触发 | 每 20ms 或满 200 条 |
| 单批上限 | 500 行 |
| 连接池 | outbox 独立 2 连接，与冷回源隔离，总预算仍是 8 |
| 重试 | 自实扣时刻起计算 `retry-deadline`（默认 600 秒，上限 14 分钟），超过期限交由巡检处理 |

### 4.3 事件副本与巡检重投

在结算 Lua 之内写一份副本（与扣减原子，因此不存在「扣了但副本没写」）：

```
SET   gw:settle:evt:{eventId}   <outbox payload>  PX 3600000
SET   gw:settle:proof:{eventId} <key generations> PX 3600000
HSET  gw:settle:owners         {eventId}         <orgId|occurredAt>
ZADD  gw:settle:pending        <nowMs>           {eventId}
```

- 消费者提交账本后清理工单、副本和镜像标识。清理失败时，巡检通过账本按事件 ID 确认。
- admin 每 30 秒最多推进 500 笔工单。结算超过 60 秒后按副本补写 outbox，保留工单直到折叠成功或认亏。

这一层把「进程死在扣钱之后、入队之前」的窗口从「事件丢失」降级为「重投一次」。

### 4.4 准入闸门与降级

| 条件 | 行为 |
| :--- | :--- |
| 队列已满，或水位 > 50% 持续 30 秒 | 该实例停止准入，返回 503 `OUTBOX_UNAVAILABLE` 与 `Retry-After` |
| outbox 写失败率 > 1% 且 1 分钟内 ≥ 50 条 | 同上 |
| 恢复正常 | 自动放开 |

**响应语义（修订 04 §5.4）**：

- **准入前的 503 是唯一合法的 503**，此时一分钱都没扣，客户端重试安全。
- **钱一旦动了，响应里不再出现错误码**。结算之后 outbox 写失败不改变响应，事件走 4.3 的重投通道。

### 4.5 水位删除

- 每 30 秒分批清理水位以内已确认的事件。每行按原事件 ID 和发生时间匹配账本或认亏审计。
- **不设时间保留期**：Debezium 读的是 WAL，删行不影响投递；删除只解决存储。
- 主键为 UUIDv7，id 区间等价于时间区间，走主键索引即可，不需要 `created_at` 索引。
- 水位停滞超过 24 小时按时间兜底清理并发出 P1。发放授权需等账本确认后才可清理，每轮巡检会重投超过五分钟的未确认发放。
- 稳态表大小 ≈ 端到端延迟 × QPS，因此**表行数是积压的可视化指标**。
- **切换条件**：若压测中 purge 跟不上，或表行数超过 100 万，改为按日 `RANGE` 分区 + 确认分区中每一笔事件已入账或已认亏后 `DROP PARTITION`。该方案需要 publication 开启 `publish_via_partition_root` 并实测投递，不在首版范围内。

---

## 5. 一致性与兜底

### 5.1 稳态

账本折叠值 = Redis 实时值 + 未落账消耗，差额为消费者延迟窗口内的消耗，量级为秒。

### 5.2 积压：全局闸门

积压状态由全局信号统一发布。

| 信号 | 键存在 | 行为 |
| :--- | :--- | :--- |
| OK | 存在 | 正常服务 |
| OK | 缺失 | 无未确认实扣且快照版本满足下界时加载 |
| DEGRADED | 存在 | **照常服务**（Redis 领先 PG 是常态） |
| DEGRADED | 缺失 | 503 `LEDGER_BACKLOG`，`Retry-After` 取 `drainSeconds` |

配套规则：

- DEGRADED 期间，网关对**触碰到的**键执行 `PERSIST`（摘掉 TTL），恢复后下一次触碰自动重新装上。该动作幂等且廉价，不需要全局扫描。
- **监视器失联按 OK 处理**：`armed` 存在但数据键过期（admin 停机、滚动发布）时视为正常滑动过期，并打 P1 告警。**监控组件故障不得转化为客户故障。**
- 加载 Lua 在写入前比较现存 Redis 余额与 PG 快照。`gateway_rebuild_stale_delta_micros` 按现金、赠金和偏差方向记录。已过期的 Redis 键单独计入 `gateway_rebuild_live_balance_missing_total`。
- 降级拒绝重建计入 `gateway_rebuild_while_degraded_total`，日志记录组织标识。
- 冷加载原子核对未确认实扣集合和钱包快照版本下界。实扣折叠或认亏前，工单保持在集合中。存在未确认实扣或快照版本过旧时返回 `503 LEDGER_BACKLOG`，存活镜像照常服务。

**为什么闸门只挡「键缺失」**：热键租户手里的 Redis 就是权威本身，停它没有任何收益；冷租户必须从一份已知可能落后的副本取值，此时「不取值」才是唯一确定的选择。规则对所有人相同，命中面由流量形态决定，与账户身份无关。

### 5.3 永久丢失：对账工单

`gw:ledger:pos:{orgId}` 记录 `produced`（最后一次产出的事件 id）与 `since`；`folded` 在 `wallets.last_applied_event_id`。`produced > folded` = 该租户的 PG 可能缺账。

位置用于观察，具体工单按事件 ID 推进。admin 通过账本主键和审计确认每笔事件，避免较新的事件掩盖较旧的未入账事件。

| 级别 | 触发 | 判据 | 动作 |
| :--- | :--- | :--- | :--- |
| L1 重投 | 60s | `gw:settle:evt` 仍存在 | 按副本重投 outbox |
| L2 修链路 | 5 分钟 | `id ≤ 水位` 或 outbox 行存在 | admin 将 outbox 行或可核验的 Redis 副本重新投递到同一 Kafka topic。两份证据均缺失时发出 P1 |
| L3 补账 | 15 分钟 | 行不存在、`id > 水位` 且受影响的 Redis 键仍保留原镜像标识 | 以原事件 ID、时间和实扣腿折叠一笔 `ADJUST` |
| L4 认亏 | 30 分钟 | 工单未关闭 | 写审计、推进已处理位置并关闭。迟到的原消息由同一钱包事务识别并跳过 |

L3 先判定投递状态，再核对相关 Redis 键仍是扣款时的镜像。投递检查走主键和水位：

| 条件 | 结论 | 处置 |
| :--- | :--- | :--- |
| `outbox_events` 中存在该 id | 已进入链路 | L2，**禁止补账** |
| `id ≤ purge 水位` | 事件可能已经过传输链路 | L2，**禁止跳过消费者补账** |
| 两者都不成立且超过宽限期 | 无已投递证据 | 仅在扣款镜像仍可验证时允许 L3 |

配套硬约束：**网关对同一事件的重试必须有截止时间，截止之后禁止再插入**，且该截止时间显著小于 L3 的 15 分钟。否则会出现「判定为从未进入 → 补 ADJUST → 网关迟到插入 → 双重扣减」。

L4 保留认亏审计并推进快照版本。账本放弃该笔收入，存活的 Redis 镜像保留已有扣款，后续冷加载使用账本快照。每一次 L3 / L4 必须同时写审计日志、打指标（`ledger_degraded_resolved_total{level}`）并告警（L3 为 P2，L4 为 P1）。

### 5.4 兜底结算（进程死在结算前）

预占额是「输出上限 × 1.15」的**上限**，按它扣费会多扣，因此只能用下界。

- 准入时把「仅 prompt」的分摊和计价上下文存入 hold。兜底扣费受原预占腿限制。
- 流式请求处理到首帧时，写入 `served = 1` 后继续发送。非流式请求在交付前完成正常结算。
- 回收作业按状态处置：

| hold 状态 | 处置 |
| :--- | :--- |
| `served = 0` | 全额释放，不扣费 |
| `served = 1` | 释放预占冻结，按兜底下界结算，事件标 `settledAfterReclaim = true` |
| 结算成功 | 正常路径 |

兜底结算必须先校验 `gw:hold:done:{holdId}` 不存在（同一把幂等锁），并显式释放「预占腿」与「兜底腿」的差额。

### 5.5 事件丢失的方向

| 方向 | 是否可丢 | 收敛手段 |
| :--- | :--- | :--- |
| 消耗（Redis 已扣、账本无记录） | 是 | 5.3 的工单；重建时测量差额 |
| 发放（行已提交、Redis 未应用） | 否 | 消费者重放 + 幂等应用，必然最终落地 |

---

## 6. 不变量（在 04 的 P1 至 P12 之后追加）

| 编号 | 不变量 |
| :--- | :--- |
| **P13** | 键缺失时不写 delta；键存在时加载不覆盖。二者共同保证重建与增量在同一个键上永不交错 |
| **P14** | 事件只携带实扣量，落库一律加法，禁止钳位；出现负余额即判错账 |
| **P15** | `fold(wallet_ledger) == wallets 快照` 恒成立，二者在同一事务内写入 |
| **P16** | 消耗走「先 Redis 后 outbox」，发放走「先 outbox 后 Redis」；同一 `eventId` 贯穿两处 |
| **P17** | 重建闸门是全局判据，不得引入任何逐租户的准入状态 |
| **P18** | 钱一旦动了，响应里不再出现错误码；503 只出现在准入之前 |
| **P19** | 批次过期不写 Redis，视为权威值随时间衰减，P9 对此显式豁免 |

---

## 7. 配置

| 配置 | 默认 | 说明 |
| :--- | :--- | :--- |
| `hyperllm.cache.sliding-ttl.ttl-seconds` | 300 | 滑动 TTL 时长 |
| `hyperllm.cache.sliding-ttl.renew-throttle-seconds` | 60 | 单键续期节流窗口 |
| `hyperllm.cache.sliding-ttl.freeze` | false | 全局强制 PERSIST |
| `hyperllm.cache.sliding-ttl.freeze-orgs` | 空 | 按组织强制 PERSIST |
| `hyperllm.billing.lag-monitor.interval-seconds` | 15 | 监视器采样周期 |
| `hyperllm.billing.lag-monitor.signal-ttl-seconds` | 60 | 健康信号存活时间 |
| `hyperllm.billing.lag-monitor.records-threshold` | 5000 | 记录级 lag 阈值 |
| `hyperllm.billing.lag-monitor.drain-seconds-threshold` | 60 | 排空时间阈值 |
| `hyperllm.outbox.flush-interval-ms` | 20 | 批量刷盘间隔 |
| `hyperllm.outbox.batch-size` | 200 | 触发刷盘的条数 |
| `hyperllm.outbox.max-batch-rows` | 500 | 单次 multi-row INSERT 上限 |
| `hyperllm.outbox.queue-capacity` | 100000 | 有界队列容量 |
| `hyperllm.outbox.admission-pause-ratio` | 0.5 | 队列水位超过该值即停准入 |
| `hyperllm.outbox.purge-interval-seconds` | 30 | 水位删除周期 |
| `hyperllm.outbox.purge-batch-rows` | 5000 | 单次删除行数 |
| `hyperllm.outbox.watermark-stall-hours` | 24 | 水位停滞兜底 |
| `hyperllm.outbox.retry-deadline-seconds` | 600 | 网关单事件重试截止，必须远小于 L3 的 15 分钟 |
| `hyperllm.ledger.redelivery-delay-seconds` | 60 | 巡检重投的等待阈值 |
| `hyperllm.ledger.reconcile-l1-seconds` 至 `...-l4-seconds` | 60 / 300 / 900 / 1800 | 工单阶梯时间点 |

---

## 8. 代码改动清单

| 模块 | 改动 |
| :--- | :--- |
| `gateway/lua` | 新增 `prehold_snapshot.lua`、`load_tenant.lua`、`settle_copy.lua` 片段；改写 `prehold_admit.lua` / `prehold_settle.lua` 接收腿清单；`prehold_renew.lua` 补钱包与批次键 |
| `gateway/GatewayConfigCacheService` | 删除 `resolveEntitlements` 的 PostgreSQL 读取与 `ensurePackAvailability`；新增快照读取与加载 |
| `gateway/GatewayProxyService` | 准入改传腿清单；结算用 hold 的腿重跑分摊；删除「直投 Kafka 结算事件」 |
| `gateway/GatewayTelemetryService` | 结算事件改写入 outbox 队列；事件门禁补 `packDeltaUnits != 0` |
| `common/BillingSettlementEvent` | 迁移为 `BillingMoneyEvent`，旧类保留一版只读兼容 |
| `common/ErrorCode` | 新增 `LEDGER_BACKLOG` 与 `OUTBOX_UNAVAILABLE`（均为 503，带 `Retry-After`） |
| `admin/BillingSettlementConsumer` | 独立 Bean 承载事务；按逐腿扣减；落 `allocation_detail_json` 与新列；推进 `last_applied_event_id`；提交后 `ZREM` |
| `admin` 新增作业 | 僵尸预占回收、批次 sweeper、分区预建、outbox 巡检重投、水位删除、对账工单推进 |
| `admin/AdminOrgService` | 列表 `LEFT JOIN wallets` 内联余额；新增单租户实时钱包查询 |
| `console/ConsoleWalletService` | 单租户钱包改读 Redis |
| `domain` | `V4_2__outbox_and_ledger_columns.sql`：删 `idx_outbox_events_created`、`outbox_events` 设 `fillfactor = 100`、`wallet_ledger` 增 `shortfall_micros` 与 `settled_after_reclaim`、`wallets` 增 `last_applied_event_id`、新增 `outbox_watermark` 单行表 |
| `infra/debezium` | `hyperllm-outbox-connector.json` 的 `route.topic.replacement` 改为 `cdc.billing` |

---

## 9. 验收手段

**2.4**

- 容器测试：长流跨 TTL 后钱包键仍存在；空闲 300 秒后键消失并可按 checkpoint 重建。
- 容器测试：空键哨兵、加载原子性、并发加载只回源一次、**存在键不被加载覆盖**。
- 断言：结算路径对 `resource_packs` / `bonus_grants` 的查询次数为 0。
- 断言：纯资源包请求产生一条事件且 `packDeltaUnits` 非零。
- 断言：结算后逐批 `held` 归零，item 余量按到期顺序递减，无超卖。
- 单测：健康信号四种状态映射到正确的网关动作；信号缺失且 `armed` 存在时按 OK 处理并打 P1。
- 断言：`DEGRADED` 且键存在时请求照常 200；`DEGRADED` 且键缺失时返回 `LEDGER_BACKLOG` 且带 `Retry-After`。
- 断言：`admin` 租户列表为常数条 SQL。

**2.5**

- 容器测试：跨月入账、重复事件只落一行、消费者恢复后余额一致。
- 容器测试：逐腿余量扣减与账本 delta 一致；过期 sweeper 只写一次 `BONUS_EXPIRE`。
- 断言：`allocation_detail_json` 可由明细复算出 `chargedMicros`。
- 断言：任意时刻 `fold(wallet_ledger) == wallets`（随机化测试，含并发投递）。
- 断言：负余额不会触发（构造乱序投递，验证幂等门禁与重试）。

**2.6**

- 容器测试：批量插入行数正确、UUIDv7 单调、无二级索引仍可按区间删除。
- 容器测试：队列满时停准入并返回 503；恢复后自动放开。
- 断言：结算后 outbox 写失败时响应仍为 200，事件达到 60 秒宽限期后进入下一次巡检。
- 断言：水位推进后表行数收敛到未确认事件数量级。
- 断言：三条路径产生的事件契约一致，消费者无需分支即可处理。

---

## 10. 待确认

无。本节保留为空占位，后续新出现的分歧点记在这里。

已定事项：监视器失联 fail-open + P1（5.2）、兜底结算落地（5.4）、发放幂等标记随钱包镜像保留（2.1）、按组织分 cell（2.3）、超额分摊先 hold 腿后补分摊（2.2）、删除 `deductExact` 第二写路径（2.2）。

**交付方式**：2.4、2.5、2.6 **一次性实现、一次上线**，不存在「先换契约再换传输」的中间态，也不允许两条投递通道长期并存。代码按依赖顺序分多个提交，但只作为一个变更集部署。
