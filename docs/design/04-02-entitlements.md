# HyperLLM Phase 2.2 详细设计：三顺位抵扣与组织模型协议价

> 本文件是 [04-00-overview.md](./04-00-overview.md) 索引下的 **2.2 子任务**设计稿，对应 [TODO.md](../TODO.md) 的「2.2 三顺位抵扣与组织模型协议价」。
> 设计输入：本文件第 2 节的 13 条决议（阶段二设计评审时定稿）。前置依赖：[04-01-v4-schema.md](./04-01-v4-schema.md) 交付的 9 张表与 9 个仓储。
> **本文档只设计「一次请求该扣谁的钱、扣多少」**。准入预占与流式租约归 2.3，落库与分区维护归 2.4。

---

## 1. 范围与非目标

### 1.1 在范围内

1. **定价解析**：阶梯档位选择、协议价覆盖、折扣互斥优先级链、取整顺序。
2. **批次镜像**：Redis 键形状、懒重建协议、过期与状态规则。
3. **分摊器**：纯 Java 三顺位分配，含 pack 的单位→金额换算、多批次切分、shortfall。
4. **Redis 脚本协议**：快照脚本与应用脚本，全有或全无语义。
5. **明细契约**：`AllocationDetail` 版本化 JSON + `BillingSettlementEvent` 加字段。
6. **过期 sweeper**：SQL 与算法（调度宿主归 2.4）。
7. **算法归属重构**：`CostMath` 下沉 `common`，`PricingResolver` / `EntitlementAllocator` 进 `domain`。

### 1.2 非目标

| 不做的事 | 归属 |
| :--- | :--- |
| 准入预占、流式租约续期、僵尸预占回收 | 2.3 |
| 结算事件消费、账本落库、分区维护、sweeper 调度 | 2.4 |
| 批次写入（购买/发放/兑换）与增量镜像同步 | 2.5 / 2.6 |
| 告警与对账 | 2.9 |
| 图像 / 音频 / 重排 / 按次计价 / modality 规则 | Phase 3 |
| 负余额与欠款追缴 | 不做（见 D5） |

---

## 2. 决议记录

| # | 决议 | 理由 |
| :--- | :--- | :--- |
| D1 | 分摊执行层 = **Java 算 + Lua 原子应用** | 部分抵扣/多批次/三顺位跨越等边界只有纯函数形态才能单测钉死；原子性仍在 Redis；2.3 复用同一「快照 → 计划 → 应用」协议 |
| D2 | 计价维度只做 **TOKEN 模式 + 阶梯上下文**；`billing_mode ≠ TOKEN` 的模型在 chat 端点显式拒绝 | 现有两个端点触达不到图像/音频/按次；长上下文是真实可达场景且直接决定预占额度 |
| D3 | 折扣**互斥优先级链**：生效 override > 组织折扣 > 分组默认；DISCOUNT_BPS 替代而非相乘，FIXED_PRICING 时折扣恒 10000 | 协议价是商务终价，再乘组织折扣 = 双重让利；互斥让「某客户某模型单价」只有一个来源 |
| D4 | 批次镜像沿用 01 §7 形状 + **懒重建**；增量同步留 2.5 | pack 按模型分键使资格判断天然成立；2.2 阶段无真实写入方，自建双写只会先制造漂移 |
| D5 | 三顺位扣不满时**扣到 0 为止**，产出 `shortfall_micros`；不计欠款、不回滚 | 与 V3 的 `CHECK (cash_balance_micros >= 0)` 兼容；与预付费定位一致；差额可统计不可吞噬 |
| D6 | 过期批次**读时忽略** + 周期 sweeper 写 `BONUS_EXPIRE`；调度挂 2.4 | 否则 `wallets.bonus_balance_micros` 永久虚高，准入持续误放行 |
| D7 | 2.2 就定义 `AllocationDetail` 并给结算事件加字段 | 否则分摊结果算完即丢，2.2 无法端到端验收；落库验收仍归 2.4 |
| D8 | 协议价缓存键用 `virtual_model_id` 而非 `model_code` + 空值短 TTL | CDC 事件里只有 `org_id` + `virtual_model_id`，用 id 做键可精确驱逐，不需要反查 |
| D9 | 阶梯规则 = **完整五价覆盖** + 按 `prompt_tokens` 选档 + 发布时校验连续性 | 供应商长上下文档位本就是另一套单价；预占发生在生成之前，只能按输入选档 |
| D10 | 纯算法进 `domain`，成本数学（5 数 × 5 价 → ceiling）下沉 `common` | 2.6 下单预估与 2.9 对账必须复用同一套口径，不能让 admin 重写一遍 |
| D11 | 应用冲突重试 2 次后**降级为「赠金 + 现金」**并打 `degraded` + 触发镜像重建 | 响应收尾不可阻塞、不可产生负余额；降级偏差可观测可对账，且有上限 |
| D12 | `general_bonus_micros` 保留，但降级为**派生缓存且只允许脚本写** | 准入与 2.3 预占只需两个聚合值（O(1)）；代价是写入纪律必须写死 |
| D13 | pack 按**全部计费 token 1:1** 计量，被覆盖金额用**同一次 ceiling 的比例分配** | 与计费口径同源，不产生第二套 token 口径；比例分配使「部分覆盖」与「跨类别覆盖」无歧义 |

