package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.console.dto.PaymentDtos.RedemptionDto;
import app.pagefault.hyperllm.domain.billing.WalletCreditOutbox;
import app.pagefault.hyperllm.domain.model.billing.RedemptionCode;
import app.pagefault.hyperllm.domain.model.billing.Voucher;
import app.pagefault.hyperllm.domain.model.enums.RedemptionStatus;
import app.pagefault.hyperllm.domain.model.enums.VoucherStatus;
import app.pagefault.hyperllm.domain.repository.RedemptionCodeRepository;
import app.pagefault.hyperllm.domain.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Locks the template, consumes the code and authorizes the bonus in one transaction. */
@Component
@RequiredArgsConstructor
public class VoucherRedemptionTx {

    private final RedemptionCodeRepository codes;
    private final VoucherRepository vouchers;
    private final WalletCreditOutbox creditOutbox;

    @Transactional
    public RedemptionDto redeem(UUID orgId, UUID userId, String codeHash) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RedemptionCode code = codes.findByCodeHash(codeHash)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.VOUCHER_CODE_INVALID));
        if (code.status() == RedemptionStatus.REDEEMED) {
            if (orgId.equals(code.redeemedOrgId()) && code.grantEventId() != null) {
                Voucher voucher = vouchers.findById(code.voucherId()).orElseThrow();
                return new RedemptionDto(code.grantEventId(), voucher.amountMicros(),
                        voucher.applicableModels(), null);
            }
            throw new HyperLlmException(ErrorCode.VOUCHER_CODE_INVALID);
        }
        if (code.status() != RedemptionStatus.UNUSED) {
            throw new HyperLlmException(ErrorCode.VOUCHER_CODE_INVALID);
        }
        if (code.expiresAt() != null && !code.expiresAt().isAfter(now)) {
            throw new HyperLlmException(ErrorCode.VOUCHER_CODE_INVALID);
        }

        Voucher voucher = vouchers.findByIdForUpdate(code.voucherId())
                .orElseThrow(() -> new HyperLlmException(ErrorCode.VOUCHER_NOT_FOUND));
        if (voucher.status() != VoucherStatus.ACTIVE) {
            throw new HyperLlmException(ErrorCode.VOUCHER_CODE_INVALID);
        }
        if (voucher.maxRedemptionsTotal() != null
                && codes.countRedeemedByVoucher(voucher.id()) >= voucher.maxRedemptionsTotal()) {
            throw new HyperLlmException(ErrorCode.VOUCHER_LIMIT_REACHED);
        }
        if (codes.countRedeemedByVoucherAndOrg(voucher.id(), orgId) >= voucher.maxRedemptionsPerOrg()) {
            throw new HyperLlmException(ErrorCode.VOUCHER_LIMIT_REACHED);
        }

        OffsetDateTime expiresAt = voucher.fixedExpiresAt() != null
                ? voucher.fixedExpiresAt()
                : voucher.validDays() == null ? null : now.plusDays(voucher.validDays());
        BillingMoneyEvent event = creditOutbox.appendBonus(orgId, "voucher:" + code.id(),
                voucher.amountMicros(), BillingMoneyEvent.CreditSource.VOUCHER,
                voucher.applicableModels(), expiresAt, voucher.id(), code.id(),
                "Voucher " + voucher.name(), now);
        if (!codes.markRedeemed(code.id(), orgId, userId, event.eventId(), now)) {
            throw new HyperLlmException(ErrorCode.VOUCHER_CODE_INVALID);
        }
        return new RedemptionDto(event.eventId(), voucher.amountMicros(),
                voucher.applicableModels(), expiresAt);
    }
}
