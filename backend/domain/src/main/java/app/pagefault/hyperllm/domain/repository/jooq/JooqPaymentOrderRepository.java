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
import org.jooq.impl.DSL;
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
        insertStatement(order, false).execute();
    }

    @Override
    public boolean insertIfAbsent(PaymentOrder order) {
        return insertStatement(order, true).execute() == 1;
    }

    @Override
    public Optional<PaymentOrder> findById(UUID id) {
        return dsl.selectFrom(PAYMENT_ORDERS)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<PaymentOrder> findByIdForUpdate(UUID id) {
        return dsl.selectFrom(PAYMENT_ORDERS)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .forUpdate()
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
    public long sumPendingTopupCredits(UUID orgId) {
        return sumPendingTopupCredits(orgId, null);
    }

    @Override
    public long sumPendingTopupCredits(UUID orgId, UUID excludeOrderId) {
        var condition = PAYMENT_ORDERS.ORG_ID.eq(orgId)
                .and(PAYMENT_ORDERS.ORDER_TYPE.eq(OrderType.WALLET_TOPUP.name()))
                .and(PAYMENT_ORDERS.STATUS.eq(OrderStatus.PENDING.name()));
        if (excludeOrderId != null) {
            condition = condition.and(PAYMENT_ORDERS.ID.ne(excludeOrderId));
        }
        Long sum = dsl.select(DSL.coalesce(DSL.sum(PAYMENT_ORDERS.CREDIT_MICROS), DSL.inline(0L)))
                .from(PAYMENT_ORDERS)
                .where(condition)
                .fetchOne(0, Long.class);
        return sum == null ? 0L : sum;
    }

    @Override
    public boolean updateChannelReference(UUID id, String channelRef, String channelPayload,
                                          long expectedVersion, OffsetDateTime updatedAt) {
        return dsl.update(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.CHANNEL_REF, channelRef)
                .set(PAYMENT_ORDERS.CHANNEL_PAYLOAD, PgJooqSupport.toJsonb(channelPayload))
                .set(PAYMENT_ORDERS.VERSION, PAYMENT_ORDERS.VERSION.plus(1))
                .set(PAYMENT_ORDERS.UPDATED_AT, updatedAt)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .and(PAYMENT_ORDERS.VERSION.eq(expectedVersion))
                .and(PAYMENT_ORDERS.STATUS.eq(OrderStatus.PENDING.name()))
                .execute() == 1;
    }

    @Override
    public boolean markPaid(UUID id, String externalTxId, OffsetDateTime paidAt, UUID fulfillmentEventId,
                            long expectedVersion, OffsetDateTime updatedAt) {
        return dsl.update(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.STATUS, OrderStatus.PAID.name())
                .set(PAYMENT_ORDERS.PAID_AT, paidAt)
                .set(PAYMENT_ORDERS.EXTERNAL_TX_ID, externalTxId)
                .set(PAYMENT_ORDERS.FULFILLMENT_EVENT_ID, fulfillmentEventId)
                .set(PAYMENT_ORDERS.FULFILLED_AT, paidAt)
                .set(PAYMENT_ORDERS.CLOSED_AT, (OffsetDateTime) null)
                .set(PAYMENT_ORDERS.FAILURE_CODE, (String) null)
                .set(PAYMENT_ORDERS.FAILURE_DETAIL, (String) null)
                .set(PAYMENT_ORDERS.VERSION, PAYMENT_ORDERS.VERSION.plus(1))
                .set(PAYMENT_ORDERS.UPDATED_AT, updatedAt)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .and(PAYMENT_ORDERS.VERSION.eq(expectedVersion))
                // A verified payment that arrives after the local close still wins: the channel's
                // final state, not the local deadline, decides whether money moved.
                .and(PAYMENT_ORDERS.STATUS.notIn(OrderStatus.PAID.name(), OrderStatus.REFUNDED.name()))
                .execute() == 1;
    }

    @Override
    public boolean markClosed(UUID id, OffsetDateTime closedAt, long expectedVersion, OffsetDateTime updatedAt) {
        return dsl.update(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.STATUS, OrderStatus.CLOSED.name())
                .set(PAYMENT_ORDERS.CLOSED_AT, closedAt)
                .set(PAYMENT_ORDERS.VERSION, PAYMENT_ORDERS.VERSION.plus(1))
                .set(PAYMENT_ORDERS.UPDATED_AT, updatedAt)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .and(PAYMENT_ORDERS.VERSION.eq(expectedVersion))
                .and(PAYMENT_ORDERS.STATUS.eq(OrderStatus.PENDING.name()))
                .execute() == 1;
    }

    @Override
    public boolean markFailed(UUID id, OffsetDateTime closedAt, String failureCode, String failureDetail,
                              long expectedVersion, OffsetDateTime updatedAt) {
        return dsl.update(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.STATUS, OrderStatus.FAILED.name())
                .set(PAYMENT_ORDERS.CLOSED_AT, closedAt)
                .set(PAYMENT_ORDERS.FAILURE_CODE, failureCode)
                .set(PAYMENT_ORDERS.FAILURE_DETAIL, failureDetail)
                .set(PAYMENT_ORDERS.VERSION, PAYMENT_ORDERS.VERSION.plus(1))
                .set(PAYMENT_ORDERS.UPDATED_AT, updatedAt)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .and(PAYMENT_ORDERS.VERSION.eq(expectedVersion))
                .and(PAYMENT_ORDERS.STATUS.eq(OrderStatus.PENDING.name()))
                .execute() == 1;
    }

    @Override
    public boolean markRefunded(UUID id, long refundedAmountMinor, OffsetDateTime refundedAt,
                                long expectedVersion, OffsetDateTime updatedAt) {
        return dsl.update(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.STATUS, OrderStatus.REFUNDED.name())
                .set(PAYMENT_ORDERS.REFUNDED_AT, refundedAt)
                .set(PAYMENT_ORDERS.REFUNDED_AMOUNT_MINOR, refundedAmountMinor)
                .set(PAYMENT_ORDERS.VERSION, PAYMENT_ORDERS.VERSION.plus(1))
                .set(PAYMENT_ORDERS.UPDATED_AT, updatedAt)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .and(PAYMENT_ORDERS.VERSION.eq(expectedVersion))
                .and(PAYMENT_ORDERS.STATUS.eq(OrderStatus.PAID.name()))
                .execute() == 1;
    }

    @Override
    public boolean markFulfilled(UUID id, OffsetDateTime fulfilledAt, long expectedVersion, OffsetDateTime updatedAt) {
        return dsl.update(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.FULFILLED_AT, fulfilledAt)
                .set(PAYMENT_ORDERS.VERSION, PAYMENT_ORDERS.VERSION.plus(1))
                .set(PAYMENT_ORDERS.UPDATED_AT, updatedAt)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .and(PAYMENT_ORDERS.VERSION.eq(expectedVersion))
                .and(PAYMENT_ORDERS.STATUS.eq(OrderStatus.PAID.name()))
                .execute() == 1;
    }

    @Override
    public boolean updateStatus(UUID id, OrderStatus status, OffsetDateTime paidAt, String externalTxId,
                                long expectedVersion, OffsetDateTime updatedAt) {
        return dsl.update(PAYMENT_ORDERS)
                .set(PAYMENT_ORDERS.STATUS, status.name())
                .set(PAYMENT_ORDERS.PAID_AT, paidAt)
                .set(PAYMENT_ORDERS.EXTERNAL_TX_ID, externalTxId)
                .set(PAYMENT_ORDERS.VERSION, PAYMENT_ORDERS.VERSION.plus(1))
                .set(PAYMENT_ORDERS.UPDATED_AT, updatedAt)
                .where(PAYMENT_ORDERS.ID.eq(id))
                .and(PAYMENT_ORDERS.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    private org.jooq.Query insertStatement(PaymentOrder order, boolean ignoreDuplicate) {
        var query = dsl.insertInto(PAYMENT_ORDERS)
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
                .set(PAYMENT_ORDERS.PACK_SKU_ID, order.packSkuId())
                .set(PAYMENT_ORDERS.PACK_SPEC_JSON, PgJooqSupport.toJsonb(order.packSpecJson()))
                .set(PAYMENT_ORDERS.REQUEST_FINGERPRINT, order.requestFingerprint())
                .set(PAYMENT_ORDERS.CHANNEL_REF, order.channelRef())
                .set(PAYMENT_ORDERS.CHANNEL_PAYLOAD, PgJooqSupport.toJsonb(order.channelPayload()))
                .set(PAYMENT_ORDERS.EXTERNAL_TX_ID, order.externalTxId())
                .set(PAYMENT_ORDERS.IDEMPOTENCY_KEY, order.idempotencyKey())
                .set(PAYMENT_ORDERS.STATUS, order.status().name())
                .set(PAYMENT_ORDERS.FULFILLMENT_EVENT_ID, order.fulfillmentEventId())
                .set(PAYMENT_ORDERS.FULFILLED_AT, order.fulfilledAt())
                .set(PAYMENT_ORDERS.PAID_AT, order.paidAt())
                .set(PAYMENT_ORDERS.CLOSED_AT, order.closedAt())
                .set(PAYMENT_ORDERS.FAILURE_CODE, order.failureCode())
                .set(PAYMENT_ORDERS.FAILURE_DETAIL, order.failureDetail())
                .set(PAYMENT_ORDERS.REFUNDED_AT, order.refundedAt())
                .set(PAYMENT_ORDERS.REFUNDED_AMOUNT_MINOR, order.refundedAmountMinor())
                .set(PAYMENT_ORDERS.EXPIRES_AT, order.expiresAt())
                .set(PAYMENT_ORDERS.VERSION, order.version())
                .set(PAYMENT_ORDERS.CREATED_AT, order.createdAt())
                .set(PAYMENT_ORDERS.UPDATED_AT, order.updatedAt());
        return ignoreDuplicate ? query.onConflictDoNothing() : query;
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
                r.getPackSkuId(),
                PgJooqSupport.fromJsonb(r.getPackSpecJson()),
                r.getRequestFingerprint(),
                r.getChannelRef(),
                PgJooqSupport.fromJsonb(r.getChannelPayload()),
                r.getExternalTxId(),
                r.getIdempotencyKey(),
                PgJooqSupport.toEnum(OrderStatus.class, r.getStatus()),
                r.getFulfillmentEventId(),
                r.getFulfilledAt(),
                r.getPaidAt(),
                r.getClosedAt(),
                r.getFailureCode(),
                r.getFailureDetail(),
                r.getRefundedAt(),
                r.getRefundedAmountMinor(),
                r.getExpiresAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}