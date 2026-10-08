# HyperLLM Phase 2.1 详细设计：Flyway V4、jOOQ 与领域仓储

> 本文件是 [04-00-overview.md](./04-00-overview.md) 索引下的 **2.1 子任务**设计稿，对应 [TODO.md](../TODO.md) 的「2.1 Flyway V4、jOOQ 与领域仓储」。
> 设计输入：本文件第 2 节的 12 条决议（阶段二设计评审时定稿）。设计产出：V4 完整 DDL、领域模型与枚举、9 个仓储接口契约、测试设计与验收标准。
> **本文档不含任何业务算法**——三顺位分配、预估预占额、金额计算全部归 2.2 / 2.3。

---

## 1. 范围与非目标

### 1.1 在范围内

1. Flyway `V4__init_commercial_billing_outbox.sql`：9 张新表 + `wallet_ledger` 外键与索引补齐。
2. jOOQ 重新生成（`-Pjooq`），生成配置**无改动**。
3. `domain` 模块：9 个不可变 `record` 实体 + 14 个枚举。
4. `domain` 模块：9 个仓储接口 + 对应 jOOQ 实现（含行映射）。
5. `infra/debezium/hyperllm-config-connector.json` 的订阅表 9 → 10 张。
6. 测试：`V4MigrationPathTest`、`V4SchemaConstraintTest`、`CommercialBillingRepositoryIntegrationTest`。
7. 四处文档口径修正（PROPOSAL §2.4.2、01 §5、02 §4、02 §6.2 的「12 张」与 `-Pjooq` 命令表述）。

### 1.2 非目标（明确不做）

| 不做的事 | 归属 |
| :--- | :--- |
| 三顺位分配算法、协议价选择 | 2.2 |
| Pre-hold / Settle 的 Lua 与租约回收 | 2.3 |
| 结算消费者、分区预建作业、Outbox purge | 2.4 |
| Outbox 投递链路与 Redis 加额 | 2.5 |
| 下单、验签、回调、兑换码核销 | 2.6 |
| 告警触发与对账 | 2.9 |
| 新增 `ErrorCode`、新增 `MicroUsd` helper | 各自消费阶段（2.1 不动 `common`） |
| `payment_orders.order_no` 的生成规则 | 2.6（V4 只保证唯一性） |

---

## 2. 决议记录

| # | 决议 | 理由 |
| :--- | :--- | :--- |
| D1 | 设计稿按子任务分文件，另设 `04-00-overview.md` 承载索引与跨任务不变量 | 跨任务不变量只有一份，避免多文件复制漂移 |
| D2 | 实体 9 张全建；仓储 **9 个全建**（含 2.9 才会用到的两张告警表） | 一次铺满，后续阶段零新增接口 |
| D3 | V4 单文件；V1–V3 不可修改；`ref_order_id` 外键用普通 `ADD CONSTRAINT` | 空库重放环境无需在线规避锁；`NOT VALID` 会让约束生效性变成跨阶段悬空状态 |
| D4 | `notification_endpoints` 补 `version`；`billing_alert_rules` 补 `version` + `deleted_at`；财务/权益四表不加 `deleted_at`，用 `status` 表达终止 | 告警表是 admin 可编辑配置；财务流水软删会破坏审计语义 |
| D5 | `billing_alert_rules` 不加 `UNIQUE(org_id, project_id, alert_type)` | PG 中 NULL 互不相等，组织级告警（`project_id IS NULL`）会重复插入成功，唯一性交给服务层 |
| D6 | 批次扣减用条件原子更新，`version` 只护 admin 编辑 | 避免结算消费者在热点批次上退化为重试循环 |
| D7 | 冲突表达沿用 Phase 1：`boolean` 返回 + service 抛 `CONFLICT_OPTIMISTIC_LOCK`；2.1 不动 `common` | 错误码属 API 契约，现在定等于替 2.6 猜 |
| D8 | `outbox_events` 不分区，purge 归 2.4 | 分区会让 EventRouter 与复制槽复杂化；outbox 行是 KB 级 |
| D9 | Config CDC 订阅表在 2.1 就改成 10 张 | schema 与订阅边界必须同一次变更落地 |
| D10 | 测试 3 个类，复用现有容器，用 `CREATE DATABASE` 隔离 + 纯 Flyway API | 覆盖 V3→V4 升级路径，且 `verify` 时长基本不涨 |
| D11 | `BigDecimal` 仅限 `fx_rate`；`EntitlementStatus` 共用；数组 → `List<String>` / `List<UUID>`，JSONB → `String` | 与 T1/T4 一致，且减少 2.2 的分支 |
| D12 | `applicable_models`：`NULL` = 全部，空数组非法，由 CHECK 拒绝 | 避免 `'{}'` 被 2.2 静默解释为「全模型可用」的资损方向风险 |

---

## 3. Flyway V4

### 3.1 文件与执行顺序

单文件 `backend/domain/src/main/resources/db/migration/V4__init_commercial_billing_outbox.sql`，按外键依赖拓扑排序，用注释分段：

```text
payment_orders                     -- 无新表依赖
resource_packs                     -- → payment_orders
vouchers                           -- 无新表依赖
redemption_codes                   -- → vouchers
bonus_grants                       -- → vouchers, redemption_codes
org_model_pricing_overrides        -- → virtual_models, organizations, admin_users
notification_endpoints             -- → organizations
billing_alert_rules                -- → organizations, projects
outbox_events                      -- 无依赖
ALTER TABLE wallet_ledger          -- → payment_orders
```

