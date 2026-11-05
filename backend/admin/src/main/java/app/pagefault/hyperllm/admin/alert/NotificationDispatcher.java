package app.pagefault.hyperllm.admin.alert;

import app.pagefault.hyperllm.admin.alert.AlertDeliveryStore.Delivery;
import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;
import app.pagefault.hyperllm.domain.model.enums.NotificationChannelType;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


/** Chooses the transport for one delivery and renders the minimal body. */
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final WebhookSender webhookSender;
    private final EmailSender emailSender;
    private final AesGcmCipher cipher;

    public void send(Delivery delivery, NotificationEndpoint endpoint) {
        if (endpoint == null || endpoint.deletedAt() != null
                || endpoint.status() != ToggleStatus.ACTIVE) {
            throw new NotificationException("ENDPOINT_DISABLED", "The endpoint is no longer active", true);
        }
        if (endpoint.channelType() == NotificationChannelType.WEBHOOK) {
            if (endpoint.encryptedSigningSecret() == null) {
                throw new NotificationException("WEBHOOK_NO_SECRET", "The endpoint has no signing secret", true);
            }
            String secret = cipher.decryptUtf8(endpoint.encryptedSigningSecret(), endpoint.id().toString());
            webhookSender.send(endpoint.target(), delivery.id().toString(), secret, delivery.payloadJson());
            return;
        }
        String body = delivery.subject() + "\n\n" + delivery.payloadJson() + "\n";
        emailSender.send(endpoint.target(), delivery.subject(), body);
    }
}
