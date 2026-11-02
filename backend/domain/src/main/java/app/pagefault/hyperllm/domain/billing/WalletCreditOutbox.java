package app.pagefault.hyperllm.domain.billing;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.enums.OutboxAggregateType;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes the grant that a control-plane transaction has authorized.
 *
 * <p>The row is written in the caller's transaction, so "the order is paid" and "a credit is owed"
 * commit together or not at all. The event id doubles as the batch id: a redelivery inserts the
 * same ledger row, the same pack or grant row and the same Redis marker.
 *
 * <p>Cash and bonus credits reserve capacity on the wallet row before the row is written. A pack
 * has no wallet balance; its units are bounded so every mirrored counter stays an exact integer.
 */
@Component
@RequiredArgsConstructor
public class WalletCreditOutbox {

    // Keep projected balances within the exact integer range used by the Redis reservation scripts.
    private static final long MAX_MIRRORED_MICROS = (1L << 53) - 1L;

    private final OutboxEventRepository outbox;
    private final WalletRepository wallets;
    private final PaymentOrderRepository paymentOrders;
    private final ObjectMapper mapper;

    /** Authorizes a credit in the same transaction as its originating operation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BillingMoneyEvent append(UUID orgId, String key, long cash, long bonus,
                                    BillingMoneyEvent.CreditSource source, String description, OffsetDateTime at) {
        if (cash > 0L && bonus == 0L) {
            return appendCash(orgId, key, cash, source, null, description, at);
        }
        if (bonus > 0L && cash == 0L) {
            return appendBonus(orgId, key, bonus, source, null, null, null, null, description, at);
        }
        throw new IllegalArgumentException("A credit must fund exactly one balance");
    }

    /** Credits cash, e.g. a verified top-up payment or an operator adjustment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BillingMoneyEvent appendCash(UUID orgId, String key, long amount,
                                        BillingMoneyEvent.CreditSource source, UUID orderId,
                                        String description, OffsetDateTime at) {
        return appendCash(orgId, key, amount, source, orderId, null, description, at);
    }

    /**
     * @param reservedOrderId the PENDING order whose own reservation must not be counted twice when
     *                        its verified payment is being credited
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BillingMoneyEvent appendCash(UUID orgId, String key, long amount,
                                        BillingMoneyEvent.CreditSource source, UUID orderId,
                                        UUID reservedOrderId, String description, OffsetDateTime at) {
        requirePositive(amount);
        Objects.requireNonNull(source, "credit source");
        Wallet wallet = lockWallet(orgId);
        ensureCapacity(orgId, wallet, true, amount, paymentOrders.sumPendingTopupCredits(orgId, reservedOrderId));
        return authorize(orgId, key, amount, 0L, 0L, source, null, orderId, description, at);
    }

    /**
     * Credits bonus money. {@code applicableModels == null} means every model; an expiry is
     * mandatory for a voucher and optional for an operator grant.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BillingMoneyEvent appendBonus(UUID orgId, String key, long amount,
                                         BillingMoneyEvent.CreditSource source,
                                         List<String> applicableModels, OffsetDateTime expiresAt,
                                         UUID voucherId, UUID redemptionCodeId,
                                         String description, OffsetDateTime at) {
        requirePositive(amount);
        Objects.requireNonNull(source, "credit source");
        if (source == BillingMoneyEvent.CreditSource.PAYMENT) {
            throw new IllegalArgumentException("A payment credit is cash, not bonus");
        }
        requireGrantTerms(applicableModels, expiresAt, at);
        Wallet wallet = lockWallet(orgId);
        ensureCapacity(orgId, wallet, false, amount, 0L);
        UUID id = UuidV7.next();
        BillingMoneyEvent.Grant grant = BillingMoneyEvent.Grant.bonus(
                id, amount, applicableModels, expiresAt, voucherId, redemptionCodeId);
        return authorize(orgId, key, 0L, amount, 0L, source, grant, null, description, at, id);
    }

    /** Grants one resource pack batch; the payment order is the accounting reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BillingMoneyEvent appendPack(UUID orgId, String key, String packName, PackUnitType unitType,
                                        long units, List<String> applicableModels, OffsetDateTime expiresAt,
                                        UUID orderId, String description, OffsetDateTime at) {
        requirePositive(units);
        if (units > MAX_MIRRORED_MICROS) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "Pack units exceed the mirror range");
        }
        Objects.requireNonNull(unitType, "unit type");
        if (applicableModels == null || applicableModels.isEmpty()) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "A pack must name at least one model");
        }
        requireGrantTerms(applicableModels, expiresAt, at);
        if (expiresAt == null) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "A pack must expire");
        }
        lockWallet(orgId);
        UUID id = UuidV7.next();
        BillingMoneyEvent.Grant grant = BillingMoneyEvent.Grant.pack(
                id, packName, unitType.name(), units, applicableModels, expiresAt);
        return authorize(orgId, key, 0L, 0L, units, BillingMoneyEvent.CreditSource.PAYMENT,
                grant, orderId, description, at, id);
    }

    private BillingMoneyEvent authorize(UUID orgId, String key, long cash, long bonus, long packUnits,
                                        BillingMoneyEvent.CreditSource source, BillingMoneyEvent.Grant grant,
                                        UUID orderId, String description, OffsetDateTime at) {
        return authorize(orgId, key, cash, bonus, packUnits, source, grant, orderId, description, at, UuidV7.next());
    }

    private BillingMoneyEvent authorize(UUID orgId, String key, long cash, long bonus, long packUnits,
                                        BillingMoneyEvent.CreditSource source, BillingMoneyEvent.Grant grant,
                                        UUID orderId, String description, OffsetDateTime at, UUID id) {
        var type = switch (grant == null ? BillingMoneyEvent.GrantType.BONUS : grant.type()) {
            case BONUS -> cash > 0L ? BillingMoneyEvent.EventType.RECHARGE : BillingMoneyEvent.EventType.BONUS_GRANT;
            case PACK -> BillingMoneyEvent.EventType.PACK_GRANT;
        };
        OffsetDateTime occurredAt = at.truncatedTo(ChronoUnit.MICROS);
        var event = new BillingMoneyEvent(id, type, occurredAt, orgId, null, null, key, cash, bonus,
                packUnits, 0L, List.of(), null, null, false, description, source, grant, orderId);
        try {
            if (!outbox.insertIfAbsent(new OutboxEvent(id, OutboxAggregateType.BILLING.topicSuffix(),
                    orgId.toString(), type.name(), mapper.writeValueAsString(event), occurredAt))) {
                throw new IllegalStateException("Credit event already exists " + id);
            }
            return event;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode credit event " + id, ex);
        }
    }

    private Wallet lockWallet(UUID orgId) {
        return wallets.findByOrgIdForUpdate(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND));
    }

    private void ensureCapacity(UUID orgId, Wallet wallet, boolean cash, long amount, long pendingOrders) {
        var type = cash ? BillingMoneyEvent.EventType.RECHARGE : BillingMoneyEvent.EventType.BONUS_GRANT;
        long current = cash ? wallet.cashBalanceMicros() : wallet.bonusBalanceMicros();
        long credited = cash ? wallet.totalRechargedMicros() : wallet.totalBonusGrantedMicros();
        long capacity = Math.min(MAX_MIRRORED_MICROS - current - pendingOrders, Long.MAX_VALUE - credited);
        if (amount > capacity || !outbox.unconfirmedCreditsFit(orgId, type, capacity - amount)) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "Credit exceeds the wallet capacity");
        }
    }

    private static void requirePositive(long amount) {
        if (amount <= 0L) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "A credit must be positive");
        }
    }

    private static void requireGrantTerms(List<String> models, OffsetDateTime expiresAt, OffsetDateTime at) {
        if (models != null) {
            if (models.isEmpty()) {
                throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "Model scope must not be empty");
            }
            if (models.stream().anyMatch(model -> model == null || model.isBlank())) {
                throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "Model scope contains a blank code");
            }
        }
        if (expiresAt != null && !expiresAt.isAfter(at)) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "A grant must expire in the future");
        }
    }
}