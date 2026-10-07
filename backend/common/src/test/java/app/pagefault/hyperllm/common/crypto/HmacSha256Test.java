package app.pagefault.hyperllm.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSha256Test {

    @Test
    void computesDeterministicLowerHexAndVerifiesConstantTime() {
        String digest1 = HmacSha256.hmacHex("sk-hl-test-key", "pepper-v1");
        String digest2 = HmacSha256.hmacHex("sk-hl-test-key", "pepper-v1");
        String other = HmacSha256.hmacHex("sk-hl-test-key", "pepper-v2");

        assertThat(digest1).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(HmacSha256.constantTimeEqualsHex(digest1, digest2)).isTrue();
        assertThat(HmacSha256.constantTimeEqualsHex(digest1, other)).isFalse();
        assertThat(HmacSha256.sha256Hex("refresh-token")).hasSize(64).matches("^[0-9a-f]{64}$");
    }
}
