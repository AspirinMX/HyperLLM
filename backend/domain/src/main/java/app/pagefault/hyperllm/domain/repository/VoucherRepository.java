package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.Voucher;
import app.pagefault.hyperllm.domain.model.enums.VoucherStatus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRepository {

    void insert(Voucher voucher);

    Optional<Voucher> findById(UUID id);

    /** Locks the template so two redemptions cannot both pass the per-organisation limit. */
    Optional<Voucher> findByIdForUpdate(UUID id);

    boolean update(Voucher next, long expectedVersion);

    boolean updateStatus(UUID id, VoucherStatus status, long expectedVersion, OffsetDateTime updatedAt);

    PageResponse<Voucher> listPage(PageQuery pageQuery);
}
