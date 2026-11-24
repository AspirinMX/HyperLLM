package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamEndpointResolverTest {

    @Test
    void normalizesVersionedAndUnversionedBaseUrlsAcrossAllFourProtocols() {
        assertThat(UpstreamEndpointResolver.buildInferenceUrl(
                "https://api.openai.com/v1", ProtocolKind.OPENAI_CHAT, "gpt-5", false))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(UpstreamEndpointResolver.buildInferenceUrl(
                "https://api.openai.com", ProtocolKind.OPENAI_RESPONSES, "gpt-5", true))
                .isEqualTo("https://api.openai.com/v1/responses");
        assertThat(UpstreamEndpointResolver.buildInferenceUrl(
                "https://api.anthropic.com", ProtocolKind.ANTHROPIC_MESSAGES, "claude-sonnet-4-5", false))
                .isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(UpstreamEndpointResolver.buildInferenceUrl(
                "https://api.anthropic.com/v1/", ProtocolKind.ANTHROPIC_MESSAGES, "claude-sonnet-4-5", false))
                .isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(UpstreamEndpointResolver.buildInferenceUrl(
                "https://generativelanguage.googleapis.com", ProtocolKind.GEMINI_GENERATE_CONTENT, "gemini-2.5-flash", false))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
        assertThat(UpstreamEndpointResolver.buildInferenceUrl(
                "https://generativelanguage.googleapis.com/v1beta", ProtocolKind.GEMINI_GENERATE_CONTENT, "gemini-2.5-flash", true))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse");
    }

    @Test
    void injectsProviderSpecificAuthHeadersWithoutLeakingAnthropicHeadersToGemini() {
        Map<String, String> geminiHeaders = UpstreamEndpointResolver.buildAuthHeaders(
                "X_GOOG_API_KEY", ProtocolKind.GEMINI_GENERATE_CONTENT, "AIza-secret", null, null);
        assertThat(geminiHeaders).containsEntry("x-goog-api-key", "AIza-secret")
                .doesNotContainKey("x-api-key")
                .doesNotContainKey("anthropic-version");

        Map<String, String> anthropicHeaders = UpstreamEndpointResolver.buildAuthHeaders(
                "X_API_KEY", ProtocolKind.ANTHROPIC_MESSAGES, "sk-ant-1", null, "prompt-caching-2024-07-31");
        assertThat(anthropicHeaders)
                .containsEntry("x-api-key", "sk-ant-1")
                .containsEntry("anthropic-version", "2023-06-01")
                .containsEntry("anthropic-beta", "prompt-caching-2024-07-31");

        Map<String, String> azureHeaders = UpstreamEndpointResolver.buildAuthHeaders(
                "AZURE_API_KEY", ProtocolKind.OPENAI_CHAT, "az-key", null, null);
        assertThat(azureHeaders).containsEntry("api-key", "az-key");
    }
}
