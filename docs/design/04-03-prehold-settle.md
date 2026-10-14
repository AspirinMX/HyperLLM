# HyperLLM Phase 2.3 详细设计：网关预占、流式结算与回收

> 本文件是 [04-00-overview.md](./04-00-overview.md) 索引下的 **2.3 子任务**设计稿，对应 [TODO.md](../TODO.md) 的「2.3 网关预占、流式结算与回收」。
> 设计输入：本文件第 2 节的 12 条决议（阶段二设计评审时定稿）。前置依赖：[04-02-entitlements.md](./04-02-entitlements.md) 交给本文的「快照 → 计划 → 应用」协议。
> **本文档只设计「准入时冻结多少、结算时怎么结、崩了怎么回收」**。事件落库与分区维护归 2.4，告警与对账归 2.9。

---

## 1. 范围与非目标

### 1.1 在范围内

1. 分档预占模型：pack 单位、赠金、现金三档各自冻结，可用额 = 剩余 − 他人预占。
2. 预占额度估算器（prompt 精确估 + maxTokens 回退链 + 安全系数）。
3. 准入脚本：限额 + 预占 + 惰性回收，单次 Lua 原子完成。
4. 结算脚本：释放本笔预占 + 按实际用量重跑分摊 + 扣减，单次 Lua 原子完成。
5. 心跳续期（分片驱动、节流）与并发租约合流。
6. 僵尸预占回收：网关惰性 + admin 周期兜底，CAS 防抢。
7. 结算幂等（hold + done 双标记）与「回收后结算」语义。
8. 失败路径收费矩阵。
9. 迁移 `V4_1__add_wallet_ledger_hold_id.sql`。
10. 计价明细 v2 与结算事件扩展。

### 1.2 非目标

| 不做的事 | 归属 |
| :--- | :--- |
| 结算事件消费、账本落库 | 2.4 |
| admin 侧回收作业的调度与监控面板 | 2.4 / 2.9 |
| 批次写入与增量镜像同步 | 2.5 / 2.6 |
| 对账与坏账统计 | 2.9 |
| 按次/多模态计价的预占 | Phase 3 |

---

## 2. 决议记录

| # | 决议 | 理由 |
| :--- | :--- | :--- |
| D1 | **分档预占**：pack 单位（按 org+model）、赠金、现金各自冻结；预占腿由 2.2 的分摊器在估算值上跑出来 | 不发明「金额 → 单位」的逆运算；堵住「只有资源包的组织被并发打穿」的敞口 |
| D2 | 估算：`jtokkit` 精确估 prompt + `maxTokens ?? 模型默认 ?? 8192` + 安全系数 1.15（bps 11500）；阶梯按**估算**的 prompt 选档 | 缺口只剩两种，被 1.15 吸收，剩余由 shortfall 兜底；模型级可降级为 chars/4 以免长 prompt 吃满线程 |
| D3 | 租约 **300s**，心跳寄生在流式分片上（最多每 15s 一次），一次脚本同时续 hold score 与并发计数 TTL | 不引入定时器与线程；并发计数与预占同生命周期，不再依赖 180s TTL 自愈 |
| D4 | 回收 = **admin 周期兜底 + 网关惰性清理**（每次准入最多 3 条），释放用 ZSET score 的 CAS | 热点组织不被扫描周期冻住，冷组织也不会永久冻结 |
| D5 | 幂等 = `gw:hold:{id}` + `gw:hold:done:{id}` 双标记；被回收后才到的结算**照扣**并打 `settled_after_reclaim` | 既防重复扣，也不给「崩一下就能免单」留口子 |
| D6 | **分离式记账**：`remaining` 恒等于 PG 真值，`held_*` 单记；可用 = 剩余 − 他人预占 | 镜像重建不必考虑在飞预占，对账公式拆成两条独立可核 |
| D7 | 结算**重跑分摊器**（快照排除自身 held），缺口走 shortfall | 与准入同一套算法（2.2 §11.1），估算偏差不会变成平台损失 |
| D8 | 收费以「是否已产生计费事实」为准：首字节前失败与任意非 2xx 全额释放；已输出正文则按已生成部分收费 | 与「首字节前无感重试」天然对齐，且不给「断开就免单」留通道 |
| D9 | **fail-closed** + 全局/组织级开关 + 影子模式灰度 | 预占失败时宁可少收也不超支；上线有退路 |
| D10 | 新增 Redis Testcontainers 基座；过期不 sleep，直接改 ZSET score；时间逻辑做成可注入时钟 | 只有真跑 Lua 才发现原子性问题；毫秒级且确定 |
| D11 | `hold_id` 落账本（新迁移） | 财务侧要能判断「正常结算」还是「回收后补扣」 |
| D12 | 迁移编号用 **V4_1**，不用 V6 | V5 已被 Phase 3 预定；V4_1 排在 V4 之后、V5 之前，编号规划全部不变，也不开 `outOfOrder` |

