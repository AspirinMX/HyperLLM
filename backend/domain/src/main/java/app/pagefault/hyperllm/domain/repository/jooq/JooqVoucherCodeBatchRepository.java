package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.VoucherCodeBatchesRecord;
import app.pagefault.hyperllm.domain.model.billing.VoucherCodeBatch;
import app.pagefault.hyperllm.domain.repository.VoucherCodeBatchRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.VOUCHER_CODE_BATCHES;

@Repository
@RequiredArgsConstructor
public class JooqVoucherCodeBatchRepository implements VoucherCodeBatchRepository {

    private final DSLContext dsl;

    @Override
    public void insert(VoucherCodeBatch batch) {
        dsl.insertInto(VOUCHER_CODE_BATCHES)
                .set(VOUCHER_CODE_BATCHES.ID, batch.id())
                .set(VOUCHER_CODE_BATCHES.VOUCHER_ID, batch.voucherId())
                .set(VOUCHER_CODE_BATCHES.QUANTITY, batch.quantity())
                .set(VOUCHER_CODE_BATCHES.CREATED_BY_ADMIN_ID, batch.createdByAdminId())
                .set(VOUCHER_CODE_BATCHES.CREATED_AT, batch.createdAt())
                .execute();
    }

    @Override
    public Optional<VoucherCodeBatch> findById(UUID id) {
        return dsl.selectFrom(VOUCHER_CODE_BATCHES)
                .where(VOUCHER_CODE_BATCHES.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public long countByVoucher(UUID voucherId) {
        return dsl.fetchCount(VOUCHER_CODE_BATCHES, VOUCHER_CODE_BATCHES.VOUCHER_ID.eq(voucherId));
    }

    private VoucherCodeBatch toDomain(VoucherCodeBatchesRecord r) {
        return new VoucherCodeBatch(r.getId(), r.getVoucherId(), r.getQuantity(),
                r.getCreatedByAdminId(), r.getCreatedAt());
    }
}
