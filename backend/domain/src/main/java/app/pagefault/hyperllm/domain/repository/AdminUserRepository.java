package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.auth.AdminUser;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository {

    long countAll();

    void insert(AdminUser adminUser);

    Optional<AdminUser> findById(UUID id);

    Optional<AdminUser> findActiveByLogin(String usernameOrEmail);

    boolean updateTotpSetup(UUID adminId, byte[] encryptedSecret, boolean mfaEnabled,
                            String recoveryCodesHashJson, long expectedVersion, OffsetDateTime updatedAt);

    void updateLastLogin(UUID adminId, OffsetDateTime loginAt);
}