---

## 3. 预占模型

### 3.1 Redis 键

```text
gw:wallet:{orgId}                HASH  cash_micros, general_bonus_micros,
                                       held_cash_micros, held_bonus_micros, status
gw:pack:{orgId}:{modelCode}      ZSET  2.2 的批次索引（不变）
gw:pack:item:{packId}            HASH  2.2 的批次明细（不变）
gw:pack:avail:{orgId}:{model}    STRING 该模型下全部可用 pack 的剩余单位之和（聚合只读键）
gw:pack:held:{orgId}:{model}     STRING 该模型下已被预占的单位数
gw:hold:{holdId}                 HASH  org_id, project_id, api_key_id, model_code, request_id,
                                       pack_units, bonus_micros, cash_micros, est_charged_micros,
                                       est_prompt_tokens, created_at
gw:holds:exp                     ZSET  member = holdId, score = 租约到期 epoch ms
gw:org:holds:{orgId}             SET   该组织在飞的 holdId（惰性回收的扫描入口）
gw:hold:done:{holdId}            STRING TTL 1h，值 = 结算摘要 JSON
```

**为什么需要 `gw:pack:avail`**：准入脚本要在 O(1) 内回答「这个模型还有多少单位可用」，而单位分散在 N 个批次 Hash 里。维护一个聚合只读键，由结算应用脚本与镜像重建同时更新，准入就只需一次 `GET`。

- 聚合键与批次 Hash 的一致性由「重建即重算」保证；任何一次重建都会覆盖它。
- 该键是**派生量**，丢了可以从批次重建回来，因此不参与幂等判断。

### 3.2 准入流程

```text
1. Java：解析 canonical → 估算 prompt tokens → 定输出上限 → PricingResolver → estimatedCharged → × 11500bps
2. Java：读快照（2.2 的快照脚本，pack/bonus/cash + 各自的 held）
3. Java：EntitlementAllocator.allocate(estimate)  → holdLegs{packUnits, bonusMicros, cashMicros}
4. Redis：admission(holdLegs, limits, ttl) 单脚本原子完成：
     a. 钱包存在且 ACTIVE，否则 ORG_SUSPENDED
     b. 惰性回收：从 gw:org:holds 取至多 3 个成员，ZSCORE <= now 的按 §6 释放
     c. 校验限额（RPM / 并发），不通过则 RATE_LIMIT
     d. 校验额度：cash - held_cash >= cashLeg；bonus - held_bonus >= bonusLeg；
                  packAvail - packHeld >= packUnitsLeg；否则 INSUFFICIENT_BALANCE
     e. 写入：HINCRBY held_cash / held_bonus；INCRBY pack_held；HSET hold；
              ZADD holds:exp；SADD org:holds；并发计数 INCR + EXPIRE
     f. 返回 OK + holdId
5. 预占失败但原因可能是「快照过期」→ 重读快照重算，**最多重试 1 次**，仍失败按 402/429 返回
```

