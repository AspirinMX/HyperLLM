package app.pagefault.hyperllm.common.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Denormalized request telemetry event emitted by gateway to Kafka {@code llm.request.events}
 * for ClickHouse OLAP ingestion.
 */
public record LlmRequestEvent(
        OffsetDateTime createdAt,
        String requestId,
        String traceId,
        UUID orgId,
        UUID projectId,
        UUID apiKeyId,
        UUID userId,
        String virtualModel,
        String provider,
        UUID channelId,
        String upstreamModel,
        String inboundProtocol,
        int isStream,
        int httpStatus,
        String errorCode,
        int retryCount,
        int isStickyHit,
        String guardrailAction,
        int isDebugLogged,
        int promptTokens,
        int cachedReadTokens,
        int cachedWriteTokens,
        int completionTokens,
        int reasoningTokens,
        long costMicros,
        long cashCostMicros,
        long bonusCostMicros,
        long upstreamCostMicros,
        long packDeductedUnits,
        int ttftMs,
        double tpotMs,
        int durationMs,
        String clientIpPrefix,
        String region
) {
}