### 3.2 完整 DDL

```sql
-- Phase 2 (2.1): Commercial billing foundation, payment orders, entitlements, outbox & alerts.
-- See docs/design/04-01-v4-schema.md for the design rationale of every constraint.

-- ---------------------------------------------------------------------------
-- 1. payment_orders：在线充值与资源包购买订单（不可软删，用 status 表达终止）
-- ---------------------------------------------------------------------------
CREATE TABLE payment_orders (
    id                       UUID PRIMARY KEY,
    order_no                 VARCHAR(64) NOT NULL,
    org_id                   UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    created_by_user_id       UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    order_type               VARCHAR(32) NOT NULL
        CHECK (order_type IN ('WALLET_TOPUP', 'RESOURCE_PACK_PURCHASE')),
    payment_channel          VARCHAR(32) NOT NULL
        CHECK (payment_channel IN ('STRIPE', 'ALIPAY', 'WECHAT_PAY')),
    pay_currency             VARCHAR(8) NOT NULL CHECK (pay_currency IN ('USD', 'CNY')),
    pay_amount_minor         BIGINT NOT NULL CHECK (pay_amount_minor > 0),
    fx_rate                  NUMERIC(18, 8) NOT NULL CHECK (fx_rate > 0),
    credit_micros            BIGINT NOT NULL DEFAULT 0 CHECK (credit_micros >= 0),
    invoiceable_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (invoiceable_amount_minor >= 0),
    pack_spec_json           JSONB NULL,
    external_tx_id           VARCHAR(160) NULL,
    idempotency_key          VARCHAR(128) NOT NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CLOSED', 'REFUNDED')),
    paid_at                  TIMESTAMPTZ NULL,
    expires_at               TIMESTAMPTZ NOT NULL,
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_payment_orders_order_no UNIQUE (order_no),
    CONSTRAINT uk_payment_orders_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_payment_orders_paid_at
        CHECK (status <> 'PAID' OR paid_at IS NOT NULL)
);

CREATE UNIQUE INDEX uk_payment_orders_external_tx
    ON payment_orders (payment_channel, external_tx_id)
    WHERE external_tx_id IS NOT NULL;

CREATE INDEX idx_payment_orders_org_created
    ON payment_orders (org_id, created_at DESC);

-- 供 2.6 的关单扫描：WHERE status = 'PENDING' AND expires_at < now()
CREATE INDEX idx_payment_orders_pending_expiry
    ON payment_orders (expires_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 2. resource_packs：第一顺位定向模型资源包
-- ---------------------------------------------------------------------------
CREATE TABLE resource_packs (
    id                UUID PRIMARY KEY,
    org_id            UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    pack_name         VARCHAR(120) NOT NULL,
    unit_type         VARCHAR(32) NOT NULL CHECK (unit_type IN ('TOKENS', 'CALLS')),
    applicable_models TEXT[] NOT NULL,
    total_units       BIGINT NOT NULL CHECK (total_units > 0),
    remaining_units   BIGINT NOT NULL
        CHECK (remaining_units >= 0 AND remaining_units <= total_units),
    purchase_order_id UUID NULL REFERENCES payment_orders (id) ON DELETE RESTRICT,
    expires_at        TIMESTAMPTZ NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_resource_packs_models_non_empty
        CHECK (cardinality(applicable_models) > 0),
    CONSTRAINT chk_resource_packs_models_no_blank
        CHECK (array_position(applicable_models, '') IS NULL)
);

CREATE INDEX idx_resource_packs_org_status_expires
    ON resource_packs (org_id, status, expires_at);

CREATE INDEX idx_resource_packs_purchase_order
    ON resource_packs (purchase_order_id)
    WHERE purchase_order_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. vouchers：代金券/赠金模板
-- ---------------------------------------------------------------------------
CREATE TABLE vouchers (
    id                      UUID PRIMARY KEY,
    name                    VARCHAR(120) NOT NULL,
    voucher_type            VARCHAR(32) NOT NULL
        CHECK (voucher_type IN ('SIGNUP_BONUS', 'PROMO_CODE', 'CAMPAIGN')),
    amount_micros           BIGINT NOT NULL CHECK (amount_micros > 0),
    applicable_models       TEXT[] NULL,
    valid_days              INTEGER NULL CHECK (valid_days IS NULL OR valid_days > 0),
    fixed_expires_at        TIMESTAMPTZ NULL,
    max_redemptions_total   INTEGER NULL
        CHECK (max_redemptions_total IS NULL OR max_redemptions_total > 0),
    max_redemptions_per_org INTEGER NOT NULL DEFAULT 1 CHECK (max_redemptions_per_org > 0),
    status                  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    created_by_admin_id     UUID NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_vouchers_expiry_mode
        CHECK (NOT (valid_days IS NOT NULL AND fixed_expires_at IS NOT NULL)),
    CONSTRAINT chk_vouchers_models_non_empty
        CHECK (applicable_models IS NULL OR cardinality(applicable_models) > 0),
    CONSTRAINT chk_vouchers_models_no_blank
        CHECK (applicable_models IS NULL OR array_position(applicable_models, '') IS NULL)
);

-- ---------------------------------------------------------------------------
-- 4. redemption_codes：批量兑换码池（只追加 + 一次状态迁移）
-- ---------------------------------------------------------------------------
CREATE TABLE redemption_codes (
    id                   UUID PRIMARY KEY,
    voucher_id           UUID NOT NULL REFERENCES vouchers (id) ON DELETE RESTRICT,
    code_hash            VARCHAR(128) NOT NULL,
    code_hint            VARCHAR(24) NULL,
    redeemed_org_id      UUID NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    redeemed_by_user_id  UUID NULL REFERENCES users (id) ON DELETE RESTRICT,
    redeemed_at          TIMESTAMPTZ NULL,
    expires_at           TIMESTAMPTZ NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'UNUSED'
        CHECK (status IN ('UNUSED', 'REDEEMED', 'EXPIRED', 'VOIDED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_redemption_codes_hash UNIQUE (code_hash),
    CONSTRAINT chk_redemption_codes_redeemed_at
        CHECK (status <> 'REDEEMED' OR (redeemed_org_id IS NOT NULL AND redeemed_at IS NOT NULL))
);

CREATE INDEX idx_redemption_codes_voucher_status
    ON redemption_codes (voucher_id, status);

-- ---------------------------------------------------------------------------
-- 5. bonus_grants：第二顺位赠金批次
-- ---------------------------------------------------------------------------
CREATE TABLE bonus_grants (
    id                 UUID PRIMARY KEY,
    org_id             UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    grant_source       VARCHAR(32) NOT NULL
        CHECK (grant_source IN ('SIGNUP', 'VOUCHER', 'ADMIN_GRANT', 'COMPENSATION')),
    voucher_id         UUID NULL REFERENCES vouchers (id) ON DELETE RESTRICT,
    redemption_code_id UUID NULL REFERENCES redemption_codes (id) ON DELETE RESTRICT,
    applicable_models  TEXT[] NULL,
    initial_micros     BIGINT NOT NULL CHECK (initial_micros > 0),
    remaining_micros   BIGINT NOT NULL
        CHECK (remaining_micros >= 0 AND remaining_micros <= initial_micros),
    expires_at         TIMESTAMPTZ NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_bonus_grants_models_non_empty
        CHECK (applicable_models IS NULL OR cardinality(applicable_models) > 0),
    CONSTRAINT chk_bonus_grants_models_no_blank
        CHECK (applicable_models IS NULL OR array_position(applicable_models, '') IS NULL)
);

CREATE INDEX idx_bonus_grants_org_status_expires
    ON bonus_grants (org_id, status, expires_at);

-- ---------------------------------------------------------------------------
-- 6. org_model_pricing_overrides：大客户单模型协议价（纳入 Config CDC）
-- ---------------------------------------------------------------------------
CREATE TABLE org_model_pricing_overrides (
    id                  UUID PRIMARY KEY,
    org_id              UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    virtual_model_id    UUID NOT NULL REFERENCES virtual_models (id) ON DELETE RESTRICT,
    override_mode       VARCHAR(32) NOT NULL
        CHECK (override_mode IN ('DISCOUNT_BPS', 'FIXED_PRICING')),
    discount_ratio_bps  INTEGER NULL
        CHECK (discount_ratio_bps IS NULL
               OR (discount_ratio_bps > 0 AND discount_ratio_bps <= 20000)),
    fixed_pricing_json  JSONB NULL,
    effective_from      TIMESTAMPTZ NOT NULL,
    effective_to        TIMESTAMPTZ NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_by_admin_id UUID NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    deleted_at          TIMESTAMPTZ NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_org_override_window
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT chk_org_override_payload
        CHECK ((override_mode = 'DISCOUNT_BPS'
                    AND discount_ratio_bps IS NOT NULL AND fixed_pricing_json IS NULL)
            OR (override_mode = 'FIXED_PRICING'
                    AND fixed_pricing_json IS NOT NULL AND discount_ratio_bps IS NULL))
);

CREATE INDEX idx_org_override_lookup
    ON org_model_pricing_overrides (org_id, virtual_model_id, effective_from DESC)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';

-- 时间窗不重叠由 2.2 的应用层校验（与 pricing_rules 一致），不在 DB 层用 EXCLUDE 约束。

-- ---------------------------------------------------------------------------
-- 7. notification_endpoints：告警通知端点（消费方为 2.9）
-- ---------------------------------------------------------------------------
CREATE TABLE notification_endpoints (
    id                       UUID PRIMARY KEY,
    org_id                   UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    channel_type             VARCHAR(16) NOT NULL
        CHECK (channel_type IN ('EMAIL', 'WEBHOOK')),
    target                   VARCHAR(512) NOT NULL,
    encrypted_signing_secret BYTEA NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    deleted_at               TIMESTAMPTZ NULL,
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_endpoints_org
    ON notification_endpoints (org_id, status)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 8. billing_alert_rules：低余额与预算告警规则（消费方为 2.9）
-- ---------------------------------------------------------------------------
CREATE TABLE billing_alert_rules (
    id                UUID PRIMARY KEY,
    org_id            UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    project_id        UUID NULL REFERENCES projects (id) ON DELETE RESTRICT,
    alert_type        VARCHAR(32) NOT NULL
        CHECK (alert_type IN ('LOW_BALANCE', 'PROJECT_SOFT_CAP', 'PROJECT_HARD_CAP')),
    threshold_micros  BIGINT NOT NULL CHECK (threshold_micros >= 0),
    endpoint_ids      UUID[] NOT NULL,
    cooldown_minutes  INTEGER NOT NULL DEFAULT 60 CHECK (cooldown_minutes > 0),
    last_triggered_at TIMESTAMPTZ NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    deleted_at        TIMESTAMPTZ NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_billing_alert_rules_endpoints
        CHECK (cardinality(endpoint_ids) > 0)
);

CREATE INDEX idx_billing_alert_rules_org
    ON billing_alert_rules (org_id, alert_type)
    WHERE deleted_at IS NULL;

-- endpoint_ids 是 UUID[]，无法建外键；引用完整性由 2.9 服务层保证
--（删除 endpoint 前必须检查是否被规则引用）。

-- ---------------------------------------------------------------------------
-- 9. outbox_events：Debezium Transactional Outbox（不分区，见 D8）
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    aggregatetype VARCHAR(64) NOT NULL,
    aggregateid   VARCHAR(64) NOT NULL,
    type          VARCHAR(64) NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 供 2.4 的 purge：DELETE FROM outbox_events WHERE created_at < now() - interval '7 days'
CREATE INDEX idx_outbox_events_created
    ON outbox_events (created_at);

-- aggregatetype 不加 CHECK：路由后缀由 Java 侧 OutboxAggregateType 约束，
-- DB 层保留扩展空间，避免每加一个业务 Topic 就要新迁移。

-- ---------------------------------------------------------------------------
-- 10. wallet_ledger：补齐 Phase 1 的外键与索引（不改 V3 本身）
-- ---------------------------------------------------------------------------
ALTER TABLE wallet_ledger
    ADD CONSTRAINT fk_wallet_ledger_ref_order
    FOREIGN KEY (ref_order_id) REFERENCES payment_orders (id) ON DELETE RESTRICT;

CREATE INDEX idx_wallet_ledger_ref_order
    ON wallet_ledger (ref_order_id)
    WHERE ref_order_id IS NOT NULL;
```