---

## 3. 定价解析（PricingResolver）

### 3.1 优先级链

```text
输入：org（discountRatioBps 可空）、tier（defaultDiscountBps）、model、at、overrideLookup(org, model, at)

1. 若存在生效的 org_model_pricing_overrides：
   a. mode = DISCOUNT_BPS  → discountBps = override.discountRatioBps，unitPrices = pricing_rules 五价
                             source = OVERRIDE_DISCOUNT
   b. mode = FIXED_PRICING → discountBps = 10000（折扣不参与），unitPrices = fixed_pricing_json 五价
                             source = OVERRIDE_FIXED，**忽略阶梯规则**
2. 否则若 org.discountRatioBps != null → discountBps = 它，source = ORG
3. 否则                                  → discountBps = tier.defaultDiscountBps，source = TIER
4. unitPrices 在 source ≠ OVERRIDE_FIXED 时经阶梯档位替换（§3.2）
```

**多条同时生效时的确定性兜底**：按 `effective_from DESC, id ASC` 取第一条。正常路径下 2.2 的发布校验会阻止重叠（应用层加锁，见 04-01 §3.4），兜底只为脏数据保命，并由 2.9 巡检告警。

### 3.2 阶梯档位（`tiered_rules_json` 契约 v1）

```json
[
  {
    "minInputTokens": 0,
    "maxInputTokens": 131071,
    "inputPriceMicros1m": 3000000,
    "outputPriceMicros1m": 15000000,
    "cacheReadPriceMicros1m": 300000,
    "cacheWritePriceMicros1m": 3750000,
    "reasoningPriceMicros1m": 15000000
  },
  {
    "minInputTokens": 131072,
    "maxInputTokens": null,
    "inputPriceMicros1m": 6000000,
    "outputPriceMicros1m": 22500000,
    "cacheReadPriceMicros1m": 600000,
    "cacheWritePriceMicros1m": 7500000,
    "reasoningPriceMicros1m": 22500000
  }
]
```

**选档**：取 `promptTokens + cachedReadTokens + cachedWriteTokens`（即输入侧全部 token）落入 `[min, max]` 闭区间的那一档；`maxInputTokens = null` 表示无上限。未命中任何档（数组为空、或脏数据留下的空隙）→ **回落顶层五价**，并记一次告警计数，不拒绝请求。

**发布时校验**（`TieredPricingValidator`，由 admin 的定价发布端点调用）：

| 规则 | 违反时 |
| :--- | :--- |
| 数组元素数 ≤ 8 | 拒绝发布 |
| 第一项 `minInputTokens = 0` | 拒绝发布 |
| 按 `minInputTokens` 严格升序 | 拒绝发布 |
| `maxInputTokens` 为 null 仅允许出现在最后一项 | 拒绝发布 |
| 相邻项首尾相接：`prev.max + 1 == next.min` | 拒绝发布 |
| 每个单价 ≥ 0 且 ≤ 2_000_000_000（防溢出） | 拒绝发布 |

> 读取侧对不合规数据**容错**：一律回落顶层五价，绝不因为一条脏规则让整批请求失败。

### 3.3 取整顺序

沿用 Phase 1 与 03 文档已定的两步 ceiling，**不合并**：

