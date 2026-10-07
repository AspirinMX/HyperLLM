package app.pagefault.hyperllm.console.service;

import app.pagefault.hyperllm.common.cache.SandboxTokenPayload;
import app.pagefault.hyperllm.common.crypto.ApiKeyGenerator;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.console.config.ConsoleCacheSyncService;
import app.pagefault.hyperllm.console.dto.BillingDtos.ActivePricingDto;
import app.pagefault.hyperllm.console.dto.BillingDtos.EphemeralTokenRequest;
import app.pagefault.hyperllm.console.dto.BillingDtos.EphemeralTokenResponse;
import app.pagefault.hyperllm.console.dto.BillingDtos.PublicModelCatalogDto;
import app.pagefault.hyperllm.console.dto.BillingDtos.WalletDto;
import app.pagefault.hyperllm.console.dto.BillingDtos.WalletLedgerDto;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.repository.PricingRuleRepository;
import app.pagefault.hyperllm.domain.repository.VirtualModelRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConsoleCatalogAndWalletService {

    private static final int SANDBOX_TTL_SECONDS = 300;

    private final ConsoleRbacService rbac;
    private final ConsoleCacheSyncService cacheSync;
    private final VirtualModelRepository virtualModelRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;

    public List<PublicModelCatalogDto> listPublicModels() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return virtualModelRepository.listPublicActive().stream()
                .map(model -> toCatalogDto(model, pricingRuleRepository.findEffectiveAt(model.id(), now).orElse(null)))
                .toList();
    }

    public WalletDto getWallet(UUID orgId, UUID userId) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN, OrgRole.BILLING);
        Wallet wallet = walletRepository.findByOrgId(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND, "Wallet not found for org"));
        return new WalletDto(
                wallet.orgId(), wallet.cashBalanceMicros(), wallet.bonusBalanceMicros(),
                wallet.totalRechargedMicros(), wallet.totalBonusGrantedMicros(),
                wallet.totalConsumedCashMicros(), wallet.totalConsumedBonusMicros(),
                wallet.lowBalanceThresholdMicros(), wallet.version(), wallet.updatedAt()
        );
    }

    public PageResponse<WalletLedgerDto> listWalletLedger(UUID orgId, UUID userId, PageQuery pageQuery) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN, OrgRole.BILLING);
        PageResponse<WalletLedgerEntry> page = walletLedgerRepository.listByOrg(orgId, pageQuery);
        return new PageResponse<>(
                page.items().stream().map(e -> new WalletLedgerDto(
                        e.id(), e.orgId(), e.projectId(), e.apiKeyId(), e.ledgerType(),
                        e.cashDeltaMicros(), e.bonusDeltaMicros(), e.packDeltaUnits(),
                        e.cashBalanceAfterMicros(), e.bonusBalanceAfterMicros(),
                        e.refRequestId(), e.description(), e.createdAt()
                )).toList(),
                page.page(), page.pageSize(), page.total()
        );
    }

    public EphemeralTokenResponse issueSandboxToken(UUID userId, EphemeralTokenRequest req) {
        ConsoleRbacService.ProjectAccess access = rbac.requireKeyManageAccess(req.projectId(), userId);
        String rawToken = ApiKeyGenerator.generateSandboxToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(SANDBOX_TTL_SECONDS);
        SandboxTokenPayload payload = new SandboxTokenPayload(
                access.org().id(),
                access.project().id(),
                userId,
                access.project().allowedModels(),
                expiresAt
        );
        cacheSync.storeSandboxToken(HmacSha256.sha256Hex(rawToken), payload, Duration.ofSeconds(SANDBOX_TTL_SECONDS));
        return new EphemeralTokenResponse(rawToken, SANDBOX_TTL_SECONDS, expiresAt);
    }

    private static PublicModelCatalogDto toCatalogDto(VirtualModel model, PricingRule rule) {
        ActivePricingDto pricingDto = rule == null ? null : new ActivePricingDto(
                rule.billingMode(),
                rule.inputPriceMicros1m(),
                rule.outputPriceMicros1m(),
                rule.cacheReadPriceMicros1m(),
                rule.cacheWritePriceMicros1m(),
                rule.reasoningPriceMicros1m(),
                rule.perCallMicros(),
                rule.effectiveFrom(),
                rule.effectiveTo()
        );
        return new PublicModelCatalogDto(
                model.id(), model.modelCode(), model.displayName(), model.providerBadge(),
                model.capabilityType(), model.nativeProtocol(), model.contextWindow(),
                model.maxOutputTokens(), model.supportsTools(), model.supportsVision(),
                model.supportsThinking(), model.supportsPromptCache(), pricingDto
        );
    }
}
