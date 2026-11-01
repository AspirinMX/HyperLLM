package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.billing.WalletCreditOutbox;
import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.OrderType;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The confirmation transaction: the order moves to PAID and the grant that it buys is authorized in
 * the same commit. The wallet lock is taken first, matching the ledger fold's lock order.
 */
@Component
@RequiredArgsConstructor
public class PaymentConfirmationTx {

    private final PaymentOrderRepository orders;
    private final WalletCreditOutbox creditOutbox;
    private final ObjectMapper mapper;

    public record Result(Compensation outcome, UUID eventId) {
    }

    public enum Compensation { FULFILLED, DUPLICATE }

    @Transactional
    public Result confirm(UUID orderId, PaymentGateway.VerifiedPayment paid) {
        PaymentOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (order.status() == OrderStatus.PAID) {
            if (order.externalTxId() != null && order.externalTxId().equals(paid.externalTxId())) {
                return new Result(Compensation.DUPLICATE, order.fulfillmentEventId());
            }
            throw new HyperLlmException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "The order is already paid by another transaction");
        }
        if (order.status() == OrderStatus.REFUNDED) {
            throw new HyperLlmException(ErrorCode.PAYMENT_STATE_CONFLICT, "The order was refunded");
        }
        if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.CLOSED
                && order.status() != OrderStatus.FAILED) {
            throw new HyperLlmException(ErrorCode.PAYMENT_STATE_CONFLICT, "The order cannot be confirmed");
        }

        OffsetDateTime paidAt = paid.paidAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : paid.paidAt();
        String description = order.orderType() == OrderType.WALLET_TOPUP
                ? "Top-up " + order.orderNo()
                : "Pack purchase " + order.orderNo();
        BillingMoneyEvent event = switch (order.orderType()) {
            case WALLET_TOPUP -> creditOutbox.appendCash(order.orgId(), "payment:" + order.orderNo(),
                    order.creditMicros(), BillingMoneyEvent.CreditSource.PAYMENT, order.id(),
                    description, paidAt);
            case RESOURCE_PACK_PURCHASE -> grantPack(order, paidAt, description);
        };
        if (!orders.markPaid(order.id(), paid.externalTxId(), paidAt, event.eventId(),
                order.version(), OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new HyperLlmException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "The order moved while it was being confirmed");
        }
        return new Result(Compensation.FULFILLED, event.eventId());
    }

    private BillingMoneyEvent grantPack(PaymentOrder order, OffsetDateTime paidAt, String description) {
        PackSpecSnapshot spec = readSpec(order.packSpecJson());
        return creditOutbox.appendPack(order.orgId(), "payment:" + order.orderNo(),
                spec.name(), PackUnitType.valueOf(spec.unitType()), spec.totalUnits(),
                spec.applicableModels(), paidAt.plusDays(spec.validDays()), order.id(),
                description, paidAt);
    }

    private PackSpecSnapshot readSpec(String raw) {
        try {
            return mapper.readValue(raw, PackSpecSnapshot.class);
        } catch (Exception ex) {
            throw new HyperLlmException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "The order has no readable pack snapshot");
        }
    }
}