```text
rawNumerator = promptTokens   × inputPrice
             + completionTok  × outputPrice
             + cachedReadTok  × cacheReadPrice
             + cachedWriteTok × cacheWritePrice
             + reasoningTok   × reasoningPrice        （BigInteger）

rawCostMicros   = ceil(rawNumerator / 1_000_000)       （第一次 ceiling）
chargedMicros   = ceil(rawCostMicros × discountBps / 10_000)  （第二次 ceiling）
```

理由：两个 ceiling 各自对应一次「不可分割的最小计费单位」（micro-USD 与 bps 精度），合并会让「按 1M 单价 × 折扣」与「先算原价再打折」两个口径出现不可解释的差额；且 Phase 1 已按此实现，改口径会让历史账单无法对照。代价是最多 +2 micro（0.000002 美元）。

### 3.4 定价缺失时拒绝（修掉 Phase 1 的免费漏洞）

现状：`route.activePricing() == null` 时 `exactCostMicros = 0`，即**未配置定价的模型会被免费使用**。2.2 改为：

- 在准入前判定：`activePricing == null` → `MODEL_PRICING_MISSING`（HTTP 503），不进入上游调用。
- 新增 `ErrorCode.MODEL_PRICING_MISSING(503, "No active pricing rule for this model")`。
- 这是一次刻意的行为变更：宁可挡住流量，也不能在计费地基上开一个静默免费的口子。

---

## 4. 批次镜像（Redis）

### 4.1 键与字段

```text
gw:pack:{orgId}:{modelCode}     ZSET  member = packId, score = expires_at epoch ms
gw:pack:item:{packId}           HASH  remaining_units, total_units, unit_type, status, expires_at
gw:bonus:{orgId}                ZSET  member = grantId, score = expires_at epoch ms
gw:bonus:item:{grantId}         HASH  remaining_micros, initial_micros, models ("|m1|m2|" 或 ""), status, expires_at
gw:ent:ver:{orgId}              STRING  镜像版本戳（重建时 +1）
gw:ent:stale:{orgId}            STRING  TTL 60s，降级时置位，下一次请求前强制重建
```

- **pack 按模型分键**是刻意的：`applicable_models` 的资格判断变成「这个键存在即适用」，Lua 与 Java 都不需要解析数组。
- **bonus 按组织一个 ZSET**：`models` 字段用竖线包裹的串，便于 Lua/Java 做子串判断；空串 = 全部模型。
- 每个 `item` Hash 独立存在，使「按批扣减」可以逐批 HINCRBY，而不必重写大文档。
- ZSET 的 score 就是 `expires_at`，**排序即抵扣顺序**；`expires_at IS NULL`（无限期赠金）约定 score = `0`，并用 `ZRANGE ... 0 +inf` 时的 0 分位排在最前——为保持「NULL 排最后」，实际写入时 score 取 `9999-12-31` 的 epoch ms，并在 Hash 里保留真实的 `expires_at`（可为空）。

### 4.2 重建协议（懒加载 + 版本戳）

```text
rebuildIfMissing(orgId):
  ver0 = GET gw:ent:ver:{orgId}
  if EXISTS gw:pack:{org}（该组织任一 pack 键）或 gw:bonus:{org} 存在，且未置 stale → return
  loading = SET gw:ent:loading:{orgId} NX PX 5000     // 防击穿，与 Phase 1 SingleFlight 同思路
  if loading != OK → 等待 50ms 后重试一次，仍失败则按「无权益」路径继续（降级）
  rows = PG: resource_packs(status=ACTIVE, expires_at > now) + bonus_grants(status=ACTIVE,
                                              expires_at IS NULL OR expires_at > now)
  DEL 旧键 → 逐条写 item Hash + ZSET → SET gw:ent:ver:{orgId} = ver0+1
  HINCRBY gw:wallet:{org} general_bonus_micros = （sum(剩余) - 当前值）   // 见 D12
```

**为什么用「整体重建」而不是增量修补**：批次数据量小（单组织通常 < 100 条），整体重建是幂等的、可重放的；增量修补（HINCRBY 若干次）在失败重试时无法判断是否已应用，反而制造漂移。

**重建与结算的竞态**：结算（2.4）落库会更新 PG 的 `remaining`，但重建读的是 PG —— 若重建发生在「Redis 已扣、PG 未落」的窗口内，会把已花的额度复活。缓解措施：

