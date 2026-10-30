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
import app.pagefault.hyperllm.common.idempotency.DuplicatePolicy;
import app.pagefault.hyperllm.common.idempotency.Idempotent;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.billing.WalletCreditOutbox;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.enums.ActorType;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminTenantAndBillingService {

    private static final String TOPUP_AUTHORIZED = "WALLET.TOPUP_AUTHORIZED";

    private final OrganizationRepository organizationRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final WalletRepository walletRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final WalletCreditOutbox walletCreditOutbox;
    private final AuditLogRepository auditLogRepository;
    private final AdminCacheSyncService cacheSync;
    private final ObjectMapper objectMapper;

    public PageResponse<AdminOrgDto> listOrganizations(OrgStatus statusFilter, PageQuery pageQuery) {
        PageResponse<Organization> page = organizationRepository.listPage(statusFilter, pageQuery);
        // One extra statement for the whole page rather than one per row. The values are the ledger
        // folded snapshot: a list has no business showing a live number nobody can reconcile.
        Map<UUID, WalletRepository.Balance> balances = walletRepository.findBalancesByOrgIds(
                page.items().stream().map(Organization::id).toList());
        return new PageResponse<>(
                page.items().stream()
                        .map(org -> toOrgDto(org, balances.get(org.id())))
                        .toList(),
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

    @Idempotent(namespace = "admin-topup", key = "#p0 + ':' + #p3.idempotencyKey().trim()",
            onDuplicate = DuplicatePolicy.REPLAY)
    @Transactional
    public AdminWalletTopupResponse topupWallet(
            UUID orgId, UUID adminId, String clientIp, AdminWalletTopupRequest req
    ) {
        walletRepository.findByOrgIdForUpdate(orgId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_ORG_NOT_FOUND));
        String requestKey = req.idempotencyKey().trim();
        String requestHash = hashRequestKey(requestKey);
        // The durable guard survives Redis loss, API idempotency expiry and outbox purges.
        var existing = auditLogRepository.findFirstResourceEventId(orgId, TOPUP_AUTHORIZED, requestHash)
                .or(() -> walletLedgerRepository.findFirstByRequestKey(orgId, "admin-topup:" + orgId + ":" + requestKey));
        if (existing.isPresent()) {
            return new AdminWalletTopupResponse(orgId, existing.orElseThrow());
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean cash = req.topupType() == TopupType.CASH;
        var event = walletCreditOutbox.append(orgId, "admin-credit:" + orgId + ":" + requestHash,
                cash ? req.amountMicros() : 0L, cash ? 0L : req.amountMicros(),
                BillingMoneyEvent.CreditSource.ADMIN, req.description(), now);
        auditLogRepository.insert(new AuditLogEntry(event.eventId(), ActorType.ADMIN, adminId, orgId,
                TOPUP_AUTHORIZED, "BILLING_EVENT", requestHash, null, clientIp, null, event.occurredAt()));
        return new AdminWalletTopupResponse(orgId, event.eventId());
    }

    private static String hashRequestKey(String requestKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(requestKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
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
        return toOrgDto(o, null);
    }

    private static AdminOrgDto toOrgDto(Organization o, WalletRepository.Balance balance) {
        return new AdminOrgDto(
                o.id(), o.name(), o.slug(), o.ownerUserId(), o.tierId(),
                o.discountRatioBps(), o.maxConcurrency(), o.personal(), o.status(),
                balance == null ? null : balance.cashMicros(),
                balance == null ? null : balance.bonusMicros(),
                true,
                o.version(), o.createdAt(), o.updatedAt()
        );
    }
}
