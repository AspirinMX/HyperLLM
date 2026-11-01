package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.ResourcePackSku;
import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourcePackSkuRepository {

    void insert(ResourcePackSku sku);

    Optional<ResourcePackSku> findById(UUID id);

    Optional<ResourcePackSku> findByCode(String code);

    List<ResourcePackSku> listActive();

    PageResponse<ResourcePackSku> listPage(PackSkuStatus status, PageQuery pageQuery);

    boolean update(ResourcePackSku next, long expectedVersion);
}
