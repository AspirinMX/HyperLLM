package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Expires one bonus batch and books what is left of it.
 *
 * <p>Expiry is deliberately not routed through the Redis write entry: no balance change happened,
 * the value simply stopped being spendable when its date passed, and allocation already ignores a
 * batch past its expiry. What does have to move is the database snapshot, because it is the fold of
 * the ledger and would otherwise keep reporting a balance nobody can spend.
 *
 * <p>The ledger insert is the gate again: an expiry that was already booked returns false and
 * leaves the snapshot alone, so running the sweep twice cannot expire the same balance twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchExpiryService {

    private final WalletLedgerRepository ledgerRepository;
    private final WalletRepository walletRepository;
    private final BonusGrantRepository grantRepository;

    /** @return true when this call booked the expiry, false when it had already been booked */
    @Transactional
    public boolean expireGrant(BonusGrant grant, OffsetDateTime at) {
        long remaining = grant.remainingMicros();
        boolean inserted = ledgerRepository.insertIdempotent(new WalletLedgerEntry(
                UuidV7.next(),
                grant.orgId(),
                null,
                null,
                LedgerType.BONUS_EXPIRE,
                "expire:" + grant.id(),
                0L,
                -remaining,
                0L,
                null,
                null,
                null,
                null,
                null,
                null,
                "Bonus grant expired",
                at,
                null));
        if (!inserted) {
            return false;
        }
        if (!grantRepository.expire(grant.id(), at)) {
            // Someone else changed the grant between the read and this update; the row was written,
            // so the balance must still move to keep the fold identity.
            log.debug("grant {} changed before expiry was applied", grant.id());
        }
        if (remaining > 0L) {
            walletRepository.applyExpiryDelta(grant.orgId(), -remaining, at);
        }
        return true;
    }
}
