package app.pagefault.hyperllm.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyGeneratorTest {

    @Test
    void generatesExpectedLiveKeyAndSandboxTokenFormats() {
        ApiKeyGenerator.GeneratedApiKey generated = ApiKeyGenerator.generateLiveKey();

        assertThat(generated.rawKey())
                .hasSize(54)
                .startsWith("sk-hl-")
                .matches("^sk-hl-[0-9A-Za-z]{48}$");
        assertThat(generated.keyPrefix())
                .hasSize(21)
                .startsWith(generated.rawKey().substring(0, 14))
                .contains("...")
                .endsWith(generated.rawKey().substring(50));
        assertThat(ApiKeyGenerator.isLiveKey(generated.rawKey())).isTrue();
        assertThat(ApiKeyGenerator.isSandboxKey(generated.rawKey())).isFalse();

        String sandboxToken = ApiKeyGenerator.generateSandboxToken();
        assertThat(sandboxToken)
                .hasSize(41)
                .startsWith("sk-hl-sb-")
                .matches("^sk-hl-sb-[0-9A-Za-z]{32}$");
        assertThat(ApiKeyGenerator.isSandboxKey(sandboxToken)).isTrue();
        assertThat(ApiKeyGenerator.isLiveKey(sandboxToken)).isFalse();
    }
}
