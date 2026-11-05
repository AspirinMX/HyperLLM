package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.BillingAlertRule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingAlertRuleRepository {

    void insert(BillingAlertRule rule);

    Optional<BillingAlertRule> findById(UUID id);

    List<BillingAlertRule> listActiveByOrg(UUID orgId);

    /** Active rules across tenants, page by page, for the sampler. */
    List<BillingAlertRule> listActivePage(int limit, int offset);

    /** Unconditional write; cooldown evaluation belongs to 2.9. */
    boolean touchLastTriggered(UUID id, OffsetDateTime triggeredAt);

    boolean update(BillingAlertRule next, long expectedVersion);

    boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt);
}
