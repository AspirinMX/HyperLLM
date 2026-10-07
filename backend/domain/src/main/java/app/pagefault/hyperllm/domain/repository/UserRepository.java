package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.auth.User;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    void insert(User user);

    Optional<User> findById(UUID id);

    Optional<User> findActiveByEmail(String email);

    boolean updateDefaultOrg(UUID userId, UUID orgId, long expectedVersion, OffsetDateTime updatedAt);

    void updateLastLogin(UUID userId, OffsetDateTime loginAt);
}
