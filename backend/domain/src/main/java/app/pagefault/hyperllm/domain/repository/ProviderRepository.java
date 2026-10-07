package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.routing.Provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderRepository {

    Optional<Provider> findById(UUID id);

    Optional<Provider> findByCode(String code);

    List<Provider> listAllActive();
}
