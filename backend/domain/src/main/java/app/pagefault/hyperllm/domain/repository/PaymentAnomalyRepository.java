package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyStatus;
import app.pagefault.hyperllm.domain.model.payment.PaymentAnomaly;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface PaymentAnomalyRepository {

    void insert(PaymentAnomaly anomaly);

    PageResponse<PaymentAnomaly> list(PaymentAnomalyStatus status, PageQuery pageQuery);

    boolean resolve(UUID id, UUID adminId, PaymentAnomalyStatus status, String resolution,
                    OffsetDateTime resolvedAt);
}
