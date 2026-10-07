package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.ChannelStatus;
import app.pagefault.hyperllm.domain.model.routing.Channel;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ChannelRepository {

    void insert(Channel channel);

    Optional<Channel> findById(UUID id);

    PageResponse<Channel> listActive(PageQuery pageQuery);

    boolean update(
            UUID channelId,
            String name,
            String region,
            String baseUrlOverride,
            byte[] encryptedCredentials,
            short credentialKeyVersion,
            int defaultCostDiscountBps,
            Integer rpmLimit,
            Integer tpmLimit,
            Integer maxConcurrency,
            String healthCheckModel,
            ChannelStatus status,
            long expectedVersion,
            OffsetDateTime updatedAt
    );

    boolean softDelete(UUID channelId, long expectedVersion, OffsetDateTime deletedAt);
}
