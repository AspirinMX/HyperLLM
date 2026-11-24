package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.auth.UserIdentity;
import app.pagefault.hyperllm.domain.model.enums.IdentityProvider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository {

    void insert(UserIdentity identity);

    Optional<UserIdentity> findGlobalByProviderAndSubject(IdentityProvider provider, String providerSubject);

    List<UserIdentity> findByUserId(UUID userId);

    void updateLastUsed(UUID identityId, OffsetDateTime lastUsedAt);

    boolean updatePasswordHash(UUID identityId, String passwordHash, OffsetDateTime updatedAt);
}
