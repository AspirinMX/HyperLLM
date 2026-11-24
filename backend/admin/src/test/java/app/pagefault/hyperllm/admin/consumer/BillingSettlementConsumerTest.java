package app.pagefault.hyperllm.admin.consumer;

import app.pagefault.hyperllm.admin.maintenance.SettlementWorklist;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingSettlementConsumerTest {

    private ObjectMapper objectMapper;
    private LedgerFolder ledgerFolder;
    private OutboxEventRepository outboxEventRepository;
    private SettlementWorklist worklist;
    private BillingSettlementConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ledgerFolder = mock(LedgerFolder.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        worklist = mock(SettlementWorklist.class);
        consumer = new BillingSettlementConsumer(objectMapper, ledgerFolder, outboxEventRepository, worklist);
    }

    @Test
    void unfoldsPlainBillingEventJson() {
        UUID eventId = UUID.fromString("01a20dda-cf95-7bf4-9034-da0d77b9a58e");
        UUID orgId = UUID.fromString("01a20dda-cdec-7d0f-89b0-b374335afeda");
        String raw = """
                {
                  "eventId": "%s",
                  "eventType": "BONUS_GRANT",
                  "occurredAt": "2026-11-24T13:59:33.072415Z",
                  "orgId": "%s",
                  "idempotencyKey": "signup-bonus:%s",
                  "cashDeltaMicros": 0,
                  "bonusDeltaMicros": 5000000,
                  "packDeltaUnits": 0,
                  "shortfallMicros": 0,
                  "settledAfterReclaim": false,
                  "description": "Welcome signup bonus",
                  "creditSource": "SIGNUP",
                  "budgetChargeMicros": 0
                }
                """.formatted(eventId, orgId, orgId);

        when(ledgerFolder.fold(any())).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onMessage(raw, ack);

        verify(ledgerFolder).fold(argThat(e ->
                eventId.equals(e.eventId())
                        && orgId.equals(e.orgId())
                        && e.eventType() == BillingMoneyEvent.EventType.BONUS_GRANT
                        && e.bonusDeltaMicros() == 5_000_000L));
        verify(outboxEventRepository).advanceWatermark(eventId);
        verify(ack).acknowledge();
    }

    @Test
    void unfoldsDebeziumConnectSchemaPayloadEnvelope() {
        UUID eventId = UUID.fromString("01a20dda-cf95-7bf4-9034-da0d77b9a58e");
        UUID orgId = UUID.fromString("01a20dda-cdec-7d0f-89b0-b374335afeda");
        String envelope = """
                {
                  "schema": {"type": "struct", "name": "payload", "fields": []},
                  "payload": {
                    "grant": {"type": "BONUS", "units": 0, "micros": 5000000, "batchId": "%s"},
                    "orgId": "%s",
                    "eventId": "%s",
                    "eventType": "BONUS_GRANT",
                    "occurredAt": "2026-11-24T13:59:33.072415Z",
                    "description": "Welcome signup bonus",
                    "creditSource": "SIGNUP",
                    "idempotencyKey": "signup-bonus:%s",
                    "packDeltaUnits": 0,
                    "cashDeltaMicros": 0,
                    "shortfallMicros": 0,
                    "bonusDeltaMicros": 5000000,
                    "budgetChargeMicros": 0,
                    "settledAfterReclaim": false
                  }
                }
                """.formatted(eventId, orgId, eventId, orgId);

        when(ledgerFolder.fold(any())).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onMessage(envelope, ack);

        verify(ledgerFolder).fold(argThat(e ->
                eventId.equals(e.eventId())
                        && orgId.equals(e.orgId())
                        && e.grant() != null
                        && e.grant().micros() == 5_000_000L));
        verify(outboxEventRepository).advanceWatermark(eventId);
        verify(ack).acknowledge();
    }

    @Test
    void unfoldsStringifiedPayloadEnvelope() throws Exception {
        UUID eventId = UUID.fromString("01a20dda-cf95-7bf4-9034-da0d77b9a58e");
        UUID orgId = UUID.fromString("01a20dda-cdec-7d0f-89b0-b374335afeda");
        String inner = """
                {"eventId":"%s","orgId":"%s","eventType":"RECHARGE","occurredAt":"2026-11-24T13:59:33Z","cashDeltaMicros":1000000}
                """.formatted(eventId, orgId).trim();
        String envelope = "{\"schema\":{\"type\":\"string\"},\"payload\":" + objectMapper.writeValueAsString(inner) + "}";

        when(ledgerFolder.fold(any())).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onMessage(envelope, ack);

        verify(ledgerFolder).fold(argThat(e ->
                eventId.equals(e.eventId())
                        && orgId.equals(e.orgId())
                        && e.eventType() == BillingMoneyEvent.EventType.RECHARGE
                        && e.cashDeltaMicros() == 1_000_000L));
        verify(ack).acknowledge();
    }

    @Test
    void rejectsEventMissingRequiredFieldsBeforeFolding() {
        Acknowledgment ack = mock(Acknowledgment.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> consumer.onMessage("{\"schema\":{},\"payload\":{}}", ack));

        assertEquals("Billing event cannot be parsed", ex.getMessage());
        verify(ledgerFolder, never()).fold(any());
        verify(ack, never()).acknowledge();
    }
}