### 3.3 与 `01-database-design.md` 的差异清单

以下是本设计相对 01 文档的**新增/收紧**，实施时以本文件为准；01 文档的实体叙述保持不动。

| # | 表 | 差异 | 依据 |
| :--- | :--- | :--- | :--- |
| 1 | `notification_endpoints` | 新增 `version` | D4 |
| 2 | `billing_alert_rules` | 新增 `version` + `deleted_at`；`endpoint_ids` 去掉 `DEFAULT '{}'` | D4 |
| 3 | `payment_orders` | 新增 `pay_currency` CHECK、`chk_payment_orders_paid_at`、`idx_payment_orders_pending_expiry` | 2.6 关单扫描与订单完整性 |
| 4 | `redemption_codes` | 新增 `chk_redemption_codes_redeemed_at`、`idx_redemption_codes_voucher_status` | 兑换并发核销与限额统计 |
| 5 | `vouchers` | 新增 `chk_vouchers_expiry_mode` | 01 文档已声明「二选一」，此处落为约束 |
| 6 | `org_model_pricing_overrides` | 新增 `chk_org_override_payload`、`idx_org_override_lookup` | 防止 mode 与 payload 不一致 |
| 7 | 三张权益表 | 新增 `applicable_models` 非空数组 + 无空串 CHECK | D12 |
| 8 | `wallet_ledger` | 新增 `idx_wallet_ledger_ref_order` | V3 遗漏 |
| 9 | 枚举 | 落地 14 个（决议 D11 的口径是 12 个） | 见 4.4 说明 |

