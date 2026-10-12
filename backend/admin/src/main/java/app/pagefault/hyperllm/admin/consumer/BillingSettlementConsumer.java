package app.pagefault.hyperllm.admin.consumer;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingSettlementEvent;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Consumes asynchronous usage settlement events from Kafka and persists them idempotently
 * into monthly-partitioned {@code wallet_ledger} and {@code wallets}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingSettlementConsumer {

    private final ObjectMapper objectMapper;
    private final WalletLedgerRepository walletLedgerRepository;
    private final WalletRepository walletRepository;

    @KafkaListener(
            topics = KafkaTopics.LLM_BILLING_SETTLEMENT,
            groupId = "${spring.kafka.consumer.group-id:hyperllm-admin-billing}"
    )
    public void onMessage(String payload, Acknowledgment acknowledgment) {
        try {
            BillingSettlementEvent event = objectMapper.readValue(payload, BillingSettlementEvent.class);
            ingestIdempotent(event);
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        } catch (Exception ex) {
            log.error("Failed processing billing settlement event payload={}", payload, ex);
            throw new IllegalStateException("Billing settlement processing failed", ex);
        }
    }

    @Transactional
    public boolean ingestIdempotent(BillingSettlementEvent event) {
        boolean inserted = walletLedgerRepository.insertIdempotent(new WalletLedgerEntry(
                UuidV7.next(),
                event.orgId(),
                event.projectId(),
                event.apiKeyId(),
                LedgerType.CONSUME,
                event.idempotencyKey(),
                event.cashDeltaMicros(),
                event.bonusDeltaMicros(),
                event.packDeltaUnits(),
                null,
                null,
                null,
                event.requestId(),
                null,
                null,
                event.description(),
                event.eventCreatedAt()
        ));
        if (!inserted) {
            return false;
        }
        long consumedCash = Math.max(0L, -event.cashDeltaMicros());
        long consumedBonus = Math.max(0L, -event.bonusDeltaMicros());
        walletRepository.applySettlementDelta(
                event.orgId(),
                event.cashDeltaMicros(),
                event.bonusDeltaMicros(),
                consumedCash,
                consumedBonus,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return true;
    }
}
