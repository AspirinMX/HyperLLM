package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentNotification;

import java.util.Optional;

public interface PaymentNotificationRepository {

    /** @return true when inserted, false when this channel notification was already recorded. */
    boolean insertIfAbsent(PaymentNotification notification);

    Optional<PaymentNotification> findByChannelAndId(PaymentChannel channel, String notificationId);
}