### 3.4 约束为什么放在 DB 层

三层防线：应用层（服务校验）→ DB 约束（最后防线）→ 对账作业（2.9）。V4 只放**不可用应用逻辑替代**的约束：

- **状态机不变量**（`remaining <= total`、`PAID ⇒ paid_at`、`REDEEMED ⇒ org + time`）：一旦写错就是资金错账，且不可能靠对账自动修复。
- **表达唯一性**（`applicable_models` 的 NULL / 空数组）：必须在写入时拒绝，事后清洗要改数据。
- **不放在 DB 的**：时间窗重叠（需跨行锁，应用层做）、`endpoint_ids` 引用完整性（数组无法建 FK）、告警规则唯一性（NULL 语义问题）。

---

## 4. jOOQ 与领域模型

### 4.1 生成配置

**无任何改动**。`domain/pom.xml` 的 `-Pjooq` profile 已经是「Testcontainers Postgres 16 + Flyway 全量迁移 + 提交生成源码」模式，`<excludes>` 里的 `wallet_ledger_.*|audit_logs_.*` 继续有效——V4 不新增分区表，也不会生成新的分区子表。

重新生成命令：

```bash
./backend/mvnw -f backend/pom.xml compile -pl common,domain -Pjooq
```

> `02-dependencies-and-infra.md` §4 写的 `generate-sources -pl domain -Pjooq` 与实际可用的 `compile -pl common,domain -Pjooq` 不一致，以 AGENTS / CONTRIBUTING 的命令为准（本次一并修正 02 文档）。

### 4.2 类型映射约定

