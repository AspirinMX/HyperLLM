package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.PaymentOrdersRecord;
import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.OrderType;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.PAYMENT_ORDERS;

@Repository
@RequiredArgsConstructor
public class JooqPaymentOrderRepository implements PaymentOrderRepository {

    private final DSLContext dsl;

    @Override
    public void insert(PaymentOrder order) {
        dsl.insertInto(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.ID, order.id())
                .set(PAYMENT_ORDERS.ORDER_NO, order.orderNo())
                .set(PAYMENT_ORDERS.ORG_ID, order.orgId())
                .set(PAYMENT_ORDERS.CREATED_BY_USER_ID, order.createdByUserId())
                .set(PAYMENT_ORDERS.ORDER_TYPE, order.orderType().name())
                .set(PAYMENT_ORDERS.PAYMENT_CHANNEL, order.paymentChannel().name())
                .set(PAYMENT_ORDERS.PAY_CURRENCY, order.payCurrency())
                .set(PAYMENT_ORDERS.PAY_AMOUNT_MINOR, order.payAmountMinor())
                .set(PAYMENT_ORDERS.FX_RATE, order.fxRate())
                .set(PAYMENT_ORDERS.CREDIT_MICROS, order.creditMicros())
                .set(PAYMENT_ORDERS.INVOICEABLE_AMOUNT_MINOR, order.invoiceableAmountMinor())
                .set(PAYMENT_ORDERS.PACK_SPEC_JSON, PgJooqSupport.toJsonb(order.packSpecJson()))
                .set(PAYMENT_ORDERS.EXTERNAL_TX_ID, order.externalTxId())
                .set(PAYMENT_ORDERS.IDEMPOTENCY_KEY, order.idempotencyKey())
                .set(PAYMENT_ORDERS.STATUS, order.status().name())
                .set(PAYMENT_ORDERS.PAID_AT, order.paidAt())
                .set(PAYMENT_ORDERS.EXPIRES_AT, order.expiresAt())
                .set(PAYMENT_ORDERS.VERSION, order.version())
                .set(PAYMENT_ORDERS.CREATED_AT, order.createdAt())
                .set(PAYMENT_ORDERS.UPDATED_AT, order.updatedAt())
                .execute();
    }

    @Override
    public boolean insertIfAbsent(PaymentOrder order) {
        return dsl.insertInto(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.ID, order.id())
                .set(PAYMENT_ORDERS.ORDER_NO, order.orderNo())
                .set(PAYMENT_ORDERS.ORG_ID, order.orgId())
                .set(PAYMENT_ORDERS.CREATED_BY_USER_ID, order.createdByUserId())
                .set(PAYMENT_ORDERS.ORDER_TYPE, order.orderType().name())
                .set(PAYMENT_ORDERS.PAYMENT_CHANNEL, order.paymentChannel().name())
                .set(PAYMENT_ORDERS.PAY_CURRENCY, order.payCurrency())
                .set(PAYMENT_ORDERS.PAY_AMOUNT_MINOR, order.payAmountMinor())
                .set(PAYMENT_ORDERS.FX_RATE, order.fxRate())
                .set(PAYMENT_ORDERS.CREDIT_MICROS, order.creditMicros())
                .set(PAYMENT_ORDERS.INVOICEABLE_AMOUNT_MINOR, order.invoiceableAmountMinor())
                .set(PAYMENT_ORDERS.PACK_SPEC_JSON, PgJooqSupport.toJsonb(order.packSpecJson()))
                .set(PAYMENT_ORDERS.EXTERNAL_TX_ID, order.externalTxId())
                .set(PAYMENT_ORDERS.IDEMPOTENCY_KEY, order.idempotencyKey())
                .set(PAYMENT_ORDERS.STATUS, order.status().name())
                .set(PAYMENT_ORDERS.PAID_AT, order.paidAt())
                .set(PAYMENT_ORDERS.EXPIRES_AT, order.expiresAt())
                .set(PAYMENT_ORDERS.VERSION, order.version())
                .set(PAYMENT_ORDERS.CREATED_AT, order.createdAt())
                .set(PAYMENT_ORDERS.UPDATED_AT, order.updatedAt())
                .onConflictDoNothing()
                .execute() == 1;
    }

    @Override
    public Optional<PaymentOrder> findById(UUID id) {
        return dsl.selectFrom(PAYMENT_ORDERS)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(PAYMENT_ORDERS)
                .where(PAYMENT_ORDERS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<PaymentOrder> findByOrderNo(String orderNo) {
        return dsl.selectFrom(PAYMENT_ORDERS)
                .where(PAYMENT_ORDERS.ORDER_NO.eq(orderNo))
                .fetchOptional(this::toDomain);
    }

    @Override
    public PageResponse<PaymentOrder> listByOrg(UUID orgId, PageQuery pageQuery) {
        return PgJooqSupport.fetchPage(
                dsl, PAYMENT_ORDERS, PAYMENT_ORDERS.ORG_ID.eq(orgId),
                PAYMENT_ORDERS.CREATED_AT.desc(), pageQuery, this::toDomain
        );
    }

    @Override
    public List<PaymentOrder> listExpiredPending(OffsetDateTime now, int limit) {
        return dsl.selectFrom(PAYMENT_ORDERS)
                .where(PAYMENT_ORDERS.STATUS.eq(OrderStatus.PENDING.name()))
                .and(PAYMENT_ORDERS.EXPIRES_AT.lt(now))
                .orderBy(PAYMENT_ORDERS.EXPIRES_AT.asc())
                .limit(limit)
                .fetch(this::toDomain);
    }

    @Override
    public boolean updateStatus(
            UUID id,
            OrderStatus status,
            OffsetDateTime paidAt,
            String externalTxId,
            long expectedVersion,
            OffsetDateTime updatedAt
    ) {
        int updated = dsl.update(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.STATUS, status.name())
                .set(PAYMENT_ORDERS.PAID_AT, paidAt)
                .set(PAYMENT_ORDERS.EXTERNAL_TX_ID, externalTxId)
                .set(PAYMENT_ORDERS.VERSION, PAYMENT_ORDERS.VERSION.plus(1))
                .set(PAYMENT_ORDERS.UPDATED_AT, updatedAt)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .and(PAYMENT_ORDERS.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    private PaymentOrder toDomain(PaymentOrdersRecord r) {
        return new PaymentOrder(
                r.getId(),
                r.getOrderNo(),
                r.getOrgId(),
                r.getCreatedByUserId(),
                PgJooqSupport.toEnum(OrderType.class, r.getOrderType()),
                PgJooqSupport.toEnum(PaymentChannel.class, r.getPaymentChannel()),
                r.getPayCurrency(),
                r.getPayAmountMinor(),
                r.getFxRate(),
                r.getCreditMicros(),
                r.getInvoiceableAmountMinor(),
                PgJooqSupport.fromJsonb(r.getPackSpecJson()),
                r.getExternalTxId(),
                r.getIdempotencyKey(),
                PgJooqSupport.toEnum(OrderStatus.class, r.getStatus()),
                r.getPaidAt(),
                r.getExpiresAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
