package app.pagefault.hyperllm.domain.billing;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.model.enums.OutboxAggregateType;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
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

@Component
@RequiredArgsConstructor
public class WalletCreditOutbox {

    // Keep projected balances within the exact integer range used by the Redis reservation scripts.
    private static final long MAX_MIRRORED_MICROS = (1L << 53) - 1L;

    private final OutboxEventRepository outbox;
    private final WalletRepository wallets;
    private final ObjectMapper mapper;

    /** Authorizes a credit in the same transaction as its originating operation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BillingMoneyEvent append(UUID orgId, String key, long cash, long bonus,
                                    BillingMoneyEvent.CreditSource source, String description, OffsetDateTime at) {
        if (!(cash > 0L && bonus == 0L || bonus > 0L && cash == 0L)) {
            throw new IllegalArgumentException("A credit must fund exactly one balance");
        }
        Objects.requireNonNull(source, "credit source");
        boolean recharge = cash > 0L;
        var type = recharge ? BillingMoneyEvent.EventType.RECHARGE : BillingMoneyEvent.EventType.BONUS_GRANT;
        var wallet = wallets.findByOrgIdForUpdate(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND));
        long current = recharge ? wallet.cashBalanceMicros() : wallet.bonusBalanceMicros();
        long credited = recharge ? wallet.totalRechargedMicros() : wallet.totalBonusGrantedMicros();
        long capacity = Math.min(MAX_MIRRORED_MICROS - current, Long.MAX_VALUE - credited);
        long amount = recharge ? cash : bonus;
        if (amount > capacity || !outbox.unconfirmedCreditsFit(orgId, type, capacity - amount)) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "Credit exceeds the wallet capacity");
        }
        UUID id = UuidV7.next();
        OffsetDateTime occurredAt = at.truncatedTo(ChronoUnit.MICROS);
        var event = new BillingMoneyEvent(id, type, occurredAt, orgId, null, null, key, cash, bonus,
                0L, 0L, List.of(), null, null, false, description, source);
        try {
            if (!outbox.insertIfAbsent(new OutboxEvent(id, OutboxAggregateType.BILLING.topicSuffix(), orgId.toString(), type.name(),
                    mapper.writeValueAsString(event), occurredAt))) {
                throw new IllegalStateException("Credit event already exists " + id);
            }
            return event;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode credit event " + id, ex);
        }
    }
}
