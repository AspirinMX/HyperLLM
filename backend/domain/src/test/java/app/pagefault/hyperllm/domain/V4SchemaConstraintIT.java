package app.pagefault.hyperllm.domain;

import app.pagefault.hyperllm.common.id.UuidV7;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts the V4 constraints that cannot be expressed in application code: state-machine checks,
 * partial unique indexes, and the foreign key added to the partitioned ledger.
 */
class V4SchemaConstraintIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DSLContext dsl;

    @Test
    void rejectsStateMachineViolations() {
        UUID orgId = seedOrg();
        UUID userId = dsl.fetchOne("SELECT owner_user_id FROM organizations WHERE id = ?", orgId)
                .get(0, UUID.class);

        expectViolation("23514", () -> dsl.execute(
                "INSERT INTO payment_orders (id, order_no, org_id, created_by_user_id, order_type,"
                        + " payment_channel, pay_currency, pay_amount_minor, fx_rate, idempotency_key, status, expires_at)"
                        + " VALUES (?, ?, ?, ?, 'WALLET_TOPUP', 'STRIPE', 'USD', 100, 1.0, ?, 'PAID', now())",
                UuidV7.next(), "paid-without-timestamp-" + UuidV7.next(), orgId, userId, "idem-" + UuidV7.next()));

        expectViolation("23514", () -> dsl.execute(
                "INSERT INTO vouchers (id, name, voucher_type, amount_micros, valid_days, fixed_expires_at,"
                        + " max_redemptions_per_org) VALUES (?, 'Both expiry modes', 'PROMO_CODE', 1000, 5, now(), 1)",
                UuidV7.next()));

        UUID modelId = seedVirtualModel();
        expectViolation("23514", () -> dsl.execute(
                "INSERT INTO org_model_pricing_overrides (id, org_id, virtual_model_id, override_mode,"
                        + " effective_from, status) VALUES (?, ?, ?, 'DISCOUNT_BPS', now(), 'ACTIVE')",
                UuidV7.next(), orgId, modelId));
    }

    @Test
    void rejectsOutOfRangeEntitlementBalances() {
        UUID orgId = seedOrg();

        expectViolation("23514", () -> dsl.execute(
                "INSERT INTO resource_packs (id, org_id, pack_name, unit_type, applicable_models,"
                        + " total_units, remaining_units, expires_at, status)"
                        + " VALUES (?, ?, 'over-allocated', 'TOKENS', ARRAY['gpt'], 10, 11, now(), 'ACTIVE')",
                UuidV7.next(), orgId));

        expectViolation("23514", () -> dsl.execute(
                "INSERT INTO bonus_grants (id, org_id, grant_source, initial_micros, remaining_micros, status)"
                        + " VALUES (?, ?, 'ADMIN_GRANT', 100, 101, 'ACTIVE')",
                UuidV7.next(), orgId));
    }

    @Test
    void enforcesApplicableModelsSemantics() {
        UUID orgId = seedOrg();

        expectViolation("23514", () -> dsl.execute(
                "INSERT INTO resource_packs (id, org_id, pack_name, unit_type, applicable_models,"
                        + " total_units, remaining_units, expires_at) VALUES (?, ?, 'empty-models', 'TOKENS', '{}'::text[], 10, 10, now())",
                UuidV7.next(), orgId));

        expectViolation("23514", () -> dsl.execute(
                "INSERT INTO resource_packs (id, org_id, pack_name, unit_type, applicable_models,"
                        + " total_units, remaining_units, expires_at) VALUES (?, ?, 'blank-model', 'TOKENS', ARRAY[''], 10, 10, now())",
                UuidV7.next(), orgId));

        // NULL means "all models" and must stay legal for bonus grants.
        dsl.execute(
                "INSERT INTO bonus_grants (id, org_id, grant_source, initial_micros, remaining_micros, status)"
                        + " VALUES (?, ?, 'SIGNUP', 500, 500, 'ACTIVE')",
                UuidV7.next(), orgId);
        assertThat(dsl.fetchCount(dsl.selectFrom("bonus_grants"))).isGreaterThanOrEqualTo(1);
    }

    @Test
    void enforcesUniquenessForOrdersAndCodes() {
        UUID orgId = seedOrg();
        String idempotencyKey = "idem-dup-" + UuidV7.next();
        String orderNo = "order-dup-" + UuidV7.next();
        insertOrder(orderNo, idempotencyKey, orgId, "ACTIVE-TX-" + UuidV7.next());

        expectViolation("23505", () -> insertOrder(orderNo, "idem-other-" + UuidV7.next(), orgId, null));
        expectViolation("23505", () -> insertOrder("order-other-" + UuidV7.next(), idempotencyKey, orgId, null));

        String codeHash = "hash-" + UuidV7.next();
        UUID voucherId = UuidV7.next();
        dsl.execute("INSERT INTO vouchers (id, name, voucher_type, amount_micros, max_redemptions_per_org)"
                + " VALUES (?, 'uniqueness', 'PROMO_CODE', 1000, 1)", voucherId);
        dsl.execute("INSERT INTO redemption_codes (id, voucher_id, code_hash, status) VALUES (?, ?, ?, 'UNUSED')",
                UuidV7.next(), voucherId, codeHash);
        expectViolation("23505", () -> dsl.execute(
                "INSERT INTO redemption_codes (id, voucher_id, code_hash, status) VALUES (?, ?, ?, 'UNUSED')",
                UuidV7.next(), voucherId, codeHash));

        // Partial unique index on (payment_channel, external_tx_id) WHERE external_tx_id IS NOT NULL.
        String externalTxId = "tx-dup-" + UuidV7.next();
        insertOrder("order-tx-a-" + UuidV7.next(), "idem-tx-a-" + UuidV7.next(), orgId, externalTxId);
        insertOrder("order-tx-b-" + UuidV7.next(), "idem-tx-b-" + UuidV7.next(), orgId, null);
        expectViolation("23505", () -> insertOrder(
                "order-tx-c-" + UuidV7.next(), "idem-tx-c-" + UuidV7.next(), orgId, externalTxId));
    }

    @Test
    void enforcesForeignKeysAcrossNewTables() {
        UUID orgId = seedOrg();

        expectViolation("23503", () -> dsl.execute(
                "INSERT INTO resource_packs (id, org_id, pack_name, unit_type, applicable_models,"
                        + " total_units, remaining_units, purchase_order_id, expires_at)"
                        + " VALUES (?, ?, 'ghost-order', 'TOKENS', ARRAY['gpt'], 10, 10, ?, now())",
                UuidV7.next(), orgId, UUID.randomUUID()));

        expectViolation("23503", () -> dsl.execute(
                "INSERT INTO billing_alert_rules (id, org_id, project_id, alert_type, threshold_micros, endpoint_ids)"
                        + " VALUES (?, ?, ?, 'LOW_BALANCE', 0, ARRAY[?]::uuid[])",
                UuidV7.next(), orgId, UUID.randomUUID(), UuidV7.next()));
    }

    @Test
    void appliesDocumentedDefaults() {
        UUID orgId = seedOrg();
        String orderNo = "default-" + UuidV7.next();
        insertOrder(orderNo, "idem-default-" + UuidV7.next(), orgId, null);

        assertThat(dsl.fetchOne("SELECT status FROM payment_orders WHERE order_no = ?", orderNo)
                .get(0, String.class)).isEqualTo("PENDING");

        UUID grantId = UuidV7.next();
        dsl.execute("INSERT INTO bonus_grants (id, org_id, grant_source, initial_micros, remaining_micros)"
                + " VALUES (?, ?, 'SIGNUP', 100, 100)", grantId, orgId);
        assertThat(dsl.fetchOne("SELECT status FROM bonus_grants WHERE id = ?", grantId)
                .get(0, String.class)).isEqualTo("ACTIVE");

        UUID ruleId = UuidV7.next();
        dsl.execute("INSERT INTO billing_alert_rules (id, org_id, alert_type, threshold_micros, endpoint_ids)"
                + " VALUES (?, ?, 'LOW_BALANCE', 0, ARRAY[?]::uuid[])", ruleId, orgId, UuidV7.next());
        assertThat(dsl.fetchOne("SELECT cooldown_minutes FROM billing_alert_rules WHERE id = ?", ruleId)
                .get(0, Integer.class)).isEqualTo(60);
    }

    @Test
    void createsEveryDocumentedIndex() {
        List<String> expected = List.of(
                "uk_payment_orders_external_tx",
                "idx_payment_orders_org_created",
                "idx_payment_orders_pending_expiry",
                "idx_resource_packs_org_status_expires",
                "idx_resource_packs_purchase_order",
                "idx_redemption_codes_voucher_status",
                "idx_org_override_lookup",
                "idx_notification_endpoints_org",
                "idx_billing_alert_rules_org",
                "idx_wallet_ledger_ref_order"
        );
        List<String> actual = dsl.fetch("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
                .getValues(0, String.class);
        assertThat(actual).containsAll(expected);
    }

    @Test
    void dropsTheOutboxCreatedIndexBecausePurgingWalksThePrimaryKey() {
        List<String> actual = dsl.fetch("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
                .getValues(0, String.class);
        assertThat(actual).doesNotContain("idx_outbox_events_created");
    }

    @Test
    void carriesTheSettlementPipelineColumns() {
        assertThat(dsl.fetchOne("SELECT shortfall_micros, settled_after_reclaim FROM wallet_ledger LIMIT 0"))
                .isNotNull();
        assertThat(dsl.fetchOne("SELECT last_applied_event_id FROM wallets LIMIT 0")).isNotNull();
        assertThat(dsl.fetchOne("SELECT last_event_id, updated_at FROM outbox_watermark LIMIT 0")).isNotNull();
        assertThat(dsl.fetchValue("SELECT count(*) FROM outbox_watermark", Integer.class)).isEqualTo(1);
    }

    private void insertOrder(String orderNo, String idempotencyKey, UUID orgId, String externalTxId) {
        UUID userId = dsl.fetchOne("SELECT owner_user_id FROM organizations WHERE id = ?", orgId)
                .get(0, UUID.class);
        dsl.execute("INSERT INTO payment_orders (id, order_no, org_id, created_by_user_id, order_type,"
                        + " payment_channel, pay_currency, pay_amount_minor, fx_rate, external_tx_id,"
                        + " idempotency_key, expires_at)"
                        + " VALUES (?, ?, ?, ?, 'WALLET_TOPUP', 'STRIPE', 'USD', 100, 1.0, ?, ?, now())",
                UuidV7.next(), orderNo, orgId, userId, externalTxId, idempotencyKey);
    }

    private UUID seedOrg() {
        UUID userId = UuidV7.next();
        UUID orgId = UuidV7.next();
        dsl.execute("INSERT INTO users (id, email, display_name, status, version)"
                        + " VALUES (?, ?, 'Schema User', 'ACTIVE', 0)",
                userId, "schema-" + userId + "@acme.dev");
        dsl.execute("INSERT INTO organizations (id, name, slug, owner_user_id, tier_id, is_personal, status, version)"
                        + " VALUES (?, 'Schema Org', ?, ?, (SELECT id FROM customer_tiers WHERE tier_code = 'STANDARD'), false, 'ACTIVE', 0)",
                orgId, "schema-" + orgId, userId);
        return orgId;
    }

    private UUID seedVirtualModel() {
        UUID modelId = UuidV7.next();
        dsl.execute("INSERT INTO virtual_models (id, model_code, display_name, capability_type, native_protocol,"
                        + " context_window) VALUES (?, ?, 'Schema Model', 'CHAT', 'OPENAI', 1000)",
                modelId, "schema-model-" + modelId);
        return modelId;
    }

    private void expectViolation(String sqlState, Runnable action) {
        assertThatThrownBy(action::run)
                .as("expected SQLState " + sqlState)
                .satisfies(ex -> assertThat(rootSqlState(ex)).isEqualTo(sqlState));
    }

    private static String rootSqlState(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
