package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.OrgModelPricingOverride;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgModelPricingOverrideRepository {

    void insert(OrgModelPricingOverride override);

    Optional<OrgModelPricingOverride> findById(UUID id);

    /** The single effective override at {@code at}, latest {@code effective_from} winning ties. */
    Optional<OrgModelPricingOverride> findEffective(UUID orgId, UUID virtualModelId, OffsetDateTime at);

    List<OrgModelPricingOverride> listActiveByOrg(UUID orgId);

    boolean update(OrgModelPricingOverride next, long expectedVersion);

    boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt);
}
