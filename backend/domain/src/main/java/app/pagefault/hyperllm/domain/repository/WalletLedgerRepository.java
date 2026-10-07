package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;

import java.util.UUID;

public interface WalletLedgerRepository {

    /**
     * Inserts a ledger entry idempotently using (idempotency_key, created_at).
     *
     * @return true if inserted, false if ignored due to conflict.
     */
    boolean insertIdempotent(WalletLedgerEntry entry);

    PageResponse<WalletLedgerEntry> listByOrg(UUID orgId, PageQuery pageQuery);
}
