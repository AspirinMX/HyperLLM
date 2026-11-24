# 第 4 课：数据面 Gateway 请求全生命周期

> **本课目标**：顺着代码走完一个 `/v1/chat/completions` 或 `/v1/messages` 请求从进入 Netty 到响应结束、费用结算的完整 10 步链路，看懂三级缓存、CDC 亚秒失效、协议适配、加权路由与首字节前故障重试。

---

## 1. 入口路由与核心类地图

`backend/gateway`（`:8080`）是纯 WebFlux 响应式应用（`spring.main.web-application-type: reactive`）。接管代码时从这 8 个核心类读起就够了：

| 类 | 位置 | 职责 |
| :--- | :--- | :--- |
| `GatewayController` | `gateway/controller/` | 暴露 `/v1/chat/completions`、`/v1/messages`、`/v1/models` |
| `GatewayExceptionHandler` | `gateway/controller/` | 按路径把 `HyperLlmException` 翻译为原生 OpenAI / Anthropic 错误体，并对 `BillingAdmissionException` 附带 `Retry-After` 头 |
| `GatewayProxyService` | `gateway/proxy/` | **总编排器**——串联鉴权、路由解析、预占、重试、流式转发、心跳、结算与遥测 |
| `GatewayConfigCacheService` | `gateway/cache/` | 三级缓存（Caffeine L1 → Redis L2 → 虚拟线程 + `SingleFlight` 冷回源 `domain`） |
| `DebeziumConfigCdcConsumer` | `gateway/cache/` | 消费 `cdc.gateway.config`，亚秒级驱逐本机 Caffeine L1 |
| `ChannelSelector` & `UpstreamWebClientPool` | `gateway/routing/` | 按 `priority_tier` + `weight` 选择健康渠道；按 Provider 隔离 Reactor Netty 连接池 |
| `PreHoldService` / `ProjectBudgetService` / `ChannelCapacityService` | `gateway/billing/` | Redis Lua 预占/续期/结算/释放、项目月度预算检查、上游渠道容量保护 |
| `ProtocolRegistry` & 适配器 | `backend/protocol/` | `CanonicalChatRequest` 解析、OpenAI/Anthropic 预处理、SSE 拆帧与 Usage 嗅探、本地 token 估算 |

---

## 2. 一次请求的完整 10 步生命周期

```mermaid
sequenceDiagram
    autonumber
    participant Client as "客户端 / Playground"
    participant Proxy as "GatewayProxyService"
    participant Cache as "Caffeine L1 / Redis L2 / PG"
    participant Gate as "OutboxGate & LedgerGate"
    participant Redis as "Redis Lua Engine"
    participant Up as "上游 LLM 渠道"
    participant Outbox as "OutboxWriter & Kafka"

    Client->>Proxy: "POST /v1/chat/completions 或 /v1/messages"
    Proxy->>Proxy: "1. ProtocolAdapter.parseRequest() 提取 model/stream/max_tokens"
    Proxy->>Cache: "2. 鉴权 (sk- HMAC 或 sk-hl-sb- Sandbox) + 校验状态/IP/模型白名单"
    Proxy->>Cache: "3. 解析 VirtualModel 路由池 + 当前生效 PricingRule + 协议价覆盖"
    Proxy->>Gate: "4. 检查 OutboxAdmissionGate & LedgerHealthGate (失衡则返回 503 + Retry-After)"
    Proxy->>Redis: "5. prehold_snapshot.lua 取实时余量 → PricingResolver + EntitlementAllocator 三顺位预分摊"
    Proxy->>Redis: "6. prehold_admit.lua 原子冻结余额/批次 + 4层限流 + Project 月度预算"
    Proxy->>Up: "7. ChannelSelector 选渠道 + channel_capacity.lua + 内存解密 Key + 发起流式请求"
    alt "首字节前 429 / 5xx / 超时 / 渠道无容量"
        Proxy->>Up: "8. 排除失败渠道，自动重试备选渠道 (最多 2 次)"
    end
    loop "SSE 流式传输"
        Up-->>Proxy: "9. 收到首字节 → prehold_mark_served.lua 标记已交付; 周期心跳 prehold_renew.lua; 嗅探 Usage"
        Proxy-->>Client: "透传/还原 SSE Chunk"
    end
    Proxy->>Redis: "10. 流结束/断连 → prehold_settle.lua 按实测或本地估算多退少补"
    Proxy-->>Outbox: "异步入队 OutboxWriter (消费事件) + 直发 llm.request.events (ZDR 遥测)"
```

---

## 3. 逐环节核心设计细节

### 环节 1：双形态入口鉴权
`GatewayProxyService.authenticateCaller()` 同时支持两种凭证，方便外部 SDK 与内置 Playground 共用同一套数据面逻辑：
- **生产 API Key（`sk-...`）**：
  - 按 `key_pepper_v1`（及轮换期 `key_pepper_v2`）计算 `HMAC-SHA256` 摘要，查 `GatewayConfigCacheService`。
  - 依次校验：Key 状态为 `ACTIVE`、未过期、来源 IP 命中 `ipWhitelist`（`CidrMatcher`）、所属 Project `ACTIVE`、所属 Org `ACTIVE`、目标模型同时通过 Project 与 Key 的 `allowedModels` 白名单。
- **Playground 临时凭证（`sk-hl-sb-...`）**：
  - 由 `console` 的 `POST /api/v1/playground/ephemeral-token` 签发，仅存 Redis `gw:sandbox:{sha256}`（TTL 300s），携带绑定好的 `orgId`、`projectId`、`userId`，无数据库 Key 行。

