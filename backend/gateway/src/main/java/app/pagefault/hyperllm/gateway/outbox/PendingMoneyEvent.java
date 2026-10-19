package app.pagefault.hyperllm.gateway.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A settlement waiting to be appended to the outbox table.
 *
 * <p>The payload is serialised once, on the request thread, so the writer never touches a domain
 * object and the queue holds exactly what will be stored.
 */
public record PendingMoneyEvent(
        UUID eventId,
        String orgId,
        String eventType,
        String payload,
        OffsetDateTime occurredAt
) {
}
