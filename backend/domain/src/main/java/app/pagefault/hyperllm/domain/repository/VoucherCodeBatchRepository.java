package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.VoucherCodeBatch;

import java.util.Optional;
import java.util.UUID;

public interface VoucherCodeBatchRepository {

    void insert(VoucherCodeBatch batch);

    Optional<VoucherCodeBatch> findById(UUID id);

    long countByVoucher(UUID voucherId);
}
