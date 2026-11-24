package app.pagefault.hyperllm.domain.model.routing;

import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Provider(
        UUID id,
        String code,
        String displayName,
        ProtocolFamily protocolFamily,
        List<String> supportedProtocols,
        String defaultWireProtocol,
        String defaultBaseUrl,
        AuthScheme authScheme,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public Provider {
        String inferredDefault = defaultWireProtocol != null && !defaultWireProtocol.isBlank()
                ? defaultWireProtocol
                : inferDefaultProtocol(protocolFamily);
        defaultWireProtocol = inferredDefault;
        supportedProtocols = (supportedProtocols == null || supportedProtocols.isEmpty())
                ? List.of(inferredDefault)
                : List.copyOf(supportedProtocols);
    }

    public Provider(
            UUID id,
            String code,
            String displayName,
            ProtocolFamily protocolFamily,
            String defaultBaseUrl,
            AuthScheme authScheme,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this(id, code, displayName, protocolFamily, null, null, defaultBaseUrl, authScheme, status, createdAt, updatedAt);
    }

    public static String inferDefaultProtocol(ProtocolFamily family) {
        if (family == null) {
            return "OPENAI_CHAT";
        }
        return switch (family) {
            case ANTHROPIC -> "ANTHROPIC_MESSAGES";
            case GEMINI -> "GEMINI_GENERATE_CONTENT";
            default -> "OPENAI_CHAT";
        };
    }
}
