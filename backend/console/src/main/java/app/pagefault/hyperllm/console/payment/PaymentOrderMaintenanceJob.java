package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Queries expired PENDING orders and closes the ones the channel says are dead. A local deadline
 * alone never releases the reservation: only the channel's final state does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hyperllm.payment.maintenance-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentOrderMaintenanceJob {

    private static final int BATCH = 20;
    private static final long CLOSE_GRACE_MINUTES = 5;

    private final PaymentOrderRepository orders;
    private final PaymentGatewayRegistry gateways;
    private final PaymentConfirmationService confirmations;

    @Scheduled(initialDelayString = "${hyperllm.payment.maintenance-initial-delay-ms:30000}",
            fixedDelayString = "${hyperllm.payment.maintenance-interval-ms:30000}")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (PaymentOrder order : orders.listExpiredPending(now, BATCH)) {
            PaymentGateway gateway = gateways.find(order.paymentChannel());
            if (gateway == null || !gateway.enabled()) {
                continue;
            }
            try {
                PaymentGateway.PaymentQueryResult result = gateway.query(order);
                if (result.state() == PaymentGateway.PaymentState.PAID) {
                    confirmations.confirm(new PaymentGateway.VerifiedPayment(
                            "query:" + order.orderNo() + ":" + result.externalTxId(),
                            order.orderNo(), result.externalTxId(), PaymentGateway.PaymentState.PAID,
                            result.paidAmountMinor(), result.currency(), result.paidAt(), result.rawJson()),
                            order.paymentChannel());
                } else if (result.state() == PaymentGateway.PaymentState.CLOSED) {
                    orders.markClosed(order.id(), now, order.version(), now);
                } else if (now.isAfter(order.expiresAt().plusMinutes(CLOSE_GRACE_MINUTES))) {
                    gateway.close(order);
                    orders.markClosed(order.id(), now, order.version(), now);
                }
            } catch (RuntimeException ex) {
                log.warn("Payment order {} could not be reconciled: {}", order.orderNo(), ex.getMessage());
            }
        }
    }
}