package app.pagefault.hyperllm.admin.service;

import app.pagefault.hyperllm.admin.config.AdminCacheSyncService;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminOrgDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminWalletTopupRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminWalletTopupResponse;
import app.pagefault.hyperllm.admin.dto.AdminDtos.TopupType;
import app.pagefault.hyperllm.admin.dto.AdminDtos.UpdateOrgStatusRequest;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.ActorType;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.repository.ApiKeyRepository;
import app.pagefault.hyperllm.domain.repository.AuditLogRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminTenantAndBillingService {

    private final OrganizationRepository organizationRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final AuditLogRepository auditLogRepository;
    private final AdminCacheSyncService cacheSync;
    private final ObjectMapper objectMapper;

    public PageResponse<AdminOrgDto> listOrganizations(OrgStatus statusFilter, PageQuery pageQuery) {
        PageResponse<Organization> page = organizationRepository.listPage(statusFilter, pageQuery);
        return new PageResponse<>(
                page.items().stream().map(AdminTenantAndBillingService::toOrgDto).toList(),
                page.page(), page.pageSize(), page.total()
        );
    }

    @Transactional
    public AdminOrgDto updateOrgStatus(UUID orgId, UUID adminId, String clientIp, UpdateOrgStatusRequest req) {
        organizationRepository.findById(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!organizationRepository.updateStatus(orgId, req.status(), req.version(), now)) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        cacheSync.syncOrgStatusAfterCommit(orgId, req.status().name(), apiKeyRepository.listActiveKeyHashesByOrg(orgId));
        recordAudit(adminId, orgId, "ORG.STATUS_UPDATE", clientIp, Map.of("status", req.status().name()), now);
        return toOrgDto(organizationRepository.findById(orgId).orElseThrow());
    }

    @Transactional
    public AdminWalletTopupResponse topupWallet(
            UUID orgId,
            UUID adminId,
            String clientIp,
            AdminWalletTopupRequest req
    ) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND));
        Wallet current = walletRepository.findByOrgId(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND, "Wallet not found for org"));

        boolean isCash = req.topupType() == TopupType.CASH;
        long cashDelta = isCash ? req.amountMicros() : 0L;
        long bonusDelta = isCash ? 0L : req.amountMicros();
        long expectedCashAfter = current.cashBalanceMicros() + cashDelta;
        long expectedBonusAfter = current.bonusBalanceMicros() + bonusDelta;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        boolean inserted = walletLedgerRepository.insertIdempotent(new WalletLedgerEntry(
                UuidV7.next(),
                orgId,
                null,
                null,
                isCash ? LedgerType.RECHARGE : LedgerType.BONUS_GRANT,
                "admin-topup:" + orgId + ":" + req.idempotencyKey().trim(),
                cashDelta,
                bonusDelta,
                0L,
                expectedCashAfter,
                expectedBonusAfter,
                null,
                null,
                null,
                null,
                req.description() == null ? "Admin Phase 1 test topup" : req.description().trim(),
                now
        ));

        if (inserted) {
            walletRepository.applyDelta(
                    orgId,
                    cashDelta,
                    bonusDelta,
                    cashDelta,
                    bonusDelta,
                    0L,
                    0L,
                    now
            );
            Wallet updated = walletRepository.findByOrgId(orgId).orElseThrow();
            cacheSync.incrementWalletBalanceAfterCommit(orgId, cashDelta, bonusDelta, updated, org.status().name());
            recordAudit(adminId, orgId, "WALLET.ADMIN_TOPUP", clientIp,
                    Map.of("type", req.topupType().name(), "amountMicros", req.amountMicros()), now);
            return new AdminWalletTopupResponse(
                    orgId, true, updated.cashBalanceMicros(), updated.bonusBalanceMicros(), updated.version()
            );
        }

        return new AdminWalletTopupResponse(
                orgId, false, current.cashBalanceMicros(), current.bonusBalanceMicros(), current.version()
        );
    }

    private void recordAudit(
            UUID adminId,
            UUID orgId,
            String action,
            String clientIp,
            Map<String, Object> diff,
            OffsetDateTime now
    ) {
        try {
            auditLogRepository.insert(new AuditLogEntry(
                    UuidV7.next(), ActorType.ADMIN, adminId, orgId, action,
                    "organizations", orgId.toString(), objectMapper.writeValueAsString(diff),
                    clientIp, null, now
            ));
        } catch (Exception ignored) {
        }
    }

    private static AdminOrgDto toOrgDto(Organization o) {
        return new AdminOrgDto(
                o.id(), o.name(), o.slug(), o.ownerUserId(), o.tierId(),
                o.discountRatioBps(), o.maxConcurrency(), o.personal(), o.status(),
                o.version(), o.createdAt(), o.updatedAt()
        );
    }
}
