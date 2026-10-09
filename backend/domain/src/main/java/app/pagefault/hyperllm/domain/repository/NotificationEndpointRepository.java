package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationEndpointRepository {

    void insert(NotificationEndpoint endpoint);

    Optional<NotificationEndpoint> findById(UUID id);

    List<NotificationEndpoint> listActiveByOrg(UUID orgId);

    boolean update(NotificationEndpoint next, long expectedVersion);

    boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt);
}
