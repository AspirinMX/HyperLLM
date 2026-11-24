package app.pagefault.hyperllm.protocol.translate;

import app.pagefault.hyperllm.protocol.ir.ProtocolKind;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared upstream URL and authentication-header builder used by both {@code gateway} and {@code admin}
 * so base-URL normalization (e.g. {@code /v1} or {@code /v1beta}) and provider-specific auth headers
 * ({@code Authorization}, {@code x-api-key}, {@code x-goog-api-key}, {@code api-key}) never drift.
 */
public final class UpstreamEndpointResolver {

    public static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";

    private UpstreamEndpointResolver() {
    }

    public static String buildInferenceUrl(
            String baseUrl,
            ProtocolKind outboundProtocol,
            String upstreamModelName,
            boolean stream
    ) {
        String base = stripTrailingSlashes(baseUrl);
        return switch (outboundProtocol) {
            case OPENAI_CHAT -> appendVersionedPath(base, "/v1", "/chat/completions");
            case OPENAI_RESPONSES -> appendVersionedPath(base, "/v1", "/responses");
            case ANTHROPIC_MESSAGES -> appendVersionedPath(base, "/v1", "/messages");
            case GEMINI_GENERATE_CONTENT -> {
                String encodedModel = URLEncoder.encode(
                        upstreamModelName != null ? upstreamModelName.trim() : "",
                        StandardCharsets.UTF_8
                ).replace("+", "%20");
                String action = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
                String prefix = hasVersionSuffix(base) ? base : base + "/v1beta";
                yield prefix + "/models/" + encodedModel + action;
            }
        };
    }

    public static String buildProbeUrl(String baseUrl, ProtocolKind protocolKind) {
        String base = stripTrailingSlashes(baseUrl);
        return switch (protocolKind) {
            case ANTHROPIC_MESSAGES -> appendVersionedPath(base, "/v1", "/messages");
            case GEMINI_GENERATE_CONTENT -> (hasVersionSuffix(base) ? base : base + "/v1beta") + "/models";
            case OPENAI_CHAT, OPENAI_RESPONSES -> appendVersionedPath(base, "/v1", "/models");
        };
    }

    public static Map<String, String> buildAuthHeaders(
            String authSchemeName,
            ProtocolKind outboundProtocol,
            String apiKey,
            String anthropicVersionOverride,
            String anthropicBetaOverride
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        String scheme = authSchemeName == null ? "BEARER" : authSchemeName.trim().toUpperCase();
        switch (scheme) {
            case "X_GOOG_API_KEY" -> headers.put("x-goog-api-key", apiKey);
            case "X_API_KEY" -> {
                if (outboundProtocol == ProtocolKind.GEMINI_GENERATE_CONTENT) {
                    headers.put("x-goog-api-key", apiKey);
                } else {
                    headers.put("x-api-key", apiKey);
                }
            }
            case "AZURE_API_KEY" -> headers.put("api-key", apiKey);
            default -> headers.put("Authorization", "Bearer " + apiKey);
        }

        if (outboundProtocol == ProtocolKind.ANTHROPIC_MESSAGES) {
            String version = (anthropicVersionOverride != null && !anthropicVersionOverride.isBlank())
                    ? anthropicVersionOverride.trim() : DEFAULT_ANTHROPIC_VERSION;
            headers.put("anthropic-version", version);
            if (anthropicBetaOverride != null && !anthropicBetaOverride.isBlank()) {
                headers.put("anthropic-beta", anthropicBetaOverride.trim());
            }
        }
        return headers;
    }

    private static String appendVersionedPath(String base, String versionPrefix, String leafPath) {
        if (hasVersionSuffix(base)) {
            return base + leafPath;
        }
        return base + versionPrefix + leafPath;
    }

    private static boolean hasVersionSuffix(String base) {
        return base.endsWith("/v1")
                || base.endsWith("/v1beta")
                || base.endsWith("/v1beta1")
                || base.matches(".*/v\\d+(beta\\d*)?$");
    }

    private static String stripTrailingSlashes(String url) {
        if (url == null) {
            return "";
        }
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
