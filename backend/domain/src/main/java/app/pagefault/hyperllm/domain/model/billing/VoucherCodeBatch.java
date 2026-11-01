package app.pagefault.hyperllm.domain.model.billing;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One batch of generated codes. The plaintext is delivered once, on creation. */
public record VoucherCodeBatch(
        UUID id,
        UUID voucherId,
        int quantity,
        UUID createdByAdminId,
        OffsetDateTime createdAt
) {
}