| PG 类型 | jOOQ 生成 | 领域 record | 转换位置 |
| :--- | :--- | :--- | :--- |
| `UUID` | `UUID` | `UUID` | 直通 |
| `BIGINT` | `Long` | `long`（可空列用 `Long`） | 行映射时拆箱 |
| `NUMERIC(18,8)` | `BigDecimal` | `BigDecimal` | 直通（仅 `fx_rate`） |
| `TEXT[]` | `String[]` | `List<String>` | 行映射 `Arrays.asList`；写入 `toArray(String[]::new)` |
| `UUID[]` | `UUID[]` | `List<UUID>` | 同上 |
| `JSONB` | `JSONB` | `String` | 直通（沿用 Phase 1 的 `metadataJson` 范式） |
| `TIMESTAMPTZ` | `OffsetDateTime` | `OffsetDateTime` | 直通 |
| `VARCHAR` 状态列 | `String` | 对应枚举 | `valueOf` / `.name()` |

### 4.3 九个 record

全部放在 `app.pagefault.hyperllm.domain.model.billing` 与 `...model.payment`（订单归 payment，权益与告警归 billing）：

```text
payment.PaymentOrder(UUID id, String orderNo, UUID orgId, UUID createdByUserId, OrderType orderType,
    PaymentChannel paymentChannel, String payCurrency, long payAmountMinor, BigDecimal fxRate,
    long creditMicros, long invoiceableAmountMinor, String packSpecJson, String externalTxId,
    String idempotencyKey, OrderStatus status, OffsetDateTime paidAt, OffsetDateTime expiresAt,
    long version, OffsetDateTime createdAt, OffsetDateTime updatedAt)

billing.ResourcePack(UUID id, UUID orgId, String packName, PackUnitType unitType,
    List<String> applicableModels, long totalUnits, long remainingUnits, UUID purchaseOrderId,
    OffsetDateTime expiresAt, EntitlementStatus status, long version,
    OffsetDateTime createdAt, OffsetDateTime updatedAt)

billing.Voucher(UUID id, String name, VoucherType voucherType, long amountMicros,
    List<String> applicableModels, Integer validDays, OffsetDateTime fixedExpiresAt,
    Integer maxRedemptionsTotal, int maxRedemptionsPerOrg, VoucherStatus status,
    UUID createdByAdminId, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt)

billing.RedemptionCode(UUID id, UUID voucherId, String codeHash, String codeHint,
    UUID redeemedOrgId, UUID redeemedByUserId, OffsetDateTime redeemedAt, OffsetDateTime expiresAt,
    RedemptionStatus status, OffsetDateTime createdAt)

billing.BonusGrant(UUID id, UUID orgId, GrantSource grantSource, UUID voucherId,
    UUID redemptionCodeId, List<String> applicableModels, long initialMicros, long remainingMicros,
    OffsetDateTime expiresAt, EntitlementStatus status, long version,
    OffsetDateTime createdAt, OffsetDateTime updatedAt)

billing.OrgModelPricingOverride(UUID id, UUID orgId, UUID virtualModelId, OverrideMode overrideMode,
    Integer discountRatioBps, String fixedPricingJson, OffsetDateTime effectiveFrom,
    OffsetDateTime effectiveTo, ToggleStatus status, UUID createdByAdminId, OffsetDateTime deletedAt,
    long version, OffsetDateTime createdAt, OffsetDateTime updatedAt)

billing.NotificationEndpoint(UUID id, UUID orgId, NotificationChannelType channelType, String target,
    byte[] encryptedSigningSecret, ToggleStatus status, OffsetDateTime deletedAt,
    long version, OffsetDateTime createdAt, OffsetDateTime updatedAt)

billing.BillingAlertRule(UUID id, UUID orgId, UUID projectId, AlertType alertType,
    long thresholdMicros, List<UUID> endpointIds, int cooldownMinutes, OffsetDateTime lastTriggeredAt,
    ToggleStatus status, OffsetDateTime deletedAt, long version,
    OffsetDateTime createdAt, OffsetDateTime updatedAt)

billing.OutboxEvent(UUID id, String aggregateType, String aggregateId, String type,
    String payload, OffsetDateTime createdAt)
```

> `byte[] encryptedSigningSecret` 与 01 文档的 `BYTEA` 一致；解密在 2.9 发送时于内存完成，AAD 绑定 `endpoint_id`。

### 4.4 枚举（14 个，全部在 `domain/model/enums/`）

| 枚举 | 取值 | 说明 |
| :--- | :--- | :--- |
| `OrderType` | `WALLET_TOPUP`, `RESOURCE_PACK_PURCHASE` | |
| `PaymentChannel` | `STRIPE`, `ALIPAY`, `WECHAT_PAY` | |
| `OrderStatus` | `PENDING`, `PAID`, `FAILED`, `CLOSED`, `REFUNDED` | |
| `PackUnitType` | `TOKENS`, `CALLS` | |
| `EntitlementStatus` | `ACTIVE`, `EXHAUSTED`, `EXPIRED`, `REVOKED` | **resource_packs 与 bonus_grants 共用** |
| `VoucherType` | `SIGNUP_BONUS`, `PROMO_CODE`, `CAMPAIGN` | |
| `VoucherStatus` | `ACTIVE`, `PAUSED`, `ENDED` | |
| `RedemptionStatus` | `UNUSED`, `REDEEMED`, `EXPIRED`, `VOIDED` | |
| `GrantSource` | `SIGNUP`, `VOUCHER`, `ADMIN_GRANT`, `COMPENSATION` | |
| `OverrideMode` | `DISCOUNT_BPS`, `FIXED_PRICING` | |
| `NotificationChannelType` | `EMAIL`, `WEBHOOK` | |
| `AlertType` | `LOW_BALANCE`, `PROJECT_SOFT_CAP`, `PROJECT_HARD_CAP` | |
| `ToggleStatus` | `ACTIVE`, `DISABLED` | **notification_endpoints 与 billing_alert_rules 共用** |
| `OutboxAggregateType` | `WALLET_TOPUP`, `WALLET_ADJUST`, `PACK_GRANT`, `BONUS_GRANT` | 每个值带 `topicSuffix()` → `wallet.topup` 等 |

