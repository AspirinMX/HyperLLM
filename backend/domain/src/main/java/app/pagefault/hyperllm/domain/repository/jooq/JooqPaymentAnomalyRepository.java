package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.PaymentAnomaliesRecord;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyKind;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyStatus;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentAnomaly;
import app.pagefault.hyperllm.domain.repository.PaymentAnomalyRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.PAYMENT_ANOMALIES;

@Repository
@RequiredArgsConstructor
public class JooqPaymentAnomalyRepository implements PaymentAnomalyRepository {

    private final DSLContext dsl;

    @Override
    public void insert(PaymentAnomaly anomaly) {
        dsl.insertInto(PAYMENT_ANOMALIES)
                .set(PAYMENT_ANOMALIES.ID, anomaly.id())
                .set(PAYMENT_ANOMALIES.ORG_ID, anomaly.orgId())
                .set(PAYMENT_ANOMALIES.ORDER_ID, anomaly.orderId())
                .set(PAYMENT_ANOMALIES.PAYMENT_CHANNEL, anomaly.paymentChannel().name())
                .set(PAYMENT_ANOMALIES.KIND, anomaly.kind().name())
                .set(PAYMENT_ANOMALIES.DETAIL_JSON, PgJooqSupport.toJsonb(anomaly.detailJson()))
                .set(PAYMENT_ANOMALIES.STATUS, anomaly.status().name())
                .set(PAYMENT_ANOMALIES.CREATED_AT, anomaly.createdAt())
                .set(PAYMENT_ANOMALIES.UPDATED_AT, anomaly.updatedAt())
                .execute();
    }

    @Override
    public PageResponse<PaymentAnomaly> list(PaymentAnomalyStatus status, PageQuery pageQuery) {
        Condition condition = status == null
                ? DSL.trueCondition()
                : PAYMENT_ANOMALIES.STATUS.eq(status.name());
        return PgJooqSupport.fetchPage(dsl, PAYMENT_ANOMALIES, condition,
                PAYMENT_ANOMALIES.CREATED_AT.desc(), pageQuery, this::toDomain);
    }

    @Override
    public boolean resolve(UUID id, UUID adminId, PaymentAnomalyStatus status, String resolution,
                           OffsetDateTime resolvedAt) {
        return dsl.update(PAYMENT_ANOMALIES)
                .set(PAYMENT_ANOMALIES.STATUS, status.name())
                .set(PAYMENT_ANOMALIES.RESOLVED_BY_ADMIN_ID, adminId)
                .set(PAYMENT_ANOMALIES.RESOLVED_AT, resolvedAt)
                .set(PAYMENT_ANOMALIES.RESOLUTION, resolution)
                .set(PAYMENT_ANOMALIES.UPDATED_AT, resolvedAt)
                .where(PAYMENT_ANOMALIES.ID.eq(id))
                .and(PAYMENT_ANOMALIES.STATUS.eq(PaymentAnomalyStatus.OPEN.name()))
                .execute() == 1;
    }

    private PaymentAnomaly toDomain(PaymentAnomaliesRecord r) {
        return new PaymentAnomaly(
                r.getId(),
                r.getOrgId(),
                r.getOrderId(),
                PgJooqSupport.toEnum(PaymentChannel.class, r.getPaymentChannel()),
                PgJooqSupport.toEnum(PaymentAnomalyKind.class, r.getKind()),
                PgJooqSupport.fromJsonb(r.getDetailJson()),
                PgJooqSupport.toEnum(PaymentAnomalyStatus.class, r.getStatus()),
                r.getResolvedByAdminId(),
                r.getResolvedAt(),
                r.getResolution(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
