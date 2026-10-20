package app.pagefault.hyperllm.admin.consumer;

import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes money events from the outbox connector and folds them into the ledger.
 *
 * <p>The listener itself holds no transaction: folding lives in {@link LedgerFolder}, so the
 * transactional proxy is actually on the call path. Acknowledge happens only after the fold
 * committed, which keeps a crash between the two from losing the event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingSettlementConsumer {

    private final ObjectMapper objectMapper;
    private final LedgerFolder ledgerFolder;
    private final OutboxEventRepository outboxEventRepository;

    @KafkaListener(
            topics = KafkaTopics.CDC_BILLING,
            groupId = "${spring.kafka.consumer.group-id:hyperllm-admin-billing}"
    )
    public void onMessage(String payload, Acknowledgment acknowledgment) {
        BillingMoneyEvent event;
        try {
            event = objectMapper.readValue(payload, BillingMoneyEvent.class);
        } catch (Exception ex) {
            // A payload that cannot be parsed will never parse; acknowledging would drop money, so
            // the failure is surfaced and the partition stops until it is dealt with.
            log.error("Unreadable money event payload={}", payload, ex);
            throw new IllegalStateException("Billing event cannot be parsed", ex);
        }
        try {
            boolean folded = ledgerFolder.fold(event);
            if (folded) {
                outboxEventRepository.advanceWatermark(event.eventId());
            } else {
                log.debug("money event already folded eventId={}", event.eventId());
            }
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        } catch (Exception ex) {
            log.error("Failed processing money event eventId={}", event.eventId(), ex);
            throw new IllegalStateException("Billing event cannot be folded", ex);
        }
    }
}
