package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerTierRepository {

    Optional<CustomerTier> findById(UUID id);

    Optional<CustomerTier> findActiveByCode(String tierCode);

    List<CustomerTier> listAllActive();
}
