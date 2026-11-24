package app.pagefault.hyperllm.admin.service;

import app.pagefault.hyperllm.admin.config.AdminCacheSyncService;
import app.pagefault.hyperllm.admin.dto.AdminDtos.ChannelDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.ChannelProbeDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.CreateChannelRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.ProviderDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.UpdateChannelRequest;
import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.enums.ActorType;
import app.pagefault.hyperllm.domain.model.enums.ChannelStatus;
import app.pagefault.hyperllm.domain.model.routing.Channel;
import app.pagefault.hyperllm.domain.model.routing.Provider;
import app.pagefault.hyperllm.domain.repository.AuditLogRepository;
import app.pagefault.hyperllm.domain.repository.ChannelRepository;
import app.pagefault.hyperllm.domain.repository.ModelChannelBindingRepository;
import app.pagefault.hyperllm.domain.repository.ProviderRepository;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.translate.UpstreamEndpointResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminChannelService {

    private final ProviderRepository providerRepository;
    private final ChannelRepository channelRepository;
    private final ModelChannelBindingRepository bindingRepository;
    private final AuditLogRepository auditLogRepository;
    private final AesGcmCipher aesGcmCipher;
    private final ObjectMapper objectMapper;
    private final AdminCacheSyncService cacheSync;
    private final RestClient probeRestClient;

    public List<ProviderDto> listProviders() {
        return providerRepository.listAllActive().stream()
                .map(p -> new ProviderDto(
                        p.id(), p.code(), p.displayName(), p.protocolFamily(),
                        p.defaultBaseUrl(), p.authScheme(), p.status()
                ))
                .toList();
    }

    public PageResponse<ChannelDto> listChannels(PageQuery pageQuery) {
        PageResponse<Channel> page = channelRepository.listActive(pageQuery);
        return new PageResponse<>(
                page.items().stream().map(AdminChannelService::toDto).toList(),
                page.page(), page.pageSize(), page.total()
        );
    }

    public ChannelDto getChannel(UUID channelId) {
        return toDto(requireChannel(channelId));
    }

    @Transactional
    public ChannelDto createChannel(UUID adminId, String clientIp, CreateChannelRequest req) {
        providerRepository.findById(req.providerId())
                .orElseThrow(() -> new HyperLlmException(ErrorCode.CHANNEL_NOT_FOUND, "Provider not found"));
        UUID channelId = UuidV7.next();
        byte[] encryptedCreds = encryptApiKey(channelId, req.apiKey());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Channel channel = new Channel(
                channelId,
                req.providerId(),
                req.name().trim(),
                trimOrNull(req.region()),
                trimOrNull(req.baseUrlOverride()),
                encryptedCreds,
                aesGcmCipher.currentVersion(),
                req.defaultCostDiscountBps() == null ? 10_000 : req.defaultCostDiscountBps(),
                req.rpmLimit(),
                req.tpmLimit(),
                req.maxConcurrency(),
                trimOrNull(req.healthCheckModel()),
                ChannelStatus.ACTIVE,
                null,
                0L,
                now,
                now
        );
        channelRepository.insert(channel);
        recordAudit(adminId, "CHANNEL.CREATE", channelId, clientIp, Map.of("name", channel.name()), now);
        return toDto(channel);
    }

    @Transactional
    public ChannelDto updateChannel(UUID channelId, UUID adminId, String clientIp, UpdateChannelRequest req) {
        Channel existing = requireChannel(channelId);
        byte[] encrypted = (req.apiKey() != null && !req.apiKey().isBlank())
                ? encryptApiKey(channelId, req.apiKey())
                : existing.encryptedCredentials();
        short keyVersion = (req.apiKey() != null && !req.apiKey().isBlank())
                ? aesGcmCipher.currentVersion()
                : existing.credentialKeyVersion();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        boolean ok = channelRepository.update(
                channelId, req.name().trim(), trimOrNull(req.region()), trimOrNull(req.baseUrlOverride()),
                encrypted, keyVersion, req.defaultCostDiscountBps(),
                req.rpmLimit(), req.tpmLimit(), req.maxConcurrency(), trimOrNull(req.healthCheckModel()),
                req.status(), req.version(), now
        );
        if (!ok) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictRoutesAfterCommit(bindingRepository.listActiveModelCodesByChannelId(channelId));
        recordAudit(adminId, "CHANNEL.UPDATE", channelId, clientIp, Map.of("status", req.status().name()), now);
        return toDto(requireChannel(channelId));
    }

    @Transactional
    public void deleteChannel(UUID channelId, long version, UUID adminId, String clientIp) {
        requireChannel(channelId);
        List<String> affectedModels = bindingRepository.listActiveModelCodesByChannelId(channelId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!channelRepository.softDelete(channelId, version, now)) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictRoutesAfterCommit(affectedModels);
        recordAudit(adminId, "CHANNEL.DELETE", channelId, clientIp, Map.of(), now);
    }

    public ChannelProbeDto probeChannel(UUID channelId) {
        Channel channel = requireChannel(channelId);
        Provider provider = providerRepository.findById(channel.providerId())
                .orElseThrow(() -> new HyperLlmException(ErrorCode.CHANNEL_NOT_FOUND, "Provider missing for channel"));
        String apiKey = decryptApiKey(channel);
        String baseUrl = stripTrailingSlash(
                channel.baseUrlOverride() != null ? channel.baseUrlOverride() : provider.defaultBaseUrl()
        );

        long startNanos = System.nanoTime();
        String endpointUrl = "";
        try {
            ProtocolKind probeProtocol = ProtocolKind.parseOrNull(provider.defaultWireProtocol());
            if (probeProtocol == null) {
                probeProtocol = switch (provider.protocolFamily()) {
                    case ANTHROPIC -> ProtocolKind.ANTHROPIC_MESSAGES;
                    case GEMINI -> ProtocolKind.GEMINI_GENERATE_CONTENT;
                    default -> ProtocolKind.OPENAI_CHAT;
                };
            }
            endpointUrl = UpstreamEndpointResolver.buildProbeUrl(baseUrl, probeProtocol);
            Map<String, String> authHeaders = UpstreamEndpointResolver.buildAuthHeaders(
                    provider.authScheme().name(), probeProtocol, apiKey, null, null);
            ResponseEntity<String> response;
            if (probeProtocol == ProtocolKind.ANTHROPIC_MESSAGES) {
                String probeModel = channel.healthCheckModel() != null ? channel.healthCheckModel() : "claude-3-5-haiku-latest";
                String body = objectMapper.writeValueAsString(Map.of(
                        "model", probeModel,
                        "max_tokens", 1,
                        "messages", List.of(Map.of("role", "user", "content", "ping"))
                ));
                response = probeRestClient.post()
                        .uri(endpointUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(h -> authHeaders.forEach(h::set))
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
            } else {
                response = probeRestClient.get()
                        .uri(endpointUrl)
                        .headers(h -> authHeaders.forEach(h::set))
                        .retrieve()
                        .toEntity(String.class);
            }
            long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
            return new ChannelProbeDto(
                    channelId, true, response.getStatusCode().value(), latencyMs, endpointUrl, "Probe succeeded"
            );
        } catch (Exception ex) {
            throw new HyperLlmException(
                    ErrorCode.CHANNEL_PROBE_FAILED,
                    "Upstream probe to " + endpointUrl + " failed: " + ex.getMessage(),
                    ex
            );
        }
    }

    private Channel requireChannel(UUID channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.CHANNEL_NOT_FOUND));
    }

    private byte[] encryptApiKey(UUID channelId, String rawApiKey) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("apiKey", rawApiKey.trim()));
            return aesGcmCipher.encryptUtf8(json, channelId.toString());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt channel credentials", ex);
        }
    }

    private String decryptApiKey(Channel channel) {
        try {
            String json = aesGcmCipher.decryptUtf8(channel.encryptedCredentials(), channel.id().toString());
            JsonNode root = objectMapper.readTree(json);
            return root.path("apiKey").asText("");
        } catch (Exception ex) {
            throw new HyperLlmException(ErrorCode.CHANNEL_PROBE_FAILED, "Failed to decrypt channel credentials", ex);
        }
    }

    private void recordAudit(
            UUID adminId,
            String action,
            UUID resourceId,
            String clientIp,
            Map<String, Object> diff,
            OffsetDateTime now
    ) {
        try {
            auditLogRepository.insert(new AuditLogEntry(
                    UuidV7.next(), ActorType.ADMIN, adminId, null, action,
                    "channels", resourceId.toString(), objectMapper.writeValueAsString(diff),
                    clientIp, null, now
            ));
        } catch (Exception ignored) {
        }
    }

    private static ChannelDto toDto(Channel c) {
        return new ChannelDto(
                c.id(), c.providerId(), c.name(), c.region(), c.baseUrlOverride(),
                c.encryptedCredentials() != null && c.encryptedCredentials().length > 0,
                c.credentialKeyVersion(), c.defaultCostDiscountBps(),
                c.rpmLimit(), c.tpmLimit(), c.maxConcurrency(), c.healthCheckModel(),
                c.status(), c.version(), c.createdAt(), c.updatedAt()
        );
    }

    private static String trimOrNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