- 第 2、4 步是两次往返。**不合并**：合流就意味着把分摊算法搬进 Lua，与 D1 冲突。预算：两次 Redis 往返 + 一次本地估算。
- 影子模式（§10）下第 4 步照跑，但结果不阻断请求，只记录「若阻断会是什么结果」。

### 3.3 估算器

```text
promptTokens =
    模型 metadata_json.prehold.promptEstimation == "CHARS_PER_4" ? ceil(chars / 4)
    : TokenEstimator.countText(model, rawPromptText) + 结构开销（每条 message +4 tokens）

outputTokens =
    canonical.maxTokens
    ?? virtual_models.metadata_json.prehold.defaultMaxOutputTokens
    ?? hyperllm.billing.prehold.default-output-tokens（8192）

estimatedCharged = ceil( ceil(rawNumerator(prompt, output) / 1e6) × discountBps / 1e4 )
holdCharged      = ceil(estimatedCharged × safetyFactorBps / 1e4)     // 默认 11500
```

- 阶梯档位按 **估算的 prompt tokens** 选（输入侧 token），因为生成前无法知道 completion。
- 估算器是纯函数，入参是「已解析的 canonical + 模型元数据」，可单测。

---

## 4. 结算

### 4.1 快照调整

结算跑的仍是 2.2 的分摊器，但快照里的「可用额」要把**自己的预占视为已释放**：

```text
cashAvailable  = wallet.cash_micros    − (held_cash    − own.cashLeg)
bonusAvailable = wallet.general_bonus   − (held_bonus   − own.bonusLeg)
packAvailable  = packAvail − (packHeld − own.packUnits)     // 并逐批次按 2.2 的到期顺序取
```

这样重算出来的腿既不会被自己挡住（D7），也不会因为忽略他人预占而超卖。

### 4.2 结算脚本（`billing_settle.lua`）

```text
KEYS = [wallet, hold, done, holds:exp, org:holds, packAvail, packHeld, packItem..., bonusItem...]
ARGV = [holdId, now, packLegs..., bonusLegs..., cashLeg, chargedMicros, summaryJson, doneTtl]

1. if EXISTS done → return {'ALREADY_SETTLED', GET done}          // 纯幂等重放
2. exists = EXISTS hold
3. 若 exists:
     a. 释放本笔预占（HINCRBY held_* -ownLeg；DECRBY packHeld -ownUnits）
     b. DEL hold；ZREM holds:exp；SREM org:holds
4. 按计划扣减（无论 exists 与否都执行）
     a. 逐 pack：HINCRBY remaining -units；同步 DECRBY packAvail；remaining 归零则 ZREM
     b. 逐 bonus：HINCRBY remaining -micros；归零则 ZREM
     c. 汇总 bonus 总额：HINCRBY general_bonus -bonusTotal（钳到 >= 0）
     d. 现金：actualCash = min(cash_micros, cashLeg)；HINCRBY cash -actualCash（永不写负）
5. SET done summaryJson EX doneTtl
6. return {'OK', actualCash, actualBonus, shortfall, settledAfterReclaim = (not exists)}
```

- 第 3 步与第 4 步在**同一脚本**内完成，因此不存在「释放了却没扣」或「扣了却没释放」的中间态。
- `not exists` 分支（已被回收）依然执行第 4 步：钱确实花了（D5），只是此时额度可能已被别的请求占用，差额自然落进 shortfall。
- 失败路径（D8 规定不收费的）**不调用**本脚本，改用轻量的 `billing_release.lua`：只做第 3 步 + 写 done 标记（值 `{"released":true}`）。

### 4.3 三态判定

| 状态 | hold | done | 处理 |
| :--- | :--- | :--- | :--- |
| 正常结算 | 存在 | 无 | 释放 + 扣减 + 写 done |
| 重复结算 | 无 | 有 | 直接返回摘要，**不扣** |
| 回收后结算 | 无 | 无 | 只扣减（不释放）+ 打 `settledAfterReclaim` |
| 正常释放（失败请求） | 存在 | 无 | 释放 + 写 done（released） |