**为什么是 14 而不是决议里的 12**：D11 的选项清单漏计了两个枚举——`vouchers.voucher_type` 需要 `VoucherType`，两张告警表的 `status` 需要状态枚举。收敛动作保持不变（`EntitlementStatus` 与 `ToggleStatus` 各共用一次），最终 14 个。这是实现层对决议口径的修正，不是新增决策。

---

## 5. 仓储接口

### 5.1 通用约定

| 场景 | 签名形态 | 语义 |
| :--- | :--- | :--- |
| 新建（主键由调用方生成 UUIDv7） | `void insert(T)` | 唯一键冲突抛 `DataIntegrityViolationException` |
| 新建但唯一键冲突属正常重放 | `boolean insertIfAbsent(T)` | 冲突 ⇒ `false`，不抛异常 |
| 单条查询 | `Optional<T> findById(UUID)` | |
| 乐观锁写（admin 侧） | `boolean update(..., long expectedVersion)` | 未命中 ⇒ 由 service 抛 `CONFLICT_OPTIMISTIC_LOCK` |
| 条件原子扣减 | `boolean decrement...(..., OffsetDateTime at)` | 条件不满足 ⇒ `false`，不重试、不抛 |
| 分页 | `PageResponse<T> listByOrg(UUID, PageQuery)` | 返回单元与 Phase 1 一致 |

所有实现类命名 `Jooq<T>Repository`，装配方式沿用 Phase 1 的 ```Bean` 配置，不散落 ```Repository` 注解。

### 5.2 九个接口

```text
PaymentOrderRepository
  void insert(PaymentOrder)
  Optional<PaymentOrder> findById(UUID)
  Optional<PaymentOrder> findByIdempotencyKey(String)
  Optional<PaymentOrder> findByOrderNo(String)
  PageResponse<PaymentOrder> listByOrg(UUID, PageQuery)
  List<PaymentOrder> listExpiredPending(OffsetDateTime now, int limit)
  boolean updateStatus(UUID id, OrderStatus status, OffsetDateTime paidAt,
                       String externalTxId, long expectedVersion, OffsetDateTime updatedAt)

ResourcePackRepository
  void insert(ResourcePack)
  Optional<ResourcePack> findById(UUID)
  List<ResourcePack> listConsumableByOrgAndModel(UUID orgId, String modelCode, OffsetDateTime at)
  boolean decrementUnits(UUID packId, long units, OffsetDateTime updatedAt)
  boolean revoke(UUID packId, long expectedVersion, OffsetDateTime updatedAt)
  PageResponse<ResourcePack> listByOrg(UUID, PageQuery)

VoucherRepository
  void insert(Voucher)
  Optional<Voucher> findById(UUID)
  boolean update(UUID id, String name, Long validDays, OffsetDateTime fixedExpiresAt,
                 List<String> applicableModels, Integer maxRedemptionsTotal,
                 long expectedVersion, OffsetDateTime updatedAt)
  boolean updateStatus(UUID id, VoucherStatus status, long expectedVersion, OffsetDateTime updatedAt)
  PageResponse<Voucher> listPage(PageQuery)

RedemptionCodeRepository
  void insert(RedemptionCode)
  void insertBatch(List<RedemptionCode>)
  Optional<RedemptionCode> findByCodeHash(String)
  boolean markRedeemed(UUID codeId, UUID orgId, UUID userId, OffsetDateTime redeemedAt)
  long countRedeemedByVoucher(UUID voucherId)
  long countRedeemedByVoucherAndOrg(UUID voucherId, UUID orgId)
  PageResponse<RedemptionCode> listByVoucher(UUID voucherId, PageQuery)

BonusGrantRepository
  void insert(BonusGrant)
  Optional<BonusGrant> findById(UUID)
  List<BonusGrant> listConsumableByOrgAndModel(UUID orgId, String modelCode, OffsetDateTime at)
  boolean decrementMicros(UUID grantId, long micros, OffsetDateTime updatedAt)
  boolean revoke(UUID grantId, long expectedVersion, OffsetDateTime updatedAt)
  long sumRemainingMicros(UUID orgId)
  PageResponse<BonusGrant> listByOrg(UUID, PageQuery)

OrgModelPricingOverrideRepository
  void insert(OrgModelPricingOverride)
  Optional<OrgModelPricingOverride> findById(UUID)
  Optional<OrgModelPricingOverride> findEffective(UUID orgId, UUID virtualModelId, OffsetDateTime at)
  List<OrgModelPricingOverride> listActiveByOrg(UUID orgId)
  boolean update(..., long expectedVersion, OffsetDateTime updatedAt)
  boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt)

NotificationEndpointRepository
  void insert(NotificationEndpoint)
  Optional<NotificationEndpoint> findById(UUID)
  List<NotificationEndpoint> listActiveByOrg(UUID orgId)
  boolean update(..., long expectedVersion, OffsetDateTime updatedAt)
  boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt)

BillingAlertRuleRepository
  void insert(BillingAlertRule)
  Optional<BillingAlertRule> findById(UUID)
  List<BillingAlertRule> listActiveByOrg(UUID orgId)
  boolean touchLastTriggered(UUID id, OffsetDateTime triggeredAt)
  boolean update(..., long expectedVersion, OffsetDateTime updatedAt)
  boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt)

OutboxEventRepository
  boolean insertIfAbsent(OutboxEvent)
  int deleteOlderThan(OffsetDateTime cutoff)
```

