package app.pagefault.hyperllm.domain.model.routing;

import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;

import java.util.List;
import java.util.UUID;

/**
 * Active upstream channel binding hydrated with provider, wire protocol capability,
 * and encrypted credential details for routing.
 */
public record GatewayChannelCandidate(
        UUID bindingId,
        UUID channelId,
        String channelName,
        String region,
        String providerCode,
        ProtocolFamily protocolFamily,
        List<String> supportedProtocols,
        String defaultWireProtocol,
        String upstreamProtocol,
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
    public GatewayChannelCandidate {
        String inferredDefault = (defaultWireProtocol != null && !defaultWireProtocol.isBlank())
                ? defaultWireProtocol
                : Provider.inferDefaultProtocol(protocolFamily);
        defaultWireProtocol = inferredDefault;
        supportedProtocols = (supportedProtocols == null || supportedProtocols.isEmpty())
                ? List.of(inferredDefault)
                : List.copyOf(supportedProtocols);
    }

    public GatewayChannelCandidate(
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
        this(bindingId, channelId, channelName, region, providerCode, protocolFamily,
                null, null, null, authScheme, resolvedBaseUrl, upstreamModelName,
                priorityTier, weight, effectiveCostDiscountBps, encryptedCredentials,
                credentialKeyVersion, extraHeadersJson, channelRpmLimit, channelTpmLimit,
                channelMaxConcurrency);
    }

    /**
     * Resolves the wire protocol used when dispatching a request to this candidate:
     * <ol>
     *   <li>Explicit binding override ({@code upstreamProtocol}) if set</li>
     *   <li>Caller's {@code inboundProtocol} if listed in {@code supportedProtocols} (no translation needed)</li>
     *   <li>Provider's {@code defaultWireProtocol} (Canonical IR translation)</li>
     * </ol>
     */
    public String resolveOutboundProtocolName(String inboundProtocolName) {
        if (upstreamProtocol != null && !upstreamProtocol.isBlank()) {
            return upstreamProtocol;
        }
        if (inboundProtocolName != null && supportedProtocols.contains(inboundProtocolName)) {
            return inboundProtocolName;
        }
        return defaultWireProtocol;
    }

    public boolean isHomomorphicFor(String inboundProtocolName) {
        return inboundProtocolName != null && inboundProtocolName.equals(resolveOutboundProtocolName(inboundProtocolName));
    }
}