---

## 5. 心跳与租约

```text
触发：每次向客户端下发一个正文分片（流式）或每次收到上游心跳时，若距上次续期 >= 15s
脚本：billing_heartbeat.lua
  KEYS = [hold, holds:exp, concKey1..3]
  ARGV = [holdId, newScoreMs, ttlSeconds]
  1. if not EXISTS hold → return 0            // 已被回收，后续分片不再续
  2. ZADD holds:exp newScore holdId
  3. 每个 concKey：EXPIRE ttlSeconds
  4. return 1
```

- **非流式请求没有分片**：不心跳，依赖 300s 静态 TTL。非流式请求超过 300s 说明上游已异常，回收是正确行为。
- 续期失败（Redis 超时）只记指标 `gateway_prehold_heartbeat_failures`，**不中断用户流**：流还在跑，钱还在花，中断只会让用户更受损。
- 并发计数与 hold 同脚本续期（D3）。Phase 1 的做法是「成功路径 `.then()` 释放 + 180s TTL 自愈」，客户端取消时 `.then()` 不会执行，计数要等 TTL 才回收，期间并发上限被低估；合流后由心跳精确续期、由结算/释放精确回收，TTL 只作兜底。

---

## 6. 回收

```text
releaseHold(holdId, now):
  1. score = ZSCORE holds:exp holdId
  2. if score is null → return STALE（已被结算/释放/别人回收）
  3. if score > now → return STALE（心跳刚续过，不能抢）
  4. 读 hold 三条腿 → HINCRBY held_* -腿；DECRBY packHeld -units
  5. DEL hold；ZREM holds:exp；SREM org:holds
  6. return RELEASED
```

- 第 2、3 步就是**CAS**：只有「仍然过期」才允许释放，与心跳续期互斥。因为整个脚本原子执行，不存在读到旧 score 再写的窗口。
- 回收**不写账本**：此时没有任何钱动过，写的流水只会污染账本。
- 网关惰性回收在准入脚本内做（每组织每次最多 3 条），保证热点组织不等周期；admin 周期任务负责兜底（调度归 2.4，扫描范围是全局 `gw:holds:exp`）。

---

## 7. 失败路径收费矩阵

| 场景 | 释放预占 | 收费 | 说明 |
| :--- | :---: | :---: | :--- |
| 首字节前 429/5xx/连接失败（会重试其他渠道） | ✅ | ❌ | 用户什么都没拿到 |
| 全部渠道重试耗尽 | ✅ | ❌ | CHANNEL_UNAVAILABLE |
| 上游返回非 2xx（4xx/5xx 已透传） | ✅ | ❌ | 上游通常也不向我们收费 |
| 已输出正文后客户端断开 | 部分 | ✅ 已生成部分 | 用 `TokenEstimator` 估已下发文本 |
| 已输出正文后上游断流 | 部分 | ✅ 已生成部分 | `STREAM_INTERRUPTED` |
| 非流式 2xx 但 body 无法解析 usage | — | ✅ 估算 | 已有兜底路径 |
| 网关自身异常（序列化/反序列化失败） | ✅ | ❌ | 平台侧的错不该让用户付 |
| 租约已过期被回收后才结算 | — | ✅ | 打 `settledAfterReclaim`，走 shortfall |

> 「释放预占」与「收费」互斥的两种收尾：不收费走 `billing_release.lua`，收费走 `billing_settle.lua`。两条路径都写 done 标记，因此**同一笔只会走其中一条**。

---

## 8. 迁移 V4_1

```sql
-- Phase 2 (2.3): link wallet ledger rows back to the pre-hold that produced them.
-- Numbered V4_1 (not V6) so that Phase 3's reserved V5 keeps its position.
ALTER TABLE wallet_ledger
    ADD COLUMN hold_id UUID NULL;

CREATE INDEX idx_wallet_ledger_hold
    ON wallet_ledger (hold_id)
    WHERE hold_id IS NOT NULL;
```

