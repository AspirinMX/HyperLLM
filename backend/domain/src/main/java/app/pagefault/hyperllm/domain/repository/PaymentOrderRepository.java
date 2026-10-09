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

    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    PageResponse<PaymentOrder> listByOrg(UUID orgId, PageQuery pageQuery);

    List<PaymentOrder> listExpiredPending(OffsetDateTime now, int limit);

    boolean updateStatus(
            UUID id,
            OrderStatus status,
            OffsetDateTime paidAt,
            String externalTxId,
            long expectedVersion,
            OffsetDateTime updatedAt
    );
}