1. 重建只在键缺失或 stale 时触发，不在每次请求前做；
2. 重建完成后与结算并发时，Redis 与 PG 会短暂不一致，由 T3 的对账（2.9）兜底；
3. 2.4 的落地会把「PG remaining」作为权威值回写镜像，形成收敛。

> 这是本设计接受的**已知不一致窗口**，显式记录在 §12 R1，不用「假装没有」的方式掩盖。

### 4.3 过期与状态

| 事件 | 谁做 | 动作 |
| :--- | :--- | :--- |
| 读到已过期批次 | 分摊器 | 直接跳过（快照脚本已按 `expires_at > now` 过滤，理论上不会读到） |
| 剩余扣到 0 | 结算应用脚本 | `ZREM` 出 ZSET，Hash 保留 `remaining=0`，**不**改 PG status |
| PG status → EXHAUSTED | 2.4 落库 | 当 `remaining_units/micros = 0` 且 `status = ACTIVE` 时置 `EXHAUSTED` |
| 批次过期 | 2.2 sweeper（调度 2.4） | PG 置 `EXPIRED` + 写 `BONUS_EXPIRE` 流水 + 扣 `wallets.bonus_balance_micros` + 从镜像摘除后**整体重建**该组织的 bonus 聚合 |

---

## 5. 分摊器（EntitlementAllocator）

### 5.1 输入 / 输出

```text
输入：
  BillingContext  { chargedMicros, usage(5 类 token), unitPrices, tierIndex, pricingSource, discountBps }
  EntitlementSnapshot { packs[ (packId, unitType, remainingUnits, expiresAt) ],
                        bonusGrants[ (grantId, remainingMicros, expiresAt, models) ],
                        cashMicros, bonusSnapshotMicros, walletStatus }

输出：
  AllocationPlan { legs[ Leg(type, id, units, micros) ], chargedMicros, shortfallMicros, degraded }
```

### 5.2 单位 → 金额的换算（D13 的核心）

```text
totalUnits =
    unitType == CALLS  → 1
    unitType == TOKENS → prompt + completion + cacheRead + cacheWrite + reasoning

// 比例分配：第 i 个 pack 覆盖 [cumUnits_{i-1}, cumUnits_i] 这段单位
cumMicros_i = ceil( chargedMicros × cumUnits_i / totalUnits )      // 单次 ceiling
legMicros_i = cumMicros_i − cumMicros_{i−1}                          // 差分，天然无漂移
```

- 用**差分**而不是每腿独立 ceiling，可保证「全部单位被覆盖时，各腿金额之和恰好等于 `chargedMicros`」，不会多收 1~2 micro。
- 折扣已体现在 `chargedMicros` 里，所以 pack 承担的是「折后份额」：用户的包与折扣不会重复让利，也不会互相吞掉。
- `CALLS` 包因此自然退化成「覆盖整单」（`totalUnits = cumUnits = 1`），无需分支。

### 5.3 主循环

```text
allocate(ctx, snap):
    legs = []; remaining = ctx.chargedMicros; cumUnits = 0

    // 第一顺位：资源包（按 expires_at 升序，同到期按 id 升序）
    if remaining > 0:
        for pack in snap.packs (already filtered by model & expiry):
            if remaining == 0: break
            take = min(pack.remainingUnits, totalUnits − cumUnits)
            if take <= 0: continue
            cumUnits += take
            legMicros = ceil(charged × cumUnits / totalUnits) − priorCumMicros
            legs += PACK(pack.id, take, legMicros); remaining −= legMicros

    // 第二顺位：赠金批次（按 expires_at 升序，NULL 最后，同到期按 id 升序）
    for grant in snap.bonus (filtered by model & expiry):
        if remaining == 0: break
        take = min(grant.remainingMicros, remaining)
        legs += BONUS(grant.id, take); remaining −= take

    // 第三顺位：现金
    if remaining > 0:
        cashTake = min(snap.cashMicros, remaining)
        legs += CASH(cashTake)
        shortfall = remaining − cashTake
    return plan(legs, charged, shortfall, degraded=false)
```

### 5.4 不变量（单测逐条钉死）

