package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyKind;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyStatus;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentAnomaly;
import app.pagefault.hyperllm.domain.repository.PaymentAnomalyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Writes the anomaly in its own transaction, so a rolled back confirmation still leaves evidence. */
@Component
@RequiredArgsConstructor
public class PaymentAnomalyWriter {

    private final PaymentAnomalyRepository anomalies;
    private final ObjectMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orgId, UUID orderId, PaymentChannel channel,
                       PaymentAnomalyKind kind, Map<String, ?> detail) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String json;
        try {
            json = mapper.writeValueAsString(detail);
        } catch (Exception ex) {
            json = "{\"error\":\"unencodable\"}";
        }
        anomalies.insert(new PaymentAnomaly(UuidV7.next(), orgId, orderId, channel, kind, json,
                PaymentAnomalyStatus.OPEN, null, null, null, now, now));
    }
}
