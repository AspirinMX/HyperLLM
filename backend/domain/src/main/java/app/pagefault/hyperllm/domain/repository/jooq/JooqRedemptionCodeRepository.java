package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.RedemptionCodesRecord;
import app.pagefault.hyperllm.domain.model.billing.RedemptionCode;
import app.pagefault.hyperllm.domain.model.enums.RedemptionStatus;
import app.pagefault.hyperllm.domain.repository.RedemptionCodeRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.REDEMPTION_CODES;

@Repository
@RequiredArgsConstructor
public class JooqRedemptionCodeRepository implements RedemptionCodeRepository {

    private final DSLContext dsl;

    @Override
    public void insert(RedemptionCode code) {
        dsl.insertInto(REDEMPTION_CODES)
                .set(REDEMPTION_CODES.ID, code.id())
                .set(REDEMPTION_CODES.VOUCHER_ID, code.voucherId())
                .set(REDEMPTION_CODES.BATCH_ID, code.batchId())
                .set(REDEMPTION_CODES.CODE_HASH, code.codeHash())
                .set(REDEMPTION_CODES.CODE_HINT, code.codeHint())
                .set(REDEMPTION_CODES.REDEEMED_ORG_ID, code.redeemedOrgId())
                .set(REDEMPTION_CODES.REDEEMED_BY_USER_ID, code.redeemedByUserId())
                .set(REDEMPTION_CODES.REDEEMED_AT, code.redeemedAt())
                .set(REDEMPTION_CODES.EXPIRES_AT, code.expiresAt())
                .set(REDEMPTION_CODES.STATUS, code.status().name())
                .set(REDEMPTION_CODES.CREATED_AT, code.createdAt())
                .execute();
    }

    @Override
    public void insertBatch(List<RedemptionCode> codes) {
        if (codes.isEmpty()) {
            return;
        }
        Query[] inserts = codes.stream()
                .map(code -> dsl.insertInto(REDEMPTION_CODES)
                        .set(REDEMPTION_CODES.ID, code.id())
                        .set(REDEMPTION_CODES.VOUCHER_ID, code.voucherId())
                        .set(REDEMPTION_CODES.BATCH_ID, code.batchId())
                        .set(REDEMPTION_CODES.CODE_HASH, code.codeHash())
                        .set(REDEMPTION_CODES.CODE_HINT, code.codeHint())
                        .set(REDEMPTION_CODES.EXPIRES_AT, code.expiresAt())
                        .set(REDEMPTION_CODES.STATUS, code.status().name())
                        .set(REDEMPTION_CODES.CREATED_AT, code.createdAt()))
                .toArray(Query[]::new);
        dsl.batch(inserts).execute();
    }

    @Override
    public Optional<RedemptionCode> findByCodeHash(String codeHash) {
        return dsl.selectFrom(REDEMPTION_CODES)
                .where(REDEMPTION_CODES.CODE_HASH.eq(codeHash))
                .fetchOptional(this::toDomain);
    }

    @Override
    public boolean markRedeemed(UUID codeId, UUID orgId, UUID userId, UUID grantEventId,
                                OffsetDateTime redeemedAt) {
        int updated = dsl.update(REDEMPTION_CODES)
                .set(REDEMPTION_CODES.STATUS, RedemptionStatus.REDEEMED.name())
                .set(REDEMPTION_CODES.REDEEMED_ORG_ID, orgId)
                .set(REDEMPTION_CODES.REDEEMED_BY_USER_ID, userId)
                .set(REDEMPTION_CODES.GRANT_EVENT_ID, grantEventId)
                .set(REDEMPTION_CODES.REDEEMED_AT, redeemedAt)
                .where(REDEMPTION_CODES.ID.eq(codeId))
                .and(REDEMPTION_CODES.STATUS.eq(RedemptionStatus.UNUSED.name()))
                .and(REDEMPTION_CODES.EXPIRES_AT.isNull().or(REDEMPTION_CODES.EXPIRES_AT.gt(redeemedAt)))
                .execute();
        return updated == 1;
    }

    @Override
    public long countRedeemedByVoucher(UUID voucherId) {
        return dsl.fetchCount(
                dsl.selectFrom(REDEMPTION_CODES)
                        .where(REDEMPTION_CODES.VOUCHER_ID.eq(voucherId))
                        .and(REDEMPTION_CODES.STATUS.eq(RedemptionStatus.REDEEMED.name()))
        );
    }

    @Override
    public long countRedeemedByVoucherAndOrg(UUID voucherId, UUID orgId) {
        return dsl.fetchCount(
                dsl.selectFrom(REDEMPTION_CODES)
                        .where(REDEMPTION_CODES.VOUCHER_ID.eq(voucherId))
                        .and(REDEMPTION_CODES.REDEEMED_ORG_ID.eq(orgId))
                        .and(REDEMPTION_CODES.STATUS.eq(RedemptionStatus.REDEEMED.name()))
        );
    }

    @Override
    public PageResponse<RedemptionCode> listByVoucher(UUID voucherId, PageQuery pageQuery) {
        return PgJooqSupport.fetchPage(
                dsl, REDEMPTION_CODES, REDEMPTION_CODES.VOUCHER_ID.eq(voucherId),
                REDEMPTION_CODES.CREATED_AT.desc(), pageQuery, this::toDomain
        );
    }

    private RedemptionCode toDomain(RedemptionCodesRecord r) {
        return new RedemptionCode(
                r.getId(),
                r.getVoucherId(),
                r.getBatchId(),
                r.getCodeHash(),
                r.getCodeHint(),
                r.getRedeemedOrgId(),
                r.getRedeemedByUserId(),
                r.getRedeemedAt(),
                r.getExpiresAt(),
                PgJooqSupport.toEnum(RedemptionStatus.class, r.getStatus()),
                r.getGrantEventId(),
                r.getCreatedAt()
        );
    }
}