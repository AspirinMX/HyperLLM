# HyperLLM OpenAPI 3.1 契约文档

本目录存放 **HyperLLM** 三个独立 HTTP 服务域的 **OpenAPI 3.1.0** 规范文件，供新接管仓库的开发者、前端 SDK 生成、第三方集成方以及 API 调试工具（Swagger UI / Scalar / Redoc / Postman / Insomnia）直接使用。

---

## 规范清单

| 服务域 | OpenAPI 规范文件 | 默认本地地址 | 运行时动态端点 | 鉴权体系 | 错误体格式 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Data Plane (`gateway`)** | [gateway-api.yaml](./gateway-api.yaml) | `http://localhost:8080` | —（数据面 WebFlux 网关） | `Authorization: Bearer sk-...` / `x-api-key: sk-...` / Sandbox `sk-hl-sb-...` | 原生 OpenAI / Anthropic JSON 错误格式 |
| **Developer Control Plane (`console`)** | [console-api.yaml](./console-api.yaml) | `http://localhost:8081` | `/v3/api-docs` · `/swagger-ui.html` | `HS256` Bearer JWT（`iss=hyperllm-console`, `aud=console-api`）+ `hl_console_rt` HttpOnly Cookie | RFC 9457 `application/problem+json` |
| **Operator Control Plane (`admin`)** | [admin-api.yaml](./admin-api.yaml) | `http://localhost:8082` | `/v3/api-docs` · `/swagger-ui.html` | 独立 `HS256` Bearer JWT（`iss=hyperllm-admin`, `aud=admin-api`）+ TOTP MFA 挑战令牌 + `hl_admin_rt` Cookie | RFC 9457 `application/problem+json` |

---

## 核心 API 约定（必读）

### 1. 金额与数值精度（`micro-USD`）

- 平台内部与全部 REST API 字段统一使用 **64 位有符号整型 `micro-USD`**（字段名后缀 `Micros` 或 `Micros1m`）：
  - `1 USD = 1,000,000 micro-USD`
  - 示例：`$5.00` = `5000000`；`$0.0025` = `2500`；每百万 Token `$3.00` = `3000000`
- 支付下单（`payAmountMinor` / `refundedAmountMinor`）使用目标法币的**最小货币单位**（如 USD 美分、`CNY` 分：`1000` 表示 `$10.00` 或 `¥10.00`）。
- 严禁在客户端对计费字段做浮点乘除，请使用整型或定点字符串解析（参考前端 `micro-usd.ts`）。

### 2. 主键、时间与分页

- **主键**：全平台使用时间有序的 **UUIDv7**（`format: uuid`）。
- **时间**：统一 ISO-8601 UTC 字符串（`OffsetDateTime`，`format: date-time`），月度预算月份使用 `YYYY-MM`（UTC 月份）。
- **分页**：列表接口统一接收 `page`（1-based，默认 `1`）与 `pageSize`（默认 `20`，上限由服务端约束），返回：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 42
}
```

### 3. 乐观锁（`version`）与并发控制

- 组织、项目、API Key、渠道、虚拟模型、资源包 SKU、代金券、告警端点与告警规则均携带整型 `version` 字段。
- `PATCH` / `PUT` 修改请求在请求体中提交当前 `version`；`DELETE` 接口通过查询参数 `?version=<n>` 传递。
- 若并发写导致版本不一致，服务端返回 `409 Conflict` 且 `code = "CONFLICT_OPTIMISTIC_LOCK"`。

### 4. 幂等键（`Idempotency-Key` / `idempotencyKey`）

- 开发者创建支付订单（`POST /api/v1/orgs/{orgId}/payment-orders`）必须携带 HTTP Header `Idempotency-Key`；同键同参数重放安全返回原订单，同键不同参数返回 `409 PAYMENT_ORDER_CONFLICT`。
- 运营人工充值（`POST /api/v1/admin/orgs/{orgId}/wallet/topup`）在请求体中携带 `idempotencyKey`，用于 Outbox 与 Redis 投影的双重幂等去重。

### 5. 双域安全隔离与一次展示密文

- `console` 与 `admin` 的 JWT 签名密钥、Issuer、Audience 与 Refresh Cookie 路径完全隔离，跨服务提交令牌必定被拒绝（`401`）。
- `POST /api/v1/auth/refresh` 与 `POST /api/v1/admin/auth/refresh` 均执行 **Refresh Token 轮换**；前端并发刷新必须复用单飞锁（Single-Flight），重复使用旧 Cookie 将触发 `401 AUTH_TOKEN_REUSE_DETECTED` 并吊销整个令牌家族。
- 以下敏感凭证**仅在创建响应中返回一次明文**，此后仅存哈希或密文：
  - API Key：`CreatedApiKeyDto.rawKey`（`sk-...`）
  - Webhook 签名密钥：`CreatedEndpointDto.signingSecret`
  - 兑换码批次：`CodeBatchResponse.codes`
  - Admin TOTP 绑定密钥：`MfaSetupChallengeResponse.secret`

### 6. 错误响应模型

- **Console & Admin（RFC 9457 `application/problem+json`）**：所有业务异常统一通过 `code` 字段（对应 `ErrorCode` 枚举名）做机器判定，禁止对 `detail` 做文案匹配：

```json
{
  "type": "https://hyperllm.dev/errors/conflict-optimistic-lock",
  "title": "Resource was modified concurrently",
  "status": 409,
  "detail": "Resource was modified concurrently",
  "code": "CONFLICT_OPTIMISTIC_LOCK",
  "traceId": "01935a8b7c2d7e009f123456789abcde",
  "timestamp": "2026-11-24T07:00:00Z",
  "fieldErrors": []
}
```

- **Gateway（原生协议错误体）**：
  - OpenAI 路由（`/v1/chat/completions`、`/v1/models`）返回 `{ "error": { "message", "type", "param", "code" } }`
  - Anthropic 路由（`/v1/messages`）返回 `{ "type": "error", "error": { "type", "message" } }`
  - 当命中准入背压保护（`503 LEDGER_BACKLOG` / `OUTBOX_UNAVAILABLE` / `PROJECT_BUDGET_UNAVAILABLE`）时，响应头附带 `Retry-After`（秒）。

---

## 本地浏览与 SDK 生成

### 在线 Swagger UI（服务运行后）

- Console Swagger UI：`http://localhost:8081/swagger-ui.html`（JSON：`http://localhost:8081/v3/api-docs`）
- Admin Swagger UI：`http://localhost:8082/swagger-ui.html`（JSON：`http://localhost:8082/v3/api-docs`）

### 使用 OpenAPI 规范生成 TypeScript 类型

```bash
# 生成 Console TypeScript 契约类型
npx openapi-typescript docs/openapi/console-api.yaml -o frontend/console/src/lib/api/generated.d.ts

# 生成 Admin TypeScript 契约类型
npx openapi-typescript docs/openapi/admin-api.yaml -o frontend/admin/src/lib/api/generated.d.ts
```

### 导入调试工具

直接将 [gateway-api.yaml](./gateway-api.yaml)、[console-api.yaml](./console-api.yaml)、[admin-api.yaml](./admin-api.yaml) 拖入 Postman、Insomnia、Apifox 或 Scalar 即可生成完整的接口集合与请求示例。