> 2.9 的冷却判定**不**在这里实现（`touchLastTriggered` 无条件写）；「查后改」还是「条件更新」留给 2.9，避免现在锁死并发语义。

### 5.3 查询形态契约（给 2.2 的硬约束）

`listConsumableByOrgAndModel` 的 SQL 形态固定为：

```sql
SELECT * FROM resource_packs
 WHERE org_id = :orgId
   AND status = 'ACTIVE'
   AND expires_at > :at
   AND applicable_models @> ARRAY[:modelCode]::text[]
 ORDER BY expires_at ASC;
```

`bonus_grants` 同理，但要多一个「`applicable_models IS NULL` 表示全部」的分支：

```sql
SELECT * FROM bonus_grants
 WHERE org_id = :orgId
   AND status = 'ACTIVE'
   AND (expires_at IS NULL OR expires_at > :at)
   AND (applicable_models IS NULL OR applicable_models @> ARRAY[:modelCode]::text[])
 ORDER BY expires_at ASC NULLS LAST;
```

两处都用 `@>` 而不是 `= ANY(...)`，因为只有前者能用数组 GIN 索引（若 2.2 认为需要再加），且语义上「数组包含该元素」更直白。**排序就是抵扣顺序**：`expires_at` 最早优先，`NULL`（无限期）批次排最后。

---

## 6. CDC 联动

`infra/debezium/hyperllm-config-connector.json` 的 `table.include.list` 追加 `public.org_model_pricing_overrides`（9 → 10 张）。

```text
V4 后（10 张）：customer_tiers, organizations, projects, api_keys, providers, channels,
                virtual_models, model_channel_bindings, pricing_rules, org_model_pricing_overrides
V5 后（12 张）：+ model_org_grants, guardrail_policies
```

**不能一次写满 12 张**：`publication.autocreate.mode=filtered` 要求所列的表全部存在，V5 才建的两张表会导致 Connector 注册失败。

新增表对应的事件 → 缓存键映射（2.2 实现，此处先约定）：

| 表 | 变更后需失效的缓存 |
| :--- | :--- |
| `org_model_pricing_overrides` | `gw:cfg:override:{org_id}:{model_code}`，并连带驱逐涉及模型的 `gw:cfg:route:{model_code}` |

> 现有 `DebeziumConfigCdcConsumer` 只按 `virtual_models.model_code` 驱逐 L1 路由；对这张表，事件里只有 `org_id` + `virtual_model_id`，没有 `model_code`，2.2 需要用 `virtual_model_id` 反查（域内缓存或 join）后驱逐。这是 2.2 的第一个具体工作项，不在 2.1 实现。

---

## 7. 测试设计

三个新测试类，全部带 ```Tag("integration")`，**不新增 Testcontainers 容器**。

### 7.1 V4MigrationPathTest（不经过 Spring）

用 `AbstractPostgresIntegrationTest.POSTGRES` 的连接信息，在容器内 `CREATE DATABASE hyperllm_v4_path`，然后直接驱动 Flyway API：

| 用例 | 断言 |
| :--- | :--- |
| `migrateToV3Only` | `target("3")` 迁移后：`payment_orders` 不存在，`wallet_ledger` 有 14 个分区 |
| `v3RowsSurviveV4` | 在 V3 状态下插入 1 行 `CONSUME` 流水（`ref_order_id IS NULL`）+ 1 行 `audit_logs` → 迁到 latest → 两行字段逐一相等，行数不变 |
| `existingLedgerAcceptsNullOrderId` | 迁移后插入 `ref_order_id IS NULL` 的新流水成功（外键不阻塞历史形态） |
| `validatesRefOrderId` | 插入 `ref_order_id = 随机 UUID` 抛 FK 违反 |
| `partitionsSurviveAlter` | 迁移后 14 个分区仍在，且新分区继承 `fk_wallet_ledger_ref_order` |

### 7.2 V4SchemaConstraintTest（Spring 上下文，自动迁到 latest）

用原生 SQL（`dsl.execute` / `JdbcTemplate`）逐条验证：

| 类别 | 用例 |
| :--- | :--- |
| CHECK | `remaining_units > total_units` 被拒；`remaining_micros > initial_micros` 被拒；`status = 'PAID'` 而 `paid_at IS NULL` 被拒；`voucher` 同时给 `valid_days` 与 `fixed_expires_at` 被拒；`override_mode = 'DISCOUNT_BPS'` 而 `discount_ratio_bps IS NULL` 被拒；`applicable_models = '{}'` 被拒；`applicable_models = ARRAY['']` 被拒；`bonus_grants.applicable_models = NULL` 通过 |
| UNIQUE | `payment_orders.order_no`、`payment_orders.idempotency_key`、`redemption_codes.code_hash`、`(payment_channel, external_tx_id)` 部分唯一 |
| FK | `resource_packs.purchase_order_id` → 不存在的订单被拒；`billing_alert_rules.project_id` → 不存在的项目被拒；`wallet_ledger.ref_order_id` → 不存在的订单被拒 |
| 索引存在性 | 查 `pg_indexes`，断言全部新索引按名存在（含 5 条部分索引） |
| 默认值 | `payment_orders.status = 'PENDING'`、`bonus_grants.status = 'ACTIVE'`、`billing_alert_rules.cooldown_minutes = 60` |

