package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.billing.RedemptionCodeFormat;
import app.pagefault.hyperllm.console.config.ConsoleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Wires the shared code format to this service's configured pepper. */
@Component
@RequiredArgsConstructor
public class VoucherCodeService {

    private final PaymentProperties payment;
    private final ConsoleProperties console;

    public String normalize(String raw) {
        return RedemptionCodeFormat.normalize(raw);
    }

    public String hash(String normalized) {
        String pepper = payment.getVoucherCodePepper();
        if (pepper == null || pepper.isBlank()) {
            pepper = console.getSecurity().getKeyPepperV1();
        }
        return RedemptionCodeFormat.hash(normalized, pepper);
    }

    public String generate() {
        return RedemptionCodeFormat.generate();
    }

    public String hint(String plaintext) {
        return RedemptionCodeFormat.hint(plaintext);
    }
}