| # | 不变量 |
| :--- | :--- |
| A1 | `Σ legs.micros + shortfall == chargedMicros`（pack 腿的差分保证不漂移） |
| A2 | 每个 pack 腿的 `units ≤ 该包 remainingUnits`，且 Σ units ≤ `totalUnits` |
| A3 | 每个 bonus 腿的 `micros ≤ 该批次 remainingMicros` |
| A4 | 三顺位严格有序：任一 PACK 腿之后不出现 BONUS/CASH 之外的乱序；CASH 腿最多一条且在最后 |
| A5 | `shortfall > 0 ⇒ 所有可用批次与现金均已被耗尽` |
| A6 | 空快照（无 pack/无 bonus/无现金）⇒ 单条 CASH(0) + shortfall = charged |
| A7 | `chargedMicros == 0` ⇒ legs 为空（不产生 0 金额腿） |
| A8 | 输入确定性：同一快照 + 同一 ctx 必产出同一 plan（排序 tie-break 必须稳定） |

---

## 6. Redis 脚本协议

### 6.1 快照脚本（`entitlement_snapshot.lua`）

一次往返读回全部所需状态，**不做任何扣减**：

```text
KEYS[1] = gw:pack:{org}:{model}      KEYS[2] = gw:bonus:{org}      KEYS[3] = gw:wallet:{org}
ARGV[1] = now_ms                     ARGV[2] = modelCode

返回（扁平数组，Java 侧解析）：
  [ walletStatus, cashMicros, bonusSnapshotMicros,
    packCount,  (packId, remainingUnits, score)*，
    bonusCount, (grantId, remainingMicros, score, models)* ]
```

- 只返回 `score > now_ms` 的成员（`ZRANGEBYSCORE`），过期批次根本不进入计划。
- `models` 的适用性判断放在 **Java** 侧（计划本来就在 Java 里算），Lua 保持哑而快。
- 槽位 `gw:wallet` 的 `status ≠ ACTIVE` 时直接返回哨兵值，由 Java 抛 `TENANT_ORG_SUSPENDED`。

### 6.2 应用脚本（`entitlement_apply.lua`）

**语义：全有或全无。** 先校验全部批次腿的剩余，再统一写入。

```text
KEYS = [wallet, packItem1, ..., bonusItem1, ...]
ARGV = [ packCount, (packKey_index, units)*, bonusCount, (bonusKey_index, micros)*,
         cashMicros, bonusTotalMicros ]

1. 逐条校验：HGET remaining_units >= units / HGET remaining_micros >= micros
   任一不满足 → return {'CONFLICT'}
2. 逐条写入：HINCRBY remaining -take；若为 0 → ZREM（需要 ZSET 键，一并放进 KEYS）
3. 现金：actualCash = min(HGET wallet cash_micros, cashMicros)   // 钳制，永不写负数
4. general_bonus_micros：HINCRBY -bonusTotalMicros，再钳到 ≥ 0
5. return {'OK', actualCash, actualBonus, shortfall}
```

- 现金腿**不参与冲突判断**：永远能成功，差额由 shortfall 吸收（D5）。
- 第 4 步的钳制是 D12 的保险丝：即便镜像漂移导致快照值大于批次之和，也不会把 `general_bonus_micros` 写成负数。

### 6.3 冲突、重试与降级（D11）

```text
for attempt in 1..3:
    snap   = snapshot(org, model)          // 每次都重读 Redis，不读 L1
    plan   = allocate(ctx, snap)
    result = apply(plan)
    if result == OK: return plan(result)
    // CONFLICT：说明批次被并发请求抢先
degradedPlan = allocate(ctx, snapWithoutEntitlements)   // 只剩赠金快照 + 现金
result = apply(degradedPlan)                            // 现金腿可钳制，必定成功
SET gw:ent:stale:{org} 1 EX 60
return degradedPlan(degraded = true)
```

> 降级会把「本该用资源包」的一次请求改走现金，属于**已知的、有上限的、可观测的**偏差。它由 `detail.totals.degraded = true` 与 ClickHouse 的 `degraded_allocations` 计数暴露，2.9 对账可量化。替代方案（放弃结算）会制造免单通道，已被否（D11）。

---

## 7. 明细契约与结算事件

### 7.1 `BillingSettlementEvent` v2

