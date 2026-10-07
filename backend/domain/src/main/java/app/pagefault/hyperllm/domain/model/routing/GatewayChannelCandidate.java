package app.pagefault.hyperllm.domain.model.routing;

import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;

import java.util.UUID;

/**
 * Active upstream channel binding hydrated with provider and credential details for routing.
 */
public record GatewayChannelCandidate(
        UUID bindingId,
        UUID channelId,
        String channelName,
        String region,
        String providerCode,
        ProtocolFamily protocolFamily,
        AuthScheme authScheme,
        String resolvedBaseUrl,
        String upstreamModelName,
        short priorityTier,
        int weight,
        int effectiveCostDiscountBps,
        byte[] encryptedCredentials,
        short credentialKeyVersion,
        String extraHeadersJson,
        Integer channelRpmLimit,
        Integer channelTpmLimit,
        Integer channelMaxConcurrency
) {
}
