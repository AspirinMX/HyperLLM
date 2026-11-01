package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.RedemptionCode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RedemptionCodeRepository {

    void insert(RedemptionCode code);

    void insertBatch(List<RedemptionCode> codes);

    Optional<RedemptionCode> findByCodeHash(String codeHash);

    /**
     * Conditional atomic redemption: succeeds only for an UNUSED, unexpired code.
     */
    boolean markRedeemed(UUID codeId, UUID orgId, UUID userId, UUID grantEventId, OffsetDateTime redeemedAt);

    long countRedeemedByVoucher(UUID voucherId);

    long countRedeemedByVoucherAndOrg(UUID voucherId, UUID orgId);

    PageResponse<RedemptionCode> listByVoucher(UUID voucherId, PageQuery pageQuery);
}