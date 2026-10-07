package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.routing.PricingRule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PricingRuleRepository {

    boolean hasOverlappingWindow(UUID virtualModelId, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo);

    void insert(PricingRule rule);

    Optional<PricingRule> findEffectiveAt(UUID virtualModelId, OffsetDateTime timestamp);

    List<PricingRule> listByModelId(UUID virtualModelId);
}
