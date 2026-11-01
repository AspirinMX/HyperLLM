package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;

import java.util.Optional;
import java.util.UUID;

public interface WalletLedgerRepository {

    /**
     * Inserts a ledger entry idempotently using (idempotency_key, created_at).
     *
     * @return true if inserted, false if ignored due to conflict.
     */
    boolean insertIdempotent(WalletLedgerEntry entry);

    Optional<UUID> findFirstByRequestKey(UUID orgId, String idempotencyKey);

    /** The folded grant of one payment order, used to report APPLIED without trusting the order row. */
    Optional<WalletLedgerEntry> findFirstByRefOrderId(UUID orderId);

    PageResponse<WalletLedgerEntry> listByOrg(UUID orgId, PageQuery pageQuery);
}
