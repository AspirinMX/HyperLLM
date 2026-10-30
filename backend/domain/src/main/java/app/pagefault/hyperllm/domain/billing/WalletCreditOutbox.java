package app.pagefault.hyperllm.domain.billing;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.model.enums.OutboxAggregateType;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WalletCreditOutbox {

    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;

    /** Authorizes a credit in the same transaction as its originating operation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BillingMoneyEvent append(UUID orgId, String key, long cash, long bonus,
                                    BillingMoneyEvent.CreditSource source, String description, OffsetDateTime at) {
        if (!(cash > 0L && bonus == 0L || bonus > 0L && cash == 0L)) {
            throw new IllegalArgumentException("A credit must fund exactly one balance");
        }
        UUID id = UuidV7.next();
        OffsetDateTime occurredAt = at.truncatedTo(ChronoUnit.MICROS);
        var type = cash > 0L ? BillingMoneyEvent.EventType.RECHARGE : BillingMoneyEvent.EventType.BONUS_GRANT;
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
