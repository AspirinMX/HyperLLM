package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BonusGrantRepository {

    void insert(BonusGrant grant);

    Optional<BonusGrant> findById(UUID id);

    /**
     * Active grants that either apply to every model ({@code applicable_models IS NULL}) or list the
     * given model code, ordered by {@code expires_at} ascending with open-ended grants last.
     */
    List<BonusGrant> listConsumableByOrgAndModel(UUID orgId, String modelCode, OffsetDateTime at);

    /** Includes not-yet-folded expiry so a wallet projection can distinguish it from legacy bonus. */
    List<BonusGrant> listActiveByOrg(UUID orgId);

    boolean decrementMicros(UUID grantId, long micros, OffsetDateTime updatedAt);

    /** Applies a settled debit to its own batch. EXPIRED means expiry already booked the remainder. */
    Optional<EntitlementStatus> foldConsumption(UUID orgId, UUID grantId, long micros, OffsetDateTime at);

    boolean revoke(UUID grantId, long expectedVersion, OffsetDateTime updatedAt);

    /** Grants that are still ACTIVE but whose expiry has passed, oldest expiry first. */
    List<BonusGrant> listExpiredActive(OffsetDateTime at, int limit);

    /** Marks a grant EXPIRED; only an ACTIVE grant is touched, so a concurrent change wins. */
    boolean expire(UUID grantId, OffsetDateTime updatedAt);

    /** Authoritative batch total used by reconciliation, not by the admission path. */
    long sumRemainingMicros(UUID orgId);

    PageResponse<BonusGrant> listByOrg(UUID orgId, PageQuery pageQuery);
}
