package app.pagefault.hyperllm.admin.service;

import app.pagefault.hyperllm.admin.config.AdminCacheSyncService;
import app.pagefault.hyperllm.admin.dto.AdminDtos.BindingDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.BindingItemRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.CreateVirtualModelRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.PricingRuleDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.PublishPricingRuleRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.ReplaceBindingsRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.UpdateVirtualModelRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.VirtualModelDto;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.BindingStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.routing.ModelChannelBinding;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.repository.ChannelRepository;
import app.pagefault.hyperllm.domain.repository.ModelChannelBindingRepository;
import app.pagefault.hyperllm.domain.repository.PricingRuleRepository;
import app.pagefault.hyperllm.domain.repository.VirtualModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminModelAndPricingService {

    private final VirtualModelRepository modelRepository;
    private final ChannelRepository channelRepository;
    private final ModelChannelBindingRepository bindingRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final AdminCacheSyncService cacheSync;

    public PageResponse<VirtualModelDto> listModels(PageQuery pageQuery) {
        PageResponse<VirtualModel> page = modelRepository.listPage(pageQuery);
        return new PageResponse<>(
                page.items().stream().map(AdminModelAndPricingService::toModelDto).toList(),
                page.page(), page.pageSize(), page.total()
        );
    }

    public VirtualModelDto getModel(UUID modelId) {
        return toModelDto(requireModel(modelId));
    }

    @Transactional
    public VirtualModelDto createModel(CreateVirtualModelRequest req) {
        String code = req.modelCode().trim();
        if (modelRepository.findActiveByModelCode(code).isPresent()) {
            throw new HyperLlmException(ErrorCode.MODEL_CODE_EXISTS);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean allowTranslation = req.allowProtocolTranslation() == null || req.allowProtocolTranslation();
        String policy = normalizeTranslationPolicy(req.translationPolicy());
        VirtualModel model = new VirtualModel(
                UuidV7.next(), code, req.displayName().trim(), req.providerBadge(),
                req.capabilityType(), req.nativeProtocol(), req.contextWindow(), req.maxOutputTokens(),
                req.supportsTools(), req.supportsVision(), req.supportsThinking(), req.supportsPromptCache(),
                req.routingStrategy(), req.stickyCacheEnabled(), req.stickyTtlSeconds(),
                req.visibility(), req.fallbackModelCode(), ModelStatus.ACTIVE, "{}",
                allowTranslation, policy,
                null, 0L, now, now
        );
        modelRepository.insert(model);
        return toModelDto(model);
    }

    @Transactional
    public VirtualModelDto updateModel(UUID modelId, UpdateVirtualModelRequest req) {
        VirtualModel existing = requireModel(modelId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean allowTranslation = req.allowProtocolTranslation() != null
                ? req.allowProtocolTranslation() : existing.allowProtocolTranslation();
        String policy = req.translationPolicy() != null && !req.translationPolicy().isBlank()
                ? normalizeTranslationPolicy(req.translationPolicy()) : existing.translationPolicy();
        boolean ok = modelRepository.update(
                modelId, req.displayName().trim(), req.providerBadge(), req.contextWindow(), req.maxOutputTokens(),
                req.supportsTools(), req.supportsVision(), req.supportsThinking(), req.supportsPromptCache(),
                req.routingStrategy(), req.stickyCacheEnabled(), req.stickyTtlSeconds(), req.visibility(),
                req.fallbackModelCode(), req.status(), "{}", allowTranslation, policy, req.version(), now
        );
        if (!ok) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictRoutesAfterCommit(List.of(existing.modelCode()));
        return toModelDto(requireModel(modelId));
    }

    @Transactional
    public void deleteModel(UUID modelId, long version) {
        VirtualModel existing = requireModel(modelId);
        if (!modelRepository.softDelete(modelId, version, OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.evictRoutesAfterCommit(List.of(existing.modelCode()));
    }

    public List<BindingDto> listBindings(UUID modelId) {
        requireModel(modelId);
        return bindingRepository.listByModelId(modelId).stream()
                .map(AdminModelAndPricingService::toBindingDto)
                .toList();
    }

    @Transactional
    public List<BindingDto> replaceBindings(UUID modelId, ReplaceBindingsRequest req) {
        VirtualModel model = requireModel(modelId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<ModelChannelBinding> next = new ArrayList<>(req.bindings().size());
        for (BindingItemRequest item : req.bindings()) {
            channelRepository.findById(item.channelId())
                    .orElseThrow(() -> new HyperLlmException(ErrorCode.CHANNEL_NOT_FOUND, "Channel not found: " + item.channelId()));
            String upstreamProtocol = (item.upstreamProtocol() == null || item.upstreamProtocol().isBlank())
                    ? null : item.upstreamProtocol().trim();
            next.add(new ModelChannelBinding(
                    UuidV7.next(),
                    modelId,
                    item.channelId(),
                    item.upstreamModelName().trim(),
                    upstreamProtocol,
                    item.priorityTier(),
                    item.weight(),
                    item.costDiscountBpsOverride(),
                    null,
                    "{}",
                    item.status() == null ? BindingStatus.ACTIVE : item.status(),
                    null,
                    0L,
                    now,
                    now
            ));
        }
        bindingRepository.replaceForModel(modelId, next);
        cacheSync.evictRoutesAfterCommit(List.of(model.modelCode()));
        return listBindings(modelId);
    }

    @Transactional
    public void clearBindings(UUID modelId) {
        VirtualModel model = requireModel(modelId);
        bindingRepository.replaceForModel(modelId, List.of());
        cacheSync.evictRoutesAfterCommit(List.of(model.modelCode()));
    }

    public List<PricingRuleDto> listPricingRules(UUID modelId) {
        requireModel(modelId);
        return pricingRuleRepository.listByModelId(modelId).stream()
                .map(AdminModelAndPricingService::toPricingDto)
                .toList();
    }

    @Transactional
    public PricingRuleDto publishPricingRule(UUID modelId, UUID adminId, PublishPricingRuleRequest req) {
        VirtualModel model = requireModel(modelId);
        if (req.effectiveTo() != null && !req.effectiveTo().isAfter(req.effectiveFrom())) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "effectiveTo must be after effectiveFrom");
        }
        if (pricingRuleRepository.hasOverlappingWindow(modelId, req.effectiveFrom(), req.effectiveTo())) {
            throw new HyperLlmException(ErrorCode.PRICING_WINDOW_OVERLAP);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PricingRule rule = new PricingRule(
                UuidV7.next(), modelId, req.billingMode(),
                req.inputPriceMicros1m(), req.outputPriceMicros1m(),
                req.cacheReadPriceMicros1m(), req.cacheWritePriceMicros1m(),
                req.reasoningPriceMicros1m(), req.perCallMicros(),
                "[]", "{}", req.effectiveFrom(), req.effectiveTo(), adminId, now
        );
        pricingRuleRepository.insert(rule);
        cacheSync.evictRoutesAfterCommit(List.of(model.modelCode()));
        return toPricingDto(rule);
    }

    private VirtualModel requireModel(UUID modelId) {
        return modelRepository.findById(modelId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.MODEL_NOT_FOUND));
    }

    private static String normalizeTranslationPolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "BEST_EFFORT";
        }
        String normalized = raw.trim().toUpperCase();
        if (!"BEST_EFFORT".equals(normalized) && !"STRICT".equals(normalized)) {
            throw new HyperLlmException(ErrorCode.VALIDATION_FAILED, "Unsupported translationPolicy: " + raw);
        }
        return normalized;
    }

    private static VirtualModelDto toModelDto(VirtualModel m) {
        return new VirtualModelDto(
                m.id(), m.modelCode(), m.displayName(), m.providerBadge(),
                m.capabilityType(), m.nativeProtocol(), m.contextWindow(), m.maxOutputTokens(),
                m.supportsTools(), m.supportsVision(), m.supportsThinking(), m.supportsPromptCache(),
                m.routingStrategy(), m.stickyCacheEnabled(), m.stickyTtlSeconds(),
                m.visibility(), m.fallbackModelCode(), m.status(),
                m.allowProtocolTranslation(), m.translationPolicy(),
                m.version(), m.createdAt(), m.updatedAt()
        );
    }

    private static BindingDto toBindingDto(ModelChannelBinding b) {
        return new BindingDto(
                b.id(), b.virtualModelId(), b.channelId(), b.upstreamModelName(), b.upstreamProtocol(),
                b.priorityTier(), b.weight(), b.costDiscountBpsOverride(), b.status(), b.updatedAt()
        );
    }

    private static PricingRuleDto toPricingDto(PricingRule r) {
        return new PricingRuleDto(
                r.id(), r.virtualModelId(), r.billingMode(),
                r.inputPriceMicros1m(), r.outputPriceMicros1m(),
                r.cacheReadPriceMicros1m(), r.cacheWritePriceMicros1m(),
                r.reasoningPriceMicros1m(), r.perCallMicros(),
                r.effectiveFrom(), r.effectiveTo(), r.createdAt()
        );
    }
}
