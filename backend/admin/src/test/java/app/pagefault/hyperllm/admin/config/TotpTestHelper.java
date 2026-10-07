package app.pagefault.hyperllm.admin.config;

import java.time.Instant;

public final class TotpTestHelper {

    private TotpTestHelper() {
    }

    public static String currentCode(String base32Secret) {
        return TotpService.generateCode(base32Secret, Instant.now());
    }
}
