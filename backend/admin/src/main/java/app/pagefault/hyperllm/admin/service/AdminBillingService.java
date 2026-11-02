package app.pagefault.hyperllm.admin.service;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.CodeBatchResponse;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.CreatePackSkuRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.CreateVoucherRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.PackSkuAdminDto;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.PaymentAnomalyDto;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.PaymentOrderAdminDto;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.RefundOrderRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.ResolveAnomalyRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.UpdatePackSkuRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.UpdateVoucherStatusRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.VoucherAdminDto;
import app.pagefault.hyperllm.common.billing.RedemptionCodeFormat;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.billing.RedemptionCode;
import app.pagefault.hyperllm.domain.model.billing.ResourcePackSku;
import app.pagefault.hyperllm.domain.model.billing.Voucher;
import app.pagefault.hyperllm.domain.model.billing.VoucherCodeBatch;
import app.pagefault.hyperllm.domain.model.enums.ActorType;
import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyStatus;
import app.pagefault.hyperllm.domain.model.enums.RedemptionStatus;
import app.pagefault.hyperllm.domain.model.payment.PaymentAnomaly;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import app.pagefault.hyperllm.domain.repository.AuditLogRepository;
import app.pagefault.hyperllm.domain.repository.PaymentAnomalyRepository;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import app.pagefault.hyperllm.domain.repository.RedemptionCodeRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackSkuRepository;
import app.pagefault.hyperllm.domain.repository.VoucherCodeBatchRepository;
import app.pagefault.hyperllm.domain.repository.VoucherRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminBillingService {

    private final ResourcePackSkuRepository skus;
    private final VoucherRepository vouchers;
    private final VoucherCodeBatchRepository batches;
    private final RedemptionCodeRepository codes;
    private final PaymentOrderRepository orders;
    private final PaymentAnomalyRepository anomalies;
    private final AuditLogRepository audits;
    private final AdminProperties properties;
    private final ObjectMapper mapper;

    @Transactional
    public PackSkuAdminDto createSku(UUID adminId, String clientIp, CreatePackSkuRequest request) {
        if (skus.findByCode(request.code()).isPresent()) {
            throw new HyperLlmException(ErrorCode.PACK_SKU_CODE_EXISTS);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ResourcePackSku sku = new ResourcePackSku(UuidV7.next(), request.code(), request.name(),
                request.unitType(), request.applicableModels(), request.totalUnits(),
                request.priceMicros(), request.validDays(), request.status(), adminId, 0L, now, now);
        skus.insert(sku);
        audit(adminId, null, "PACK_SKU.CREATE", "resource_pack_sku", sku.id().toString(),
                request, clientIp, now);
        return toSkuDto(sku);
    }

    @Transactional
    public PackSkuAdminDto updateSku(UUID adminId, String clientIp, UUID skuId, UpdatePackSkuRequest request) {
        ResourcePackSku current = skus.findById(skuId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.PACK_SKU_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ResourcePackSku next = new ResourcePackSku(current.id(), current.code(), request.name(),
                request.unitType(), request.applicableModels(), request.totalUnits(),
                request.priceMicros(), request.validDays(), request.status(),
                current.createdByAdminId(), current.version(), current.createdAt(), now);
        if (!skus.update(next, request.version())) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        audit(adminId, null, "PACK_SKU.UPDATE", "resource_pack_sku", skuId.toString(),
                request, clientIp, now);
        return toSkuDto(skus.findById(skuId).orElseThrow());
    }

    public PageResponse<PackSkuAdminDto> listSkus(PackSkuStatus status, PageQuery pageQuery) {
        PageResponse<ResourcePackSku> page = skus.listPage(status, pageQuery);
        return new PageResponse<>(page.items().stream().map(AdminBillingService::toSkuDto).toList(),
                page.page(), page.pageSize(), page.total());
    }

    @Transactional
    public VoucherAdminDto createVoucher(UUID adminId, String clientIp, CreateVoucherRequest request) {
        if (request.validDays() != null && request.fixedExpiresAt() != null) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT,
                    "A voucher expires by duration or by a fixed instant, not both");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Voucher voucher = new Voucher(UuidV7.next(), request.name(), request.voucherType(),
                request.amountMicros(), request.applicableModels(), request.validDays(),
                request.fixedExpiresAt(), request.maxRedemptionsTotal(), request.maxRedemptionsPerOrg(),
                app.pagefault.hyperllm.domain.model.enums.VoucherStatus.ACTIVE, adminId, 0L, now, now);
        vouchers.insert(voucher);
        audit(adminId, null, "VOUCHER.CREATE", "voucher", voucher.id().toString(), request, clientIp, now);
        return toVoucherDto(voucher);
    }

    public PageResponse<VoucherAdminDto> listVouchers(PageQuery pageQuery) {
        PageResponse<Voucher> page = vouchers.listPage(pageQuery);
        return new PageResponse<>(page.items().stream().map(AdminBillingService::toVoucherDto).toList(),
                page.page(), page.pageSize(), page.total());
    }

    @Transactional
    public VoucherAdminDto updateVoucherStatus(UUID adminId, String clientIp, UUID voucherId,
                                               UpdateVoucherStatusRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!vouchers.updateStatus(voucherId, request.status(), request.version(), now)) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        audit(adminId, null, "VOUCHER.STATUS", "voucher", voucherId.toString(), request, clientIp, now);
        return toVoucherDto(vouchers.findById(voucherId).orElseThrow());
    }

    /** Generates the batch and returns the plaintext once; only the digest is stored. */
    @Transactional
    public CodeBatchResponse createCodeBatch(UUID adminId, String clientIp, UUID voucherId, int quantity) {
        Voucher voucher = vouchers.findById(voucherId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.VOUCHER_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID batchId = UuidV7.next();
        batches.insert(new VoucherCodeBatch(batchId, voucherId, quantity, adminId, now));

        List<String> plaintexts = new ArrayList<>(quantity);
        List<RedemptionCode> rows = new ArrayList<>(quantity);
        Set<String> seen = new HashSet<>();
        String pepper = pepper();
        while (plaintexts.size() < quantity) {
            String plaintext = RedemptionCodeFormat.generate();
            String normalized = RedemptionCodeFormat.normalize(plaintext);
            if (!seen.add(normalized)) {
                continue;
            }
            plaintexts.add(plaintext);
            rows.add(new RedemptionCode(UuidV7.next(), voucherId, batchId,
                    RedemptionCodeFormat.hash(normalized, pepper), RedemptionCodeFormat.hint(plaintext),
                    null, null, null, voucher.fixedExpiresAt(), RedemptionStatus.UNUSED, null, now));
        }
        codes.insertBatch(rows);
        audit(adminId, null, "VOUCHER.CODES_CREATE", "voucher_code_batch", batchId.toString(),
                java.util.Map.of("voucherId", voucherId, "quantity", quantity), clientIp, now);
        return new CodeBatchResponse(batchId, voucherId, quantity, plaintexts);
    }

    public PageResponse<PaymentAnomalyDto> listAnomalies(PaymentAnomalyStatus status, PageQuery pageQuery) {
        PageResponse<PaymentAnomaly> page = anomalies.list(status, pageQuery);
        return new PageResponse<>(page.items().stream().map(AdminBillingService::toAnomalyDto).toList(),
                page.page(), page.pageSize(), page.total());
    }

    @Transactional
    public void resolveAnomaly(UUID adminId, String clientIp, UUID anomalyId, ResolveAnomalyRequest request) {
        if (request.status() == PaymentAnomalyStatus.OPEN) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "Resolve to RESOLVED or IGNORED");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!anomalies.resolve(anomalyId, adminId, request.status(), request.resolution(), now)) {
            throw new HyperLlmException(ErrorCode.PAYMENT_ANOMALY_NOT_FOUND);
        }
        audit(adminId, null, "PAYMENT_ANOMALY.RESOLVE", "payment_anomaly", anomalyId.toString(),
                request, clientIp, now);
    }

    @Transactional
    public PaymentOrderAdminDto refundOrder(UUID adminId, String clientIp, UUID orderId,
                                            RefundOrderRequest request) {
        PaymentOrder order = orders.findById(orderId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (order.status() != OrderStatus.PAID) {
            throw new HyperLlmException(ErrorCode.PAYMENT_STATE_CONFLICT);
        }
        if (request.refundedAmountMinor() > order.payAmountMinor()) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT,
                    "A refund cannot exceed the paid amount");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!orders.markRefunded(orderId, request.refundedAmountMinor(), now, order.version(), now)) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        audit(adminId, order.orgId(), "PAYMENT_ORDER.REFUND", "payment_order", orderId.toString(),
                request, clientIp, now);
        return toOrderDto(orders.findById(orderId).orElseThrow());
    }

    public PageResponse<PaymentOrderAdminDto> listOrders(UUID orgId, PageQuery pageQuery) {
        PageResponse<PaymentOrder> page = orders.listByOrg(orgId, pageQuery);
        return new PageResponse<>(page.items().stream().map(AdminBillingService::toOrderDto).toList(),
                page.page(), page.pageSize(), page.total());
    }

    private String pepper() {
        String configured = properties.getSecurity().getVoucherCodePepper();
        return configured == null || configured.isBlank()
                ? properties.getSecurity().getKeyPepperV1() : configured;
    }

    private void audit(UUID adminId, UUID orgId, String action, String resourceType, String resourceId,
                       Object diff, String clientIp, OffsetDateTime now) {
        try {
            audits.insert(new AuditLogEntry(UuidV7.next(), ActorType.ADMIN, adminId, orgId, action,
                    resourceType, resourceId, mapper.writeValueAsString(diff), clientIp, null, now));
        } catch (Exception ignored) {
            // The audit is best effort; the business row is the source of truth.
        }
    }

    private static PackSkuAdminDto toSkuDto(ResourcePackSku sku) {
        return new PackSkuAdminDto(sku.id(), sku.code(), sku.name(), sku.unitType(),
                sku.applicableModels(), sku.totalUnits(), sku.priceMicros(), sku.validDays(),
                sku.status(), sku.version(), sku.createdAt(), sku.updatedAt());
    }

    private static VoucherAdminDto toVoucherDto(Voucher voucher) {
        return new VoucherAdminDto(voucher.id(), voucher.name(), voucher.voucherType(),
                voucher.amountMicros(), voucher.applicableModels(), voucher.validDays(),
                voucher.fixedExpiresAt(), voucher.maxRedemptionsTotal(),
                voucher.maxRedemptionsPerOrg(), voucher.status(), voucher.version(),
                voucher.createdAt());
    }

    private static PaymentAnomalyDto toAnomalyDto(PaymentAnomaly anomaly) {
        return new PaymentAnomalyDto(anomaly.id(), anomaly.orgId(), anomaly.orderId(),
                anomaly.paymentChannel(), anomaly.kind(), anomaly.status(), anomaly.detailJson(),
                anomaly.createdAt());
    }

    private static PaymentOrderAdminDto toOrderDto(PaymentOrder order) {
        return new PaymentOrderAdminDto(order.id(), order.orderNo(), order.orgId(), order.orderType(),
                order.paymentChannel(), order.payCurrency(), order.payAmountMinor(), order.creditMicros(),
                order.status(), order.externalTxId(), order.fulfillmentEventId(), order.paidAt(),
                order.refundedAt(), order.refundedAmountMinor(), order.createdAt());
    }
}
