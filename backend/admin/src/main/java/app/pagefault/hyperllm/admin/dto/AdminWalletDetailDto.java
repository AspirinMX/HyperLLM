package app.pagefault.hyperllm.admin.dto;

import java.util.UUID;

/**
 * One tenant's wallet as the detail view shows it.
 *
 * @param cashMicros        live cash
 * @param bonusMicros       live unexpired bonus
 * @param heldMicros        reserved by requests still in flight; only the mirror knows this
 * @param availableMicros   balance minus held
 * @param ledgerConfirmed   true when the numbers came from the database because the mirror is absent
 * @param totalRechargedMicros ledger-confirmed lifetime totals, which never come from the mirror
 */
public record AdminWalletDetailDto(
        UUID orgId,
        long cashMicros,
        long bonusMicros,
        long heldMicros,
        long availableMicros,
        boolean ledgerConfirmed,
        long totalRechargedMicros,
        long totalBonusGrantedMicros,
        long totalConsumedCashMicros,
        long totalConsumedBonusMicros
) {
}
