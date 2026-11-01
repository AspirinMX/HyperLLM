package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.PaymentNotificationsRecord;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.enums.PaymentNotificationOutcome;
import app.pagefault.hyperllm.domain.model.payment.PaymentNotification;
import app.pagefault.hyperllm.domain.repository.PaymentNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static app.pagefault.hyperllm.domain.jooq.Tables.PAYMENT_NOTIFICATIONS;

@Repository
@RequiredArgsConstructor
public class JooqPaymentNotificationRepository implements PaymentNotificationRepository {

    private final DSLContext dsl;

    @Override
    public boolean insertIfAbsent(PaymentNotification notification) {
        return dsl.insertInto(PAYMENT_NOTIFICATIONS)
                .set(PAYMENT_NOTIFICATIONS.ID, notification.id())
                .set(PAYMENT_NOTIFICATIONS.PAYMENT_CHANNEL, notification.paymentChannel().name())
                .set(PAYMENT_NOTIFICATIONS.NOTIFICATION_ID, notification.notificationId())
                .set(PAYMENT_NOTIFICATIONS.ORDER_NO, notification.orderNo())
                .set(PAYMENT_NOTIFICATIONS.EXTERNAL_TX_ID, notification.externalTxId())
                .set(PAYMENT_NOTIFICATIONS.OUTCOME, notification.outcome().name())
                .set(PAYMENT_NOTIFICATIONS.DETAIL_JSON, PgJooqSupport.toJsonb(notification.detailJson()))
                .set(PAYMENT_NOTIFICATIONS.RECEIVED_AT, notification.receivedAt())
                .set(PAYMENT_NOTIFICATIONS.PROCESSED_AT, notification.processedAt())
                .onConflictDoNothing()
                .execute() == 1;
    }

    @Override
    public Optional<PaymentNotification> findByChannelAndId(PaymentChannel channel, String notificationId) {
        return dsl.selectFrom(PAYMENT_NOTIFICATIONS)
                .where(PAYMENT_NOTIFICATIONS.PAYMENT_CHANNEL.eq(channel.name()))
                .and(PAYMENT_NOTIFICATIONS.NOTIFICATION_ID.eq(notificationId))
                .fetchOptional(this::toDomain);
    }

    private PaymentNotification toDomain(PaymentNotificationsRecord r) {
        return new PaymentNotification(
                r.getId(),
                PgJooqSupport.toEnum(PaymentChannel.class, r.getPaymentChannel()),
                r.getNotificationId(),
                r.getOrderNo(),
                r.getExternalTxId(),
                PgJooqSupport.toEnum(PaymentNotificationOutcome.class, r.getOutcome()),
                PgJooqSupport.fromJsonb(r.getDetailJson()),
                r.getReceivedAt(),
                r.getProcessedAt()
        );
    }
}
