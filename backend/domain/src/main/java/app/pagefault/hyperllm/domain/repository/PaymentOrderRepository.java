package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository {

    void insert(PaymentOrder order);

    /**
     * Inserts unless an order with the same idempotency key (or order number) already exists.
     *
     * @return true when inserted, false when a duplicate key made the insert a no-op.
     */
    boolean insertIfAbsent(PaymentOrder order);

    Optional<PaymentOrder> findById(UUID id);

    /** Locks the order row so a confirmation and a close cannot both move it. */
    Optional<PaymentOrder> findByIdForUpdate(UUID id);

    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    PageResponse<PaymentOrder> listByOrg(UUID orgId, PageQuery pageQuery);

    /** PENDING orders past their local deadline, oldest first, for the close/query job. */
    List<PaymentOrder> listExpiredPending(OffsetDateTime now, int limit);

    /** Cash reserved by orders that are still PENDING; feeds the wallet capacity check. */
    long sumPendingTopupCredits(UUID orgId);

    /** Same reservation, minus the order that is being confirmed right now. */
    long sumPendingTopupCredits(UUID orgId, UUID excludeOrderId);

    boolean updateChannelReference(UUID id, String channelRef, String channelPayload,
                                   long expectedVersion, OffsetDateTime updatedAt);

    /**
     * Moves PENDING to PAID and records the grant this payment authorized.
     *
     * @return false when the order was not PENDING at the expected version
     */
    boolean markPaid(UUID id, String externalTxId, OffsetDateTime paidAt, UUID fulfillmentEventId,
                     long expectedVersion, OffsetDateTime updatedAt);

    boolean markClosed(UUID id, OffsetDateTime closedAt, long expectedVersion, OffsetDateTime updatedAt);

    boolean markFailed(UUID id, OffsetDateTime closedAt, String failureCode, String failureDetail,
                       long expectedVersion, OffsetDateTime updatedAt);

    boolean markRefunded(UUID id, long refundedAmountMinor, OffsetDateTime refundedAt,
                         long expectedVersion, OffsetDateTime updatedAt);

    boolean markFulfilled(UUID id, OffsetDateTime fulfilledAt, long expectedVersion, OffsetDateTime updatedAt);

    boolean updateStatus(UUID id, OrderStatus status, OffsetDateTime paidAt, String externalTxId,
                         long expectedVersion, OffsetDateTime updatedAt);
}