- 对分区父表 `ADD COLUMN ... NULL` 不重写数据，14 个既有分区自动获得该列。
- 部分索引会在每个子分区上建立对应索引，不需要手工逐分区创建。
- 之后必须跑 `-Pjooq` 重新生成（`WalletLedgerRecord` 与新列、新索引）。
- 不建外键：预占是 Redis 里的短生命周期对象，没有对应的 PG 主表，`hold_id` 只作关联线索。
- 2.4 落库时写 `hold_id = event.holdId`；历史行保持 NULL。

---

## 9. 事件与明细 v2

### 9.1 结算事件

在 2.2 的 v2 基础上**再加两个字段**（仍向后兼容，追加在尾部）：

```java
public record BillingSettlementEvent(
        String requestId, OffsetDateTime eventCreatedAt, UUID orgId, UUID projectId, UUID apiKeyId,
        long cashDeltaMicros, long bonusDeltaMicros, long packDeltaUnits,
        String idempotencyKey, String description, String allocationDetailJson,
        UUID holdId,                    // 新增
        boolean settledAfterReclaim     // 新增
) {}
```

### 9.2 `AllocationDetail` v2

在 2.2 的 v1 上追加（不改既有字段）：

```json
{
  "v": 2,
  "holdId": "0193...",
  "pricing": { "...": "同 v1" },
  "usage": { "...": "同 v1" },
  "legs": [ { "...": "同 v1" } ],
  "totals": {
    "chargedMicros": 920, "shortfallMicros": 0, "degraded": false, "truncatedLegs": 0,
    "estimatedMicros": 1400, "settledAfterReclaim": false, "reclaimedAt": null
  }
}
```

- `estimatedMicros` 记录准入时的预占额，用于观察「估算偏差分布」——它是调安全系数唯一的实证依据。
- 消费者按 `v` 分支；2.4 落库时把整段 JSON 写进 `allocation_detail_json`，把 `holdId` 写进新列。

---

## 10. 配置项

| 配置 | 默认 | 说明 |
| :--- | :--- | :--- |
| `hyperllm.billing.prehold.enabled` | `true` | 全局开关，关闭即回到 Phase 1 的事后扣行为 |
| `hyperllm.billing.prehold.shadow` | `false` | 影子模式：照跑预占但不阻断，只记录判定结果 |
| `hyperllm.billing.prehold.org-whitelist` | 空（= 全部） | 灰度：只对这些组织真实启用预占 |
| `hyperllm.billing.prehold.lease-ttl-seconds` | `300` | 租约 TTL |
| `hyperllm.billing.prehold.heartbeat-min-interval-seconds` | `15` | 心跳节流 |
| `hyperllm.billing.prehold.safety-factor-bps` | `11500` | 预占安全系数 |
| `hyperllm.billing.prehold.default-output-tokens` | `8192` | 输出上限的最终回退 |
| `hyperllm.billing.prehold.lazy-reclaim-batch` | `3` | 每次准入最多惰性回收几条 |

---

## 11. 测试设计

### 11.1 纯单测

| 对象 | 用例 |
| :--- | :--- |
| 估算器 | maxTokens 三档回退；CHARS_PER_4 模式；结构开销计入；阶梯按估算 prompt 选档 |
| 预占腿计算 | 快照含他人 held 时可用额正确；pack 腿单位为 0 时不产生腿 |
| 失败矩阵 | §7 的每一行产出正确的「release or settle」决策 |
| 三态判定 | 四个组合各自走对分支（表驱动） |

### 11.2 Redis 容器测试（新增 `redis:7-alpine` 基座）

