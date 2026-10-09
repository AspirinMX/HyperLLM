package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.ResourcePack;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourcePackRepository {

    void insert(ResourcePack pack);

    Optional<ResourcePack> findById(UUID id);

    /**
     * Active, unexpired packs whose {@code applicable_models} contain the given model code,
     * ordered by {@code expires_at} ascending (the deduction order).
     */
    List<ResourcePack> listConsumableByOrgAndModel(UUID orgId, String modelCode, OffsetDateTime at);

    /**
     * Conditional atomic decrement: succeeds only when the pack is ACTIVE and holds enough units.
     */
    boolean decrementUnits(UUID packId, long units, OffsetDateTime updatedAt);

    boolean revoke(UUID packId, long expectedVersion, OffsetDateTime updatedAt);

    /** Sum of remaining units across consumable packs; feeds the gateway availability aggregate. */
    long sumRemainingUnits(UUID orgId, String modelCode, OffsetDateTime at);

    PageResponse<ResourcePack> listByOrg(UUID orgId, PageQuery pageQuery);
}