### 7.3 CommercialBillingRepositoryIntegrationTest

一个类覆盖 9 个仓储的关键行为（不写 CRUD 样板）：

| 仓储 | 用例 |
| :--- | :--- |
| `PaymentOrderRepository` | 同 `idempotency_key` 第二次 `insertIfAbsent` 返回 `false`；`updateStatus` 版本不匹配返回 `false`；`listExpiredPending` 只返回过期 `PENDING` |
| `ResourcePackRepository` | 扣减到 0 时 `remaining_units = 0`；超额扣减返回 `false` 且剩余量不变；`listConsumableByOrgAndModel` 按 `expires_at` 升序、排除过期与 `REVOKED` |
| `BonusGrantRepository` | `applicable_models IS NULL` 的批次对任意模型可见；`sumRemainingMicros` 与手工求和一致 |
| `VoucherRepository` / `RedemptionCodeRepository` | `markRedeemed` 对已兑换码返回 `false`（并发二次核销）；两个 `countRedeemed` 方法准确 |
| `OrgModelPricingOverrideRepository` | `findEffective` 命中时间窗；`softDelete` 后不再命中 |
| `NotificationEndpointRepository` / `BillingAlertRuleRepository` | 软删后 `listActiveByOrg` 不返回；`touchLastTriggered` 写入时间 |
| `OutboxEventRepository` | 同 id 第二次 `insertIfAbsent` 返回 `false`；`deleteOlderThan` 只删过期行 |

---

## 8. 验收标准

- [ ] `V4__init_commercial_billing_outbox.sql` 在空库上一次性通过，`wallet_ledger` 的 14 个分区与两行业务数据不受影响。
- [ ] `-Pjooq` 重新生成后，`src/main/generated` 的 diff 只包含 9 张新表与 `wallet_ledger` 的外键，无无关变更。
- [ ] 9 个仓储接口 + 实现全部编译通过，且 `gateway` 模块**不引用**任何 2.1 新增类型（热路径依赖不变）。
- [ ] 三个测试类在 `verify` 下通过；`./backend/mvnw -f backend/pom.xml test` 仍然零 Docker 秒级完成。
- [ ] `docker compose --env-file .env.example -f infra/docker-compose.yaml --profile pipeline config -q` 通过，且 Connector 注册后 `pub_hyperllm_config` 含 10 张表。
- [ ] 四处文档口径已修正（「12 张」→「V4 后 10 张、V5 后 12 张」；`-Pjooq` 命令统一）。
- [ ] 未修改 V1–V3 任何一行、未修改 `common` 模块。

---

## 9. 对后续阶段的硬约束（本文交付的契约）

1. **2.2** 必须用 `@>` 查询形态与 `expires_at` 排序（§5.3）；必须用 `EntitlementStatus` 处理两表；必须实现 `org_model_pricing_overrides` 的 L1 失效（§6）。
2. **2.4** 的 purge 依赖 `idx_outbox_events_created`；分区预建作业复用 V3 的 `ensure_monthly_partition`，不得另写一套。
3. **2.5** 的 `outbox_events.payload` 必须自带 `idempotency_key`（T6），因为消费端只拿得到 payload。
4. **2.6** 的关单扫描依赖 `idx_payment_orders_pending_expiry`；`order_no` 生成本阶段自定，但必须全局唯一。
5. **2.9** 的对账以 `bonus_grants` / `resource_packs` 之和与 `wallet_ledger` 之和为真值（T3），`wallets.*` 只作快照比对。
6. 任何新增约束一律走 `V6+`，不得回改 V4（T10）。

---

## 10. 风险与未决项

| # | 风险 / 未决 | 处置 |
| :--- | :--- | :--- |
| R1 | `ALTER TABLE wallet_ledger ADD FOREIGN KEY` 会对父表及其 14 个分区加 `ACCESS EXCLUSIVE` 锁 | 当前环境为空库重放，可接受；若将来对已有数据的库执行，须改为 `NOT VALID` + 择时 `VALIDATE`（届时另开迁移） |
| R2 | 两张告警表的仓储在 2.9 之前没有生产调用方 | 接受一段「已实现但未消费」的窗口；测试覆盖其行为，避免腐化 |
| R3 | `org_model_pricing_overrides` 的时间窗不重叠只在应用层校验，绕过程序直接写库可造出重叠行 | 接受；2.9 对账可加一条重叠巡检。改用 `EXCLUDE USING gist` 需 `btree_gist` 扩展，超出 01 文档已定范围 |
| R4 | `billing_alert_rules.endpoint_ids` 无外键，可引用已删除端点 | 2.9 在 softDelete endpoint 前做引用检查；发送时对缺失端点静默跳过 |
| R5 | 枚举实际 14 个，而非决议口径的 12 个 | 已在 §4.4 说明是实现层修正 |
| R6 | 01 文档 §8 的 V4 清单未给出两张告警表的列细节 | 以本文 §3.2 为准；后续维护 01 时应回链本文 |
