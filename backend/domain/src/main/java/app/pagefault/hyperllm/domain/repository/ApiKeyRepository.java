package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.tenant.ApiKey;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository {

    void insert(ApiKey apiKey);

    Optional<ApiKey> findById(UUID id);

    Optional<GatewayKeyContext> findGatewayKeyContextByHash(String keyHash);

    PageResponse<ApiKey> listByProject(UUID projectId, PageQuery pageQuery);

    boolean updateConfig(
            UUID keyId,
            String name,
            List<String> allowedModels,
            List<String> ipWhitelist,
            Integer rpmLimit,
            Integer tpmLimit,
            Integer maxConcurrency,
            Long quotaLimitMicros,
            OffsetDateTime expiresAt,
            ApiKeyStatus status,
            long expectedVersion,
            OffsetDateTime updatedAt
    );

    boolean revoke(UUID keyId, long expectedVersion, OffsetDateTime deletedAt);
}