### 环节 2：三级缓存与 Debezium CDC 亚秒级失效
配置读取遵循 **L1 → L2 → Cold PG** 三级漏斗（见 `GatewayConfigCacheService`）：
1. **Caffeine L1（进程内堆内存）**：零网络耗时，纳秒/微秒级命中，带短过期时间兜底。
2. **Lettuce Reactive Redis L2**：非阻塞读取 `gw:cfg:key:{hash}`、`gw:cfg:route:{modelCode}`、`gw:cfg:override:{orgId}:{modelId}`。
3. **Cold PG 回源**：仅在 L1/L2 双未命中时，通过 `SingleFlight` 合并并发同键请求，并切换至 `domainFallbackScheduler`（虚拟线程）调 jOOQ 查库并回填 L2 + L1，**绝不阻塞 Netty EventLoop**。

**如何做到「吊销 API Key / 停用渠道 1 秒内全集群生效」？**
- 控制面（`console` / `admin`）在修改配置事务提交后，先通过 `AfterCommitRunner` 主动删除/刷新 Redis L2。
- 同时 PostgreSQL WAL 把 10 张配置表变更经 Debezium `hyperllm-config-connector` 路由到压缩 Topic `cdc.gateway.config`。
- **关键巧思**：看 `gateway/application.yml` 第 27 行：
  ```yaml
  group-id: hyperllm-gateway-${HYPERLLM_INSTANCE_ID:${random.uuid}}
  ```
  每个 Gateway 实例启动时生成**独立 Consumer Group ID**，因此 Kafka 会把每条配置变更**广播**给集群内**每一个** Gateway 节点；`DebeziumConfigCdcConsumer` 收到后立即驱逐本地 Caffeine L1！

### 环节 3：`backend/protocol` 协议同构透传与 Usage 嗅探
- **当前落地策略（同构透传优先）**：客户端发 `/v1/chat/completions` 路由至 `OPENAI` 协议族渠道，发 `/v1/messages` 路由至 `ANTHROPIC` 协议族渠道。只改写最小必要字段（如把 `model` 替换为渠道的 `upstreamModelName`），避免全量 JSON 编解码带来的性能损耗与字段丢失。
- **OpenAI SSE 自动注入 `include_usage`**：
  - 许多 OpenAI SDK 默认不传 `stream_options.include_usage = true`，导致上游流式结束不返回 `usage`。
  - `OpenAiChatProtocolAdapter.prepareUpstreamRequest()` 会自动检测并在发往上游的 JSON 中注入 `stream_options: {"include_usage": true}`，同时记下 `injectedIncludeUsage = true`；在流式下发给客户端时，`StreamUsageTracker` 嗅探到末帧 Usage 供计费后，若客户端原本没要求，则**无痕剥离**该纯 Usage 尾帧，保持 100% 协议兼容！
- **Anthropic 原生 SSE 嗅探**：
  - `AnthropicMessagesProtocolAdapter` 通过 `SseEventSplitter` 安全处理跨 TCP `DataBuffer` 边界的残缺行，从 `message_start` 抓取 `input_tokens` / `cache_creation_input_tokens` / `cache_read_input_tokens`，从 `message_delta` 抓取 `output_tokens`。
- **本地 token 估算**：
  - `TokenEstimator` 对 UTF-8 字节数除以 4、Unicode 码点数除以 3，取较大值后向上取整。网关在请求预占、`/v1/messages/count_tokens` 和缺失上游计量的结算路径复用这一估算。

### 环节 4：路由选择、上游容量保护与首字节前重试
1. **渠道选择（`ChannelSelector`）**：把所有活跃且协议族匹配的 `GatewayChannelCandidate` 按 `priority_tier` 升序分组（Tier 1 优先于 Tier 2），同组内按 `weight` 做加权随机抽样。
2. **上游密钥内存瞬时解密**：渠道的 `encryptedCredentials` 一路以密文经过 PG → Debezium → Redis → Caffeine，直到构造上游 HTTP 请求前的一微秒才用 `AesGcmCipher.decryptUtf8(cipher, channelId)` 解密并写入 `Authorization` / `x-api-key` 头。
3. **上游容量保护（`ChannelCapacityService`）**：发请求前先调 `channel_capacity.lua` 检查该渠道自身的 RPM / TPM / 最大并发上限；若满载则不触发上游 429，而是直接把该渠道加入本次请求的 `excludedChannels` 立即换下一个渠道！
4. **首字节前无感重试 vs 首字节后不重试**：
   - 在**尚未向客户端吐出任何响应字节**前，遇到上游 `429`、`5xx`、连接异常或超时，自动把该渠道加入 `excludedChannels` 并切到下一个健康渠道重试（默认最多 2 次）。
   - 一旦向客户端下发首字节前，调用 `prehold_mark_served.lua` 写入 `served = 1` 标记；此后即使流中断也不再跨渠道重试，而是进入结算流程按已交付内容结算（若中途进程崩溃，后台回收任务会依据 `served = 1` 按准入时冻结的仅 prompt 兜底腿扣费而非全额退回！）。
5. **长流心跳续期**：流式传输期间按定时周期执行 `prehold_renew.lua`，同时续期 `gw:hold:{holdId}` 租约、并发计数、以及钱包与各批次键的滑动 TTL（带 60 秒本地节流），防止几分钟的长推理请求在半路过期。

---

**下一课**：[第 5 课：核心难点精讲——Redis 权威层、Pre-hold/Settle、Outbox 与资金一致性](./05-billing-redis-authority-and-outbox.md)（全仓含金量最高的一课！）
