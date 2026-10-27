package app.pagefault.hyperllm.domain;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.domain.model.billing.BillingAlertRule;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;
import app.pagefault.hyperllm.domain.model.billing.OrgModelPricingOverride;
import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.model.billing.RedemptionCode;
import app.pagefault.hyperllm.domain.model.billing.ResourcePack;
import app.pagefault.hyperllm.domain.model.billing.Voucher;
import app.pagefault.hyperllm.domain.model.enums.AlertType;
import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.GrantSource;
import app.pagefault.hyperllm.domain.model.enums.NotificationChannelType;
import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.OrderType;
import app.pagefault.hyperllm.domain.model.enums.OverrideMode;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.enums.RedemptionStatus;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.model.enums.VoucherStatus;
import app.pagefault.hyperllm.domain.model.enums.VoucherType;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import app.pagefault.hyperllm.domain.repository.BillingAlertRuleRepository;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.NotificationEndpointRepository;
import app.pagefault.hyperllm.domain.repository.OrgModelPricingOverrideRepository;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import app.pagefault.hyperllm.domain.repository.RedemptionCodeRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackRepository;
import app.pagefault.hyperllm.domain.repository.VoucherRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural coverage for the nine V4 repositories: conditional decrements, ordering semantics,
 * idempotent inserts and soft deletion. Plain CRUD is deliberately omitted.
 */
class CommercialBillingRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private DSLContext dsl;
    @Autowired private PaymentOrderRepository paymentOrderRepository;
    @Autowired private ResourcePackRepository resourcePackRepository;
    @Autowired private BonusGrantRepository bonusGrantRepository;
    @Autowired private VoucherRepository voucherRepository;
    @Autowired private RedemptionCodeRepository redemptionCodeRepository;
    @Autowired private OrgModelPricingOverrideRepository overrideRepository;
    @Autowired private NotificationEndpointRepository endpointRepository;
    @Autowired private BillingAlertRuleRepository alertRuleRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;

    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    @Test
    void paymentOrdersAreIdempotentAndVersionGuarded() {
        UUID orgId = seedOrg();
        UUID userId = ownerOf(orgId);
        String key = "idem-" + UuidV7.next();
        PaymentOrder order = order(orgId, userId, key, "PENDING", now.plusMinutes(30));

        assertThat(paymentOrderRepository.insertIfAbsent(order)).isTrue();
        assertThat(paymentOrderRepository.insertIfAbsent(order))
                .as("second insert with the same idempotency key must be a no-op")
                .isFalse();
        assertThat(paymentOrderRepository.findByIdempotencyKey(key)).isPresent();

        assertThat(paymentOrderRepository.updateStatus(order.id(), OrderStatus.PAID, now, "tx-1", 99L, now))
                .as("stale version must not update")
                .isFalse();
        assertThat(paymentOrderRepository.updateStatus(order.id(), OrderStatus.PAID, now, "tx-1", 0L, now)).isTrue();
        assertThat(paymentOrderRepository.findById(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void expiredPendingScanIgnoresPaidAndFutureOrders() {
        UUID orgId = seedOrg();
        UUID userId = ownerOf(orgId);

        PaymentOrder expired = order(orgId, userId, "idem-" + UuidV7.next(), "PENDING", now.minusMinutes(5));
        PaymentOrder future = order(orgId, userId, "idem-" + UuidV7.next(), "PENDING", now.plusHours(1));
        PaymentOrder paid = order(orgId, userId, "idem-" + UuidV7.next(), "PAID", now.minusMinutes(5));
        paymentOrderRepository.insert(expired);
        paymentOrderRepository.insert(future);
        paymentOrderRepository.insert(paid);

        List<PaymentOrder> scanned = paymentOrderRepository.listExpiredPending(now, 50);
        assertThat(scanned).extracting(PaymentOrder::id).contains(expired.id());
        assertThat(scanned).extracting(PaymentOrder::id).doesNotContain(future.id(), paid.id());
    }

    @Test
    void packDeductionIsConditionalAndOrderedByExpiry() {
        UUID orgId = seedOrg();
        UUID modelId = seedVirtualModel("pack-model-" + UuidV7.next());
        String modelCode = dsl.fetchOne("SELECT model_code FROM virtual_models WHERE id = ?", modelId)
                .get(0, String.class);

        ResourcePack later = pack(orgId, modelCode, 100, now.plusDays(10), EntitlementStatus.ACTIVE);
        ResourcePack sooner = pack(orgId, modelCode, 50, now.plusDays(1), EntitlementStatus.ACTIVE);
        ResourcePack expired = pack(orgId, modelCode, 999, now.minusDays(1), EntitlementStatus.ACTIVE);
        ResourcePack revoked = pack(orgId, modelCode, 999, now.plusDays(5), EntitlementStatus.REVOKED);
        List.of(later, sooner, expired, revoked).forEach(resourcePackRepository::insert);

        List<ResourcePack> consumable = resourcePackRepository.listConsumableByOrgAndModel(orgId, modelCode, now);
        assertThat(consumable).extracting(ResourcePack::id).containsExactly(sooner.id(), later.id());
        assertThat(resourcePackRepository.sumRemainingUnits(orgId, modelCode, now)).isEqualTo(150L);

        assertThat(resourcePackRepository.decrementUnits(sooner.id(), 50, now)).isTrue();
        assertThat(resourcePackRepository.findById(sooner.id()).orElseThrow().remainingUnits()).isZero();
        assertThat(resourcePackRepository.findById(sooner.id()).orElseThrow().status()).isEqualTo(EntitlementStatus.EXHAUSTED);

        assertThat(resourcePackRepository.decrementUnits(sooner.id(), 1, now))
                .as("decrement below zero must fail and leave the balance untouched")
                .isFalse();
        assertThat(resourcePackRepository.findById(sooner.id()).orElseThrow().remainingUnits()).isZero();
    }

    @Test
    void bonusGrantsWithNullModelsApplyToEveryModel() {
        UUID orgId = seedOrg();
        BonusGrant universal = grant(orgId, null, 1_000, now.plusDays(2));
        BonusGrant scoped = grant(orgId, List.of("only-this-model"), 2_000, now.plusDays(5));
        bonusGrantRepository.insert(universal);
        bonusGrantRepository.insert(scoped);

        assertThat(bonusGrantRepository.listConsumableByOrgAndModel(orgId, "any-model", now))
                .extracting(BonusGrant::id).contains(universal.id()).doesNotContain(scoped.id());
        assertThat(bonusGrantRepository.listConsumableByOrgAndModel(orgId, "only-this-model", now))
                .extracting(BonusGrant::id).containsExactly(universal.id(), scoped.id());
        assertThat(bonusGrantRepository.sumRemainingMicros(orgId)).isEqualTo(3_000L);

        assertThat(bonusGrantRepository.decrementMicros(universal.id(), 400, now)).isTrue();
        assertThat(bonusGrantRepository.decrementMicros(universal.id(), 601, now)).isFalse();
        assertThat(bonusGrantRepository.sumRemainingMicros(orgId)).isEqualTo(2_600L);
        assertThat(bonusGrantRepository.findById(universal.id()).orElseThrow().status()).isEqualTo(EntitlementStatus.ACTIVE);
        assertThat(bonusGrantRepository.decrementMicros(universal.id(), 600, now)).isTrue();
        assertThat(bonusGrantRepository.findById(universal.id()).orElseThrow().status()).isEqualTo(EntitlementStatus.EXHAUSTED);
    }

    @Test
    void redemptionCodesCanOnlyBeRedeemedOnce() {
        UUID orgId = seedOrg();
        UUID voucherId = seedVoucher();
        String codeHash = "hash-" + UuidV7.next();
        UUID codeId = UuidV7.next();
        redemptionCodeRepository.insert(new RedemptionCode(
                codeId, voucherId, codeHash, "HL26-****-9X2A", null, null, null,
                now.plusDays(1), RedemptionStatus.UNUSED, now
        ));

        assertThat(redemptionCodeRepository.findByCodeHash(codeHash)).isPresent();
        assertThat(redemptionCodeRepository.markRedeemed(codeId, orgId, null, now)).isTrue();
        assertThat(redemptionCodeRepository.markRedeemed(codeId, orgId, null, now))
                .as("second redemption must fail")
                .isFalse();
        assertThat(redemptionCodeRepository.countRedeemedByVoucher(voucherId)).isEqualTo(1L);
        assertThat(redemptionCodeRepository.countRedeemedByVoucherAndOrg(voucherId, orgId)).isEqualTo(1L);
        assertThat(redemptionCodeRepository.countRedeemedByVoucherAndOrg(voucherId, UUID.randomUUID())).isZero();
    }

    @Test
    void pricingOverrideIsEffectiveOnlyInsideItsWindow() {
        UUID orgId = seedOrg();
        UUID modelId = seedVirtualModel("override-model-" + UuidV7.next());
        UUID overrideId = UuidV7.next();
        overrideRepository.insert(new OrgModelPricingOverride(
                overrideId, orgId, modelId, OverrideMode.DISCOUNT_BPS, 9_000, null,
                now.minusDays(1), now.plusDays(1), ToggleStatus.ACTIVE, null, null, 0L, now, now
        ));

        assertThat(overrideRepository.findEffective(orgId, modelId, now)).isPresent();
        assertThat(overrideRepository.findEffective(orgId, modelId, now.plusDays(2))).isEmpty();

        assertThat(overrideRepository.softDelete(overrideId, 0L, now)).isTrue();
        assertThat(overrideRepository.findEffective(orgId, modelId, now)).isEmpty();
    }

    @Test
    void alertRulesAndEndpointsHonourSoftDelete() {
        UUID orgId = seedOrg();
        UUID endpointId = UuidV7.next();
        NotificationEndpoint endpoint = new NotificationEndpoint(
                endpointId, orgId, NotificationChannelType.WEBHOOK, "https://example.test/hook",
                null, ToggleStatus.ACTIVE, null, 0L, now, now
        );
        endpointRepository.insert(endpoint);
        assertThat(endpointRepository.listActiveByOrg(orgId)).extracting(NotificationEndpoint::id).contains(endpointId);
        assertThat(endpointRepository.softDelete(endpointId, 0L, now)).isTrue();
        assertThat(endpointRepository.listActiveByOrg(orgId)).isEmpty();

        UUID ruleId = UuidV7.next();
        alertRuleRepository.insert(new BillingAlertRule(
                ruleId, orgId, null, AlertType.LOW_BALANCE, 100_000, List.of(UUID.randomUUID()),
                60, null, ToggleStatus.ACTIVE, null, 0L, now, now
        ));
        assertThat(alertRuleRepository.touchLastTriggered(ruleId, now)).isTrue();
        assertThat(alertRuleRepository.findById(ruleId).orElseThrow().lastTriggeredAt()).isNotNull();
        assertThat(alertRuleRepository.softDelete(ruleId, 0L, now)).isTrue();
        assertThat(alertRuleRepository.listActiveByOrg(orgId)).isEmpty();
    }

    @Test
    void outboxInsertsAreIdempotentAndPurgeIsAgeBound() {
        UUID eventId = UuidV7.next();
        OutboxEvent event = new OutboxEvent(
                eventId, "wallet.topup", UUID.randomUUID().toString(), "WalletToppedUp", "{}", now
        );
        assertThat(outboxEventRepository.insertIfAbsent(event)).isTrue();
        assertThat(outboxEventRepository.insertIfAbsent(event)).isFalse();

        outboxEventRepository.insertIfAbsent(new OutboxEvent(
                UuidV7.next(), "wallet.topup", UUID.randomUUID().toString(), "WalletToppedUp", "{}",
                now.minusDays(10)
        ));
        assertThat(outboxEventRepository.deleteOlderThan(now.minusDays(7))).isEqualTo(1);
        assertThat(dsl.fetchCount(dsl.selectFrom("outbox_events"))).isEqualTo(1);
    }

    private PaymentOrder order(UUID orgId, UUID userId, String idempotencyKey, String status, OffsetDateTime expiresAt) {
        return new PaymentOrder(
                UuidV7.next(), "order-" + UuidV7.next(), orgId, userId, OrderType.WALLET_TOPUP,
                PaymentChannel.STRIPE, "USD", 10_000L, BigDecimal.ONE, 10_000L, 10_000L, null, null,
                idempotencyKey, OrderStatus.valueOf(status), "PAID".equals(status) ? now : null,
                expiresAt, 0L, now, now
        );
    }

    private ResourcePack pack(UUID orgId, String modelCode, long units, OffsetDateTime expiresAt, EntitlementStatus status) {
        return new ResourcePack(
                UuidV7.next(), orgId, "pack-" + units, PackUnitType.TOKENS, List.of(modelCode),
                units, units, null, expiresAt, status, 0L, now, now
        );
    }

    private BonusGrant grant(UUID orgId, List<String> models, long micros, OffsetDateTime expiresAt) {
        return new BonusGrant(
                UuidV7.next(), orgId, GrantSource.ADMIN_GRANT, null, null, models, micros, micros,
                expiresAt, EntitlementStatus.ACTIVE, 0L, now, now
        );
    }

    private UUID seedVoucher() {
        UUID voucherId = UuidV7.next();
        voucherRepository.insert(new Voucher(
                voucherId, "Voucher " + voucherId, VoucherType.PROMO_CODE, 5_000L, null, 7, null,
                100, 1, VoucherStatus.ACTIVE, null, 0L, now, now
        ));
        return voucherId;
    }

    private UUID seedOrg() {
        UUID userId = UuidV7.next();
        UUID orgId = UuidV7.next();
        dsl.execute("INSERT INTO users (id, email, display_name, status, version)"
                        + " VALUES (?, ?, 'Repo User', 'ACTIVE', 0)",
                userId, "repo-" + userId + "@acme.dev");
        dsl.execute("INSERT INTO organizations (id, name, slug, owner_user_id, tier_id, is_personal, status, version)"
                        + " VALUES (?, 'Repo Org', ?, ?, (SELECT id FROM customer_tiers WHERE tier_code = 'STANDARD'), false, 'ACTIVE', 0)",
                orgId, "repo-" + orgId, userId);
        return orgId;
    }

    private UUID seedVirtualModel(String modelCode) {
        UUID modelId = UuidV7.next();
        dsl.execute("INSERT INTO virtual_models (id, model_code, display_name, capability_type, native_protocol,"
                        + " context_window) VALUES (?, ?, 'Repo Model', 'CHAT', 'OPENAI', 1000)",
                modelId, modelCode);
        return modelId;
    }

    private UUID ownerOf(UUID orgId) {
        return dsl.fetchOne("SELECT owner_user_id FROM organizations WHERE id = ?", orgId).get(0, UUID.class);
    }

    @Test
    void paginationReturnsStableTotals() {
        UUID orgId = seedOrg();
        UUID userId = ownerOf(orgId);
        for (int i = 0; i < 3; i++) {
            paymentOrderRepository.insert(order(orgId, userId, "idem-page-" + i + "-" + UuidV7.next(), "PENDING", now.plusHours(1)));
        }
        var page = paymentOrderRepository.listByOrg(orgId, PageQuery.of(1, 2));
        assertThat(page.total()).isEqualTo(3L);
        assertThat(page.items()).hasSize(2);
    }
}
