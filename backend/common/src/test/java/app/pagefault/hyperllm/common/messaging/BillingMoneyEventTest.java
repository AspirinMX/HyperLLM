package app.pagefault.hyperllm.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillingMoneyEventTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void survivesAJsonRoundTripWithEveryLegKind() {
        UUID eventId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        BillingMoneyEvent event = new BillingMoneyEvent(
                eventId,
                BillingMoneyEvent.EventType.CONSUME,
                OffsetDateTime.of(2026, 10, 31, 12, 0, 0, 0, ZoneOffset.UTC),
                orgId,
                UUID.randomUUID(),
                null,
                "req:r-1",
                -300L,
                -200L,
                -10L,
                50L,
                List.of(
                        BillingMoneyEvent.Leg.pack(packId, 10L, 500L),
                        BillingMoneyEvent.Leg.bonus(grantId, 200L),
                        BillingMoneyEvent.Leg.virtualBonus(400L),
                        BillingMoneyEvent.Leg.cash(300L)),
                "{\"v\":1}",
                holdId,
                true,
                "gpt-4o");

        String json = write(event);
        BillingMoneyEvent decoded = read(json);

        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.legs()).hasSize(4);
        assertThat(decoded.legs().get(2).virtual()).isTrue();
        assertThat(decoded.legs().get(2).batchId()).isNull();
        assertThat(decoded.settledAfterReclaim()).isTrue();
    }

    @Test
    void treatsAMissingLegListAsEmptyRatherThanNull() {
        BillingMoneyEvent event = new BillingMoneyEvent(
                UUID.randomUUID(), BillingMoneyEvent.EventType.ADJUST, OffsetDateTime.now(ZoneOffset.UTC),
                UUID.randomUUID(), null, null, "adj-1", 0L, 0L, 0L, 0L, null, null, null, false,
                "adjustment");

        assertThat(event.legs()).isEmpty();
    }

    private String write(BillingMoneyEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private BillingMoneyEvent read(String json) {
        try {
            return mapper.readValue(json, BillingMoneyEvent.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
