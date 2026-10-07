package app.pagefault.hyperllm.domain.model.routing;

import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Provider(
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
}
