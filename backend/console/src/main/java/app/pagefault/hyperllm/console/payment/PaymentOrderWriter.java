package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The transactional half of order creation. The channel HTTP call happens outside this bean so a
 * slow channel never holds a wallet lock, and the row that reserved capacity is committed before
 * the payment surface is requested.
 */
@Component
@RequiredArgsConstructor
public class PaymentOrderWriter {

    private static final long MAX_MIRRORED_MICROS = (1L << 53) - 1L;

    private final PaymentOrderRepository orders;
    private final WalletRepository wallets;
    private final OutboxEventRepository outbox;

    public record Reservation(PaymentOrder order, boolean created) {
    }

    @Transactional
    public Reservation reserve(PaymentOrder candidate) {
        var wallet = wallets.findByOrgIdForUpdate(candidate.orgId())
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND,
                        "Wallet not found for organization"));
        if (candidate.creditMicros() > 0L) {
            long pending = orders.sumPendingTopupCredits(candidate.orgId());
            long headroom = MAX_MIRRORED_MICROS - wallet.cashBalanceMicros()
                    - pending - candidate.creditMicros();
            if (headroom < 0L
                    || !outbox.unconfirmedCreditsFit(candidate.orgId(), BillingMoneyEvent.EventType.RECHARGE, headroom)) {
                throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT,
                        "The top-up would exceed the wallet capacity");
            }
        }
        if (orders.insertIfAbsent(candidate)) {
            return new Reservation(candidate, true);
        }
        return new Reservation(orders.findByIdempotencyKey(candidate.idempotencyKey())
                .orElseThrow(() -> new HyperLlmException(ErrorCode.PAYMENT_ORDER_CONFLICT)), false);
    }

    @Transactional
    public PaymentOrder attachInstruction(UUID id, String channelRef, String payload) {
        PaymentOrder order = orders.findByIdForUpdate(id)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (order.status() != app.pagefault.hyperllm.domain.model.enums.OrderStatus.PENDING) {
            return order;
        }
        if (!orders.updateChannelReference(id, channelRef, payload, order.version(),
                OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new HyperLlmException(ErrorCode.PAYMENT_STATE_CONFLICT);
        }
        return orders.findById(id).orElseThrow();
    }

    @Transactional
    public void markFailed(UUID id, String failureCode, String failureDetail) {
        PaymentOrder order = orders.findByIdForUpdate(id)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (order.status() == app.pagefault.hyperllm.domain.model.enums.OrderStatus.PENDING) {
            orders.markFailed(id, OffsetDateTime.now(ZoneOffset.UTC), failureCode, failureDetail,
                    order.version(), OffsetDateTime.now(ZoneOffset.UTC));
        }
    }
}