| 用例 | 断言 |
| :--- | :--- |
| 并发防超支 | 余额只够 40 笔、起 50 笔并发 → 恰好 40 笔 OK、10 笔 INSUFFICIENT_BALANCE；最终 Redis 不出现负值 |
| 资源包兜底 | 现金为 0、pack 只够 30 笔 → 恰好 30 笔 OK（验证 D1 的敞口） |
| 心跳 CAS | 先续期再回收 → 回收返回 STALE；直接改 score 到过去 → 回收返回 RELEASED 且只释放一次 |
| 重复结算 | 同一 hold 结算两次 → 第二次返回 ALREADY_SETTLED，余额只变一次 |
| 回收后结算 | 回收后到达结算 → 照扣、返回 `settledAfterReclaim=true`、不释放（已释放过） |
| 并发计数器 | 成功路径与首字节前失败路径结束后立即归零；客户端取消的用例断言「TTL 到期后归零」（兜底而非主路径） |
| 惰性回收 | 准入时顺带清掉本组织过期 hold，且不超过 3 条 |

### 11.3 端到端（依赖 2.4）

「一次 SSE → 预占 → 断连 → 结算 → 账本带 hold_id」的闭环由 2.9 的 HTTP 脚本验收；2.3 保证到「事件被正确组装 + Redis 状态正确」。

---

## 12. 验收标准

- [ ] 50 并发 / 40 笔余额的容器测试稳定通过（重复跑 10 次不抖动）。
- [ ] 成功请求结束后，`held_cash/held_bonus/pack_held` 与并发计数全部归零。
- [ ] 节点强杀（直接丢弃 hold）后，惰性回收与周期回收都能把额度放回，且只放一次。
- [ ] 同一 hold 结算两次、释放后再结算、结算后再释放，四种顺序都不产生负数与重复扣减。
- [ ] 影子模式开启时，请求成功率与 Phase 1 一致（预占不影响主流程）。
- [ ] 关闭 `prehold.enabled` 后行为与 Phase 1 完全一致（可回退）。
- [ ] `V4_1` 在空库上随 V1–V4 一起通过，`wallet_ledger` 14 个分区均获得 `hold_id` 列与部分索引。

---

## 13. 对后续阶段的硬约束

1. **2.4** 落库必须写 `hold_id`，并在 `settledAfterReclaim=true` 时把该行标记为需人工复核（不自动冲正）。
2. **2.4** 的 admin 回收作业必须复用 §6 的 CAS 脚本，不得自行 `HINCRBY` 释放。
3. **2.5/2.6** 在发放批次时必须同时刷新 `gw:pack:avail`，否则准入看到的可用单位是旧值。
4. **2.9** 的对账必须能解释 `shortfall` 与 `settledAfterReclaim` 两类差异，不能简单归为「账不平」。
5. 2.2 的明细契约升级到 v2 后，消费端必须按 `v` 分支，不得假设字段固定。

---

## 14. 对 04-02（2.2）的回填要求

本文依赖 2.2 的三处调整，2.2 实施时必须一并落地：

| # | 回填点 | 原因 |
| :--- | :--- | :--- |
| B1 | 应用脚本额外维护 `gw:pack:avail:{org}:{model}`（扣减时同步 DECRBY） | 准入要 O(1) 拿到可用单位；否则准入脚本得遍历全部批次 |
| B2 | 快照脚本增加 `held_*` 与 `pack:held` 的读取 | 可用 = 剩余 − 他人预占（D6） |
| B3 | 明细契约 v1 → v2（加 `holdId` / `estimatedMicros` / `settledAfterReclaim`） | 2.3 的结算与 2.4 的落库都要用 |

---

## 15. 风险与未决项

