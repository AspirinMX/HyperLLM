package app.pagefault.hyperllm.admin.alert;

import app.pagefault.hyperllm.admin.alert.AlertDeliveryStore.Delivery;
import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;
import app.pagefault.hyperllm.domain.repository.NotificationEndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Delivers claimed notifications with bounded retries.
 *
 * <p>Each endpoint retries on its own: a failing webhook does not hold back the mail for the same
 * trigger. A permanent failure, an endpoint that was disabled in the meantime, or the attempt limit
 * terminates the delivery with a reason instead of retrying forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertDeliveryJob {

    private static final long[] BACKOFF_SECONDS = {60, 300, 900, 3_600, 21_600};

    private final AlertDeliveryStore store;
    private final NotificationEndpointRepository endpointRepository;
    private final NotificationDispatcher dispatcher;
    private final AdminProperties properties;

    @Scheduled(fixedDelayString = "${hyperllm.alerts.delivery-delay:PT30S}")
    public void deliver() {
        if (!properties.getAlerts().isEnabled()) {
            return;
        }
        deliverOnce();
    }

    public int deliverOnce() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> due = store.claimDue(properties.getAlerts().getDeliveryBatch(), now);
        int sent = 0;
        for (UUID id : due) {
            if (deliverOne(id)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean deliverOne(UUID id) {
        Delivery delivery = store.find(id).orElse(null);
        if (delivery == null) {
            return false;
        }
        NotificationEndpoint endpoint = endpointRepository.findById(delivery.endpointId()).orElse(null);
        int attempts = delivery.attempts() + 1;
        try {
            dispatcher.send(delivery, endpoint);
            store.markSent(id, attempts, OffsetDateTime.now(ZoneOffset.UTC));
            return true;
        } catch (NotificationException ex) {
            terminate(id, attempts, ex.errorClass(), ex.getMessage(), ex.permanent());
            return false;
        } catch (RuntimeException ex) {
            log.error("Alert delivery {} failed unexpectedly", id, ex);
            terminate(id, attempts, ex.getClass().getSimpleName(), ex.getMessage(), false);
            return false;
        }
    }

    private void terminate(UUID id, int attempts, String errorClass, String detail, boolean permanent) {
        int max = properties.getAlerts().getMaxAttempts();
        boolean dead = permanent || attempts >= max;
        OffsetDateTime next = dead ? null
                : OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(backoff(attempts));
        store.markFailure(id, attempts, dead ? "DEAD" : "RETRY", next, errorClass, detail);
        if (dead) {
            log.warn("Alert delivery {} terminated after {} attempts: {}", id, attempts, errorClass);
        }
    }

    private static long backoff(int attempts) {
        int index = Math.max(0, Math.min(attempts - 1, BACKOFF_SECONDS.length - 1));
        return BACKOFF_SECONDS[index];
    }
}