```java
public record BillingSettlementEvent(
        String requestId,
        OffsetDateTime eventCreatedAt,
        UUID orgId,
        UUID projectId,
        UUID apiKeyId,
        long cashDeltaMicros,
        long bonusDeltaMicros,      // 兼容 Phase 1：仍为赠金合计
        long packDeltaUnits,        // 新增：pack 消耗单位合计（负值）
        String idempotencyKey,
        String description,
        String allocationDetailJson // 新增：§7.2 的 JSON，可空
) {}
```

- 新字段追加在尾部，Jackson 按名反序列化，Phase 1 的消费者与历史消息不受影响（缺字段 → `0` / `null`）。
- `bonusDeltaMicros` 的语义保持「赠金总额」，**不**拆成多字段，避免动 Phase 1 已有的账本写入逻辑。
- 消费与落库（含 `allocation_detail_json` 入账本）仍由 2.4 实现。

### 7.2 `AllocationDetail` v1

```json
{
  "v": 1,
  "pricing": {
    "source": "TIER",
    "discountBps": 9500,
    "tierIndex": 1,
    "overrideId": null,
    "unitPrices": { "input": 3000000, "output": 15000000, "cacheRead": 300000,
                    "cacheWrite": 3750000, "reasoning": 15000000 }
  },
  "usage": { "prompt": 1200, "completion": 800, "cacheRead": 0, "cacheWrite": 0, "reasoning": 0 },
  "legs": [
    { "type": "PACK",  "id": "0193...", "units": 1500, "micros": 788 },
    { "type": "BONUS", "id": "0193...", "micros": 120 },
    { "type": "CASH",  "micros": 12 }
  ],
  "totals": { "chargedMicros": 920, "shortfallMicros": 0, "degraded": false, "truncatedLegs": 0 }
}
```

- `pricing` 段让一张账单**无需 join 任何表**即可解释：来源、折扣、命中档位、五个单价快照。
- `legs` 上限 32 条；超出时只保留前 32 条并把 `truncatedLegs` 记为剩余条数（保护 Kafka 单条消息与 JSONB 体积）。
- `totals.chargedMicros` 必须满足 A1：`Σ legs.micros + shortfall == chargedMicros`。

---

## 8. 过期 sweeper

```sql
-- 单批处理，行锁跳过并发实例
SELECT id, org_id, remaining_micros
  FROM bonus_grants
 WHERE status = 'ACTIVE'
   AND expires_at IS NOT NULL
   AND expires_at < :now
 ORDER BY expires_at
 LIMIT :batch
   FOR UPDATE SKIP LOCKED;

-- 每条：同一事务内
INSERT INTO wallet_ledger (..., ledger_type = 'BONUS_EXPIRE',
       idempotency_key = 'expire:' || grant_id, bonus_delta_micros = -remaining,
       created_at = :now) ON CONFLICT (idempotency_key, created_at) DO NOTHING;
UPDATE wallets SET bonus_balance_micros = bonus_balance_micros - :remaining, updated_at = :now
 WHERE org_id = :org AND bonus_balance_micros >= :remaining;
UPDATE bonus_grants SET status = 'EXPIRED', version = version + 1, updated_at = :now
 WHERE id = :grant_id;
```

- **幂等键** `expire:{grant_id}`，分区键取 `now`；同一批次重复扫描不会重复扣减（`status` 已变 + 唯一键双保险）。
- 处理完一个组织后，**整体重建**该组织的 bonus 镜像（而不是 HINCRBY 减法），避免镜像与 PG 的剩余量互相猜。
- `resource_packs` 过期只改 `status = 'EXPIRED'`，不动钱包（包不含现金）。
- 调度频率、批量大小、运维手动触发入口：2.4。

---

## 9. 算法归属与重构

| 组件 | 位置 | 形态 |
| :--- | :--- | :--- |
| `CostMath` | `common.money` | 纯静态：5 个数 × 5 个单价 → `rawCost`；再 `applyBpsCeil` → `charged` |
| `PricingResolver` | `domain.pricing` | 纯函数：输入 org/tier/model/at + `org_model_pricing_overrides` 与 `pricing_rules` 的仓储结果 → `ResolvedPricing` |
| `TieredPricingValidator` | `domain.pricing` | 纯函数：校验 §3.2 的规则，返回错误列表 |
| `EntitlementAllocator` | `domain.billing` | 纯函数：`allocate(BillingContext, EntitlementSnapshot) → AllocationPlan` |
| `AllocationDetailCodec` | `domain.billing` | 纯函数：plan → JSON（不依赖 Jackson 注解体系，手写序列化以稳定字段序） |
| `EntitlementSnapshotReader` | `gateway.billing` | 调快照脚本 → 解析成 `EntitlementSnapshot` |
| `EntitlementApplier` | `gateway.billing` | 计划 → 应用脚本；负责重试与降级 |

