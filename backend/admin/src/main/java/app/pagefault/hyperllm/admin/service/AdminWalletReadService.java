package app.pagefault.hyperllm.admin.service;

import app.pagefault.hyperllm.admin.dto.AdminWalletDetailDto;
import app.pagefault.hyperllm.common.billing.LiveWalletReader;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Answers "what does this tenant actually hold" for the operator detail view.
 *
 * <p>Live values come from the mirror, because that is what the gateway charges against and the only
 * place that knows about reservations. Lifetime totals come from the database, because they are the
 * fold of the ledger and the mirror does not carry them. When the mirror is absent the response says
 * so instead of presenting a trailing snapshot as if it were live.
 */
@Service
@RequiredArgsConstructor
public class AdminWalletReadService {

    private final WalletRepository walletRepository;
    private final LiveWalletReader liveWalletReader;

    public AdminWalletDetailDto read(UUID orgId) {
        Wallet wallet = walletRepository.findByOrgId(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND, "Wallet not found"));
        LiveWalletReader.LiveWallet live = liveWalletReader.read(orgId);
        long cash = live != null ? live.cashMicros() : wallet.cashBalanceMicros();
        long bonus = live != null ? live.bonusMicros() : wallet.bonusBalanceMicros();
        long held = live != null ? live.heldMicros() : 0L;
        long available = live != null ? live.availableMicros() : cash + bonus;
        return new AdminWalletDetailDto(
                orgId, cash, bonus, held, available, live == null,
                wallet.totalRechargedMicros(), wallet.totalBonusGrantedMicros(),
                wallet.totalConsumedCashMicros(), wallet.totalConsumedBonusMicros());
    }
}
