package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.VouchersRecord;
import app.pagefault.hyperllm.domain.model.billing.Voucher;
import app.pagefault.hyperllm.domain.model.enums.VoucherStatus;
import app.pagefault.hyperllm.domain.model.enums.VoucherType;
import app.pagefault.hyperllm.domain.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.VOUCHERS;

@Repository
@RequiredArgsConstructor
public class JooqVoucherRepository implements VoucherRepository {

    private final DSLContext dsl;

    @Override
    public void insert(Voucher voucher) {
        dsl.insertInto(VOUCHERS)
                .set(VOUCHERS.ID, voucher.id())
                .set(VOUCHERS.NAME, voucher.name())
                .set(VOUCHERS.VOUCHER_TYPE, voucher.voucherType().name())
                .set(VOUCHERS.AMOUNT_MICROS, voucher.amountMicros())
                .set(VOUCHERS.APPLICABLE_MODELS, PgJooqSupport.toTextArray(voucher.applicableModels()))
                .set(VOUCHERS.VALID_DAYS, voucher.validDays())
                .set(VOUCHERS.FIXED_EXPIRES_AT, voucher.fixedExpiresAt())
                .set(VOUCHERS.MAX_REDEMPTIONS_TOTAL, voucher.maxRedemptionsTotal())
                .set(VOUCHERS.MAX_REDEMPTIONS_PER_ORG, voucher.maxRedemptionsPerOrg())
                .set(VOUCHERS.STATUS, voucher.status().name())
                .set(VOUCHERS.CREATED_BY_ADMIN_ID, voucher.createdByAdminId())
                .set(VOUCHERS.VERSION, voucher.version())
                .set(VOUCHERS.CREATED_AT, voucher.createdAt())
                .set(VOUCHERS.UPDATED_AT, voucher.updatedAt())
                .execute();
    }

    @Override
    public Optional<Voucher> findById(UUID id) {
        return dsl.selectFrom(VOUCHERS)
                .where(VOUCHERS.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public boolean update(Voucher next, long expectedVersion) {
        int updated = dsl.update(VOUCHERS)
                .set(VOUCHERS.NAME, next.name())
                .set(VOUCHERS.APPLICABLE_MODELS, PgJooqSupport.toTextArray(next.applicableModels()))
                .set(VOUCHERS.VALID_DAYS, next.validDays())
                .set(VOUCHERS.FIXED_EXPIRES_AT, next.fixedExpiresAt())
                .set(VOUCHERS.MAX_REDEMPTIONS_TOTAL, next.maxRedemptionsTotal())
                .set(VOUCHERS.MAX_REDEMPTIONS_PER_ORG, next.maxRedemptionsPerOrg())
                .set(VOUCHERS.VERSION, VOUCHERS.VERSION.plus(1))
                .set(VOUCHERS.UPDATED_AT, next.updatedAt())
                .where(VOUCHERS.ID.eq(next.id()))
                .and(VOUCHERS.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean updateStatus(UUID id, VoucherStatus status, long expectedVersion, OffsetDateTime updatedAt) {
        int updated = dsl.update(VOUCHERS)
                .set(VOUCHERS.STATUS, status.name())
                .set(VOUCHERS.VERSION, VOUCHERS.VERSION.plus(1))
                .set(VOUCHERS.UPDATED_AT, updatedAt)
                .where(VOUCHERS.ID.eq(id))
                .and(VOUCHERS.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    @Override
    public PageResponse<Voucher> listPage(PageQuery pageQuery) {
        return PgJooqSupport.fetchPage(
                dsl, VOUCHERS, org.jooq.impl.DSL.trueCondition(),
                VOUCHERS.CREATED_AT.desc(), pageQuery, this::toDomain
        );
    }

    private Voucher toDomain(VouchersRecord r) {
        return new Voucher(
                r.getId(),
                r.getName(),
                PgJooqSupport.toEnum(VoucherType.class, r.getVoucherType()),
                r.getAmountMicros(),
                PgJooqSupport.fromTextArray(r.getApplicableModels()),
                r.getValidDays(),
                r.getFixedExpiresAt(),
                r.getMaxRedemptionsTotal(),
                r.getMaxRedemptionsPerOrg(),
                PgJooqSupport.toEnum(VoucherStatus.class, r.getStatus()),
                r.getCreatedByAdminId(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