- `protocol.UsageCostCalculator` 退化为「`TokenUsage` → 5 个 long」的一行适配，保留是因为 `protocol` 内部（流式 Usage 嗅探）仍在用它。
- `gateway` 与 `admin` 都只调 `domain` 的纯函数，不各自实现口径（D10）。

---

## 10. 测试设计

### 10.1 纯单测（无 Docker，占大头）

| 对象 | 用例 |
| :--- | :--- |
| `PricingResolver` | 四条来源分支；FIXED 时阶梯被忽略；organizations.discount 为空继承 tier；多条 override 取 `effective_from` 最新 |
| `TieredPricingValidator` | 每条校验规则的正反例；空数组合法（= 不用阶梯）；8 档上限 |
| 阶梯选档 | 边界值 `min` / `max` / `max+1`；空隙回落顶层；null 上限 |
| `CostMath` | 两步 ceiling 的边界（余数恰为 0 / 恰为 1）；BigInteger 大数不溢出；a1e9 以上的极端 token 数 |
| `EntitlementAllocator` | §5.4 的 A1–A8 逐条；pack 部分覆盖（差分不漂移）；多 pack + 多 bonus 交叉；CALLS 包；cachedRead 高占比请求；空快照；现金为 0 |
| `AllocationDetailCodec` | 往返一致；legs 超过 32 条时 truncatedLegs 正确；字段序稳定（用于 goldens） |

### 10.2 容器集成测试

| 用例 | 断言 |
| :--- | :--- |
| 快照脚本 | 过期批次不返回；settle `NULL` expires_at 排在最后；钱包 status 非 ACTIVE 返回哨兵 |
| 应用脚本 | 全有或全无：构造一条会冲突的腿，断言**没有任何**写入；现金钳制到 0 且不出现负数；`general_bonus_micros` 不为负 |
| 恢复 | 应用脚本成功后再跑一次同一 plan → CONFLICT（剩余已变），验证幂等语义是「不重复扣」而非「静默成功」 |
| sweeper | 过期批次写 `BONUS_EXPIRE` 且只写一次；`bonus_balance_micros` 扣减正确；重复扫描不重复扣 |
| 事件序列化 | v2 事件被 Phase 1 形状的消费者忽略新字段后可正常反序列化 |

### 10.3 端到端（依赖 2.3/2.4）

真正「一次 SSE 请求 → 三顺位扣减 → 账本带明细」的闭环，在 2.4 落地后由 2.9 的 HTTP 脚本验收；2.2 只保证到「事件被正确组装」。

---

## 11. 对后续阶段的硬约束

1. **2.3** 的预占必须复用 `EntitlementAllocator` 与同一份快照脚本；预占的「上限估算」不得另算一套单价（必须调 `PricingResolver`）。
2. **2.3** 引入 `held_*` 字段后，§6.2 的应用脚本必须把 `held` 与 `remaining` 分开记账，且现金腿改为「释放预占再扣实扣」。
3. **2.4** 落库时必须写 `allocation_detail_json`，并在 `remaining == 0 && status == ACTIVE` 时置 `EXHAUSTED`。
4. **2.5** 的增量同步必须以「整体重建」为兜底：任何一次事件处理的异常都应使该组织镜像 stale，而不是就地修补。
5. **2.6** 的下单预估必须调 `PricingResolver`，不得在 admin 里复写单价选择逻辑。
6. 任何新计价维度（按次、多模态）都必须回到 §3 的契约里扩展，禁止绕过 `CostMath` 直接算金额。

---

## 12. 风险与未决项