| # | 风险 / 未决 | 处置 |
| :--- | :--- | :--- |
| R1 | 准入两跳（快照 → 预占）之间存在竞态，热点组织预占失败率上升 | 允许重试 1 次；仍失败按 402/429 返回，并由 `gateway_prehold_conflict_total` 观测 |
| R2 | jtokkit 在超长 prompt 上占 CPU | 模型级可切 CHARS_PER_4；默认精确；指标观测估算耗时 P99 |
| R3 | 回收后结算仍扣费，用户可能感知为「重复扣」 | 明细打标 + 账本 `hold_id` 可追溯；客服口径写进运营文档（2.9） |
| R4 | `gw:pack:avail` 与批次 Hash 可能不一致 | 派生键，重建即重算；2.9 对账加入一致性巡检 |
| R5 | 影子模式多一次 Redis 往返 | 开关控制，观察期结束即关闭 |
| R6 | `V4_1` 依赖「V5 尚未落地」这个时序假设 | CI 必须有一条「空库应用全部迁移」的用例；若将来有人先落地 V5，`V4_1` 仍然排在 V4 之后、V5 之前，顺序不受影响 |
| R7 | 非流式请求无心跳，300s 后被回收却仍在处理 | 上游 `responseTimeout` 是 120s，正常不会触达；若触达说明上游异常，回收是正确的 |

---

## 16. 实现记录（2.3 落地时）

> ⚠️ **本节已部分失效**（2.4 设计评审时修订）：其中的「包与赠金在 PostgreSQL 侧仍以结算为准」「settle 脚本只递减 gw:pack:avail 聚合」两处表述已被 [04-phase2-design.md](./04-phase2-design.md) 取代。
> 依据方案基线 B1 与不变量 P1，批次归属由网关在 Redis 镜像上决定（即本节之前 4.2 节的原始设计），PG 批次余量由 2.5 按事件明细回写；逐批次镜像与滑动 TTL 的落地阶段为 **2.4**。
> 本节其余内容（D2 估算链、D3 租约与心跳、D5 双标记幂等、D9 灰度开关、D10 测试形态）仍然有效，但 D3 的「心跳续期」需按方案 3.2 节补齐钱包与批次键的续期。

2.3 已按本文骨架落地，下列位置与设计有出入，以本节为准。

| 决议 | 落地情况 |
| :--- | :--- |
| **D1** 分档预占 | ✅ 已落地。包单位按 (org, model) 聚合冻结在 `gw:pack:held`，赠金与现金冻结在钱包 Hash 的 `held_*` 字段 |
| **D2** 估算链 | ✅ 已落地：`jtokkit` 估 prompt + `maxTokens ?? 默认 8192` + 1.15 安全系数。**差异**：预占额度以可用额度封顶，因此余额极小的大请求仍会被放行并可能落 shortfall；这是为了保持 Phase 1「余额 > 0 即可用」的语义与既有端到端断言。若要严格拒绝超额请求，需把准入改成「估算值大于可用额即拒绝」，属于策略变更，留待后续评审 |
| **D3** 租约与心跳 | ✅ 已落地：300s 租约，心跳寄生在流式分片上并按 15s 节流，同一脚本续 hold 与并发计数 |
| **D4** 回收 | ✅ 网关惰性回收已落地（准入前最多 3 条，按 ZSET score 做 CAS）；❌ admin 周期兜底未实现，随 2.4 的维护作业 |
| **D5** 幂等 | ✅ 已落地：`gw:hold:done:{id}` 双标记；回收后到达的结算照扣并返回 `settledAfterReclaim=true`，事件与账本 `hold_id` 已打通 |
| **D9** 灰度 | ✅ 已落地：`prehold.enabled` 与组织白名单生效；`prehold.shadow` 是试运行模式——照常估算与分配、只记录「若真实预占会拒绝」的判定，然后走 Phase 1 准入，不产生任何 hold |
| **D10** 测试 | ✅ 已落地：`PreHoldServiceIT` 十个用例跑真实 Redis，过期用改写 ZSET score 模拟 |

**与 2.2 偏差的衔接**：批次（包/赠金）的 PG 扣减仍由 2.4 的账本事务完成；2.3 的 `settle` 脚本只做三件事——释放本笔预占、扣减 cash/bonus、按实际消耗递减 `gw:pack:avail` 聚合。因此包与赠金在 Redis 侧是「预占 + 聚合可用」两条线，在 PG 侧仍以结算为准，两条线由 300s TTL 的聚合重建收敛。
