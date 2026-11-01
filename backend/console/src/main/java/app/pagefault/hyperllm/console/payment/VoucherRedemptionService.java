package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.idempotency.DuplicatePolicy;
import app.pagefault.hyperllm.common.idempotency.Idempotent;
import app.pagefault.hyperllm.console.dto.PaymentDtos.RedeemVoucherRequest;
import app.pagefault.hyperllm.console.dto.PaymentDtos.RedemptionDto;
import app.pagefault.hyperllm.console.service.ConsoleRbacService;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VoucherRedemptionService {

    private final ConsoleRbacService rbac;
    private final VoucherCodeService codeService;
    private final VoucherRedemptionTx redemption;

    @Idempotent(namespace = "voucher-redeem", key = "#p0 + ':' + #p2.code()",
            onDuplicate = DuplicatePolicy.REPLAY, inFlightSeconds = 60, resultSeconds = 3600)
    public RedemptionDto redeem(UUID orgId, UUID userId, RedeemVoucherRequest request) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.BILLING);
        String normalized = codeService.normalize(request.code());
        return redemption.redeem(orgId, userId, codeService.hash(normalized));
    }
}