| # | 风险 / 未决 | 处置 |
| :--- | :--- | :--- |
| R1 | 重建窗口会把「Redis 已扣、PG 未落」的额度复活 | 只在前缀缺失/stale 时重建；2.4 落库后回写镜像；2.9 对账兜底。接受为已知不一致窗口，不掩盖 |
| R2 | 应用冲突降级会把权益请求改走现金（少用包、多扣现金） | 重试 3 轮把概率压到极低；`degraded` 标记 + 2.9 指标可量化；偏差上限 = 单次请求费用 |
| R3 | 阶梯规则读取侧容错会掩盖发布侧校验的遗漏 | 读取回落时打告警计数，2.9 面板暴露「阶梯规则回退次数」 |
| R4 | 两个 ceiling 导致最多多收 2 micro | 明确记为设计选择（§3.3），不视为缺陷；对账时纳入解释 |
| R5 | `CALLS` 包的「一次请求覆盖整单」在长上下文请求上可能异常划算 | 属商务定价自由度，由运营在包规格上控制；不改算法 |
| R6 | 现金腿的 `min()` 在应用脚本内二次钳制，可能与 Java 侧计划的 `shortfall` 存在微小差异 | 以**脚本返回值**为最终真相写事件，Java 计划值只作参考；A1 不变量以脚本返回为准做二次校验 |

---

## 13. 实现偏差记录（2.2 落地时）

实现 2.2 时发现「批次镜像」与「原子应用」在**没有写入方**的阶段无法自洽：2.2 时 2.6（购买）、2.5（Outbox 同步）都还没落地，镜像只能靠重建，而重建会把「Redis 已扣、PG 未落」的额度复活。因此 2.2 采取如下落地形态，2.3 再补齐：

| 决议 | 落地情况 | 原因与去向 |
| :--- | :--- | :--- |
| **D1** Java 算 + Lua 原子应用 | 部分落地：Java 计算分摊，Redis 只做**精确分档扣减**（`deduct_wallet_split.lua`，cash/bonus 各自钳到 0）。PG 侧的批次扣减随 2.4 的账本事务执行 | 2.2 没有并发预占，冲突与重试面尚未出现；先把「算得对、传得出」做扎实 |
| **D4** 批次镜像（`gw:pack:*` / `gw:bonus:*`） | 未落地：结算时直接从 PG 读取可用批次（结算在响应之后，属异步路径） | 镜像与 Lua 应用随 **2.3 的预占**一起落地，那时 2.4/2.6 已提供写入方，重建语义才成立 |
| **D6** 过期 sweeper | 未落地：整体移入 2.4 的维护作业 | 它要写 `BONUS_EXPIRE` 账本，与 2.4 的分区维护同属一类作业 |
| **D8** 协议价缓存 | 已落地：L1 + L2，键用 `virtual_model_id`，负答案用 `OverrideCacheEntry` 包装缓存；CDC 新增 `org_model_pricing_overrides` 分支 | — |
| **D9** 阶梯契约 v1 | 已落地：完整五价、按输入侧 token 选档、发布时校验连续性 | — |
| **D10** 算法归属 | 已落地：`CostMath` 在 `common`，`PricingResolver` / `EntitlementAllocator` / `AllocationDetailCodec` 在 `domain` | — |
| **D12** 赠金快照只允许脚本写 | 部分落地：Redis 侧由 `deduct_wallet_split.lua` 保证；PG 的 `wallets.bonus_balance_micros` 仍由 2.4 更新 | — |
| **D13** 单位换算 | 落地形式调整为「按剩余额/剩余单位逐批推进」：每批取 `min(剩余单位, 剩余 token)`，金额取 `ceil(剩余金额 × 取用 / 剩余单位)` | 与累计差分等价，但能同时处理同一组织下混用 TOKENS 与 CALLS 包的场景；由于每步都读余额，舍入不会累积，A1 仍成立 |

**钱包口径的兼容处理**：2.2 从 PG 读批次时，把 `wallets.bonus_balance_micros` 中**未被任何批次行覆盖**的部分作为「未镜像赠金」追加为最后一个赠金桶。这样 Phase 1 的数据（有快照、无批次）仍可正常消费，而 2.6 之后（快照 = 批次之和）该追加量为 0，不会重复计费。网关 E2E 既有的「5000 赠金 + 6000 现金」断言在新路径下依然通过，证明兼容成立。

**顺带关闭的两个 Phase 1 缺口**：G4（`pricing == null` 免费）改为在准入阶段拒绝并返回 `MODEL_PRICING_MISSING`；G5（Redis 现金可为负）由分档脚本的下限钳制消除。

