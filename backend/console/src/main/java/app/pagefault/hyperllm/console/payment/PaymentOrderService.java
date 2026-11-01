package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.idempotency.DuplicatePolicy;
import app.pagefault.hyperllm.common.idempotency.Idempotent;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.console.dto.PaymentDtos.CreatePaymentOrderRequest;
import app.pagefault.hyperllm.console.dto.PaymentDtos.PackSkuDto;
import app.pagefault.hyperllm.console.dto.PaymentDtos.PaymentInstructionDto;
import app.pagefault.hyperllm.console.dto.PaymentDtos.PaymentOrderDto;
import app.pagefault.hyperllm.console.service.ConsoleRbacService;
import app.pagefault.hyperllm.domain.model.billing.ResourcePackSku;
import app.pagefault.hyperllm.domain.model.enums.OrderStatus;
import app.pagefault.hyperllm.domain.model.enums.OrderType;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import app.pagefault.hyperllm.domain.repository.PaymentOrderRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackSkuRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentOrderService {

    private final ConsoleRbacService rbac;
    private final PaymentOrderRepository orders;
    private final ResourcePackSkuRepository skus;
    private final PaymentGatewayRegistry gateways;
    private final PaymentOrderWriter writer;
    private final WalletLedgerRepository ledger;
    private final PaymentProperties properties;
    private final ObjectMapper mapper;

    @Idempotent(namespace = "payment-create", key = "#p0 + ':' + #p3 + ':' + #p2",
            onDuplicate = DuplicatePolicy.REPLAY, inFlightSeconds = 120, resultSeconds = 3600)
    public PaymentOrderDto create(UUID orgId, UUID userId, CreatePaymentOrderRequest request,
                                  String idempotencyKey) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.BILLING);
        String key = requireIdempotencyKey(idempotencyKey);
        PaymentChannel channel = request.paymentChannel();
        PaymentGateway gateway = gateways.require(channel);
        BigDecimal rate = PaymentAmounts.canonicalRate(properties.fxRate(properties.currency(channel)));
        String currency = properties.currency(channel);

        ResourcePackSku sku = null;
        long payAmountMinor;
        long creditMicros;
        String packSpecJson = null;
        if (request.orderType() == OrderType.WALLET_TOPUP) {
            if (request.payAmountMinor() == null || request.payAmountMinor() <= 0L) {
                throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT,
                        "A top-up requires a positive amount");
            }
            payAmountMinor = request.payAmountMinor();
            creditMicros = PaymentAmounts.creditMicros(payAmountMinor, rate);
        } else {
            if (request.packSkuId() == null) {
                throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "A pack purchase requires a SKU");
            }
            sku = skus.findById(request.packSkuId())
                    .orElseThrow(() -> new HyperLlmException(ErrorCode.PACK_SKU_NOT_FOUND));
            if (sku.status() != PackSkuStatus.ACTIVE) {
                throw new HyperLlmException(ErrorCode.PACK_SKU_NOT_PURCHASABLE);
            }
            payAmountMinor = PaymentAmounts.payAmountMinor(sku.priceMicros(), rate);
            creditMicros = 0L;
            packSpecJson = writeJson(new PackSpecSnapshot(sku.id(), sku.code(), sku.name(),
                    sku.unitType().name(), sku.applicableModels(), sku.totalUnits(), sku.validDays()));
        }
        String fingerprint = HmacSha256.sha256Hex(String.join("|",
                request.orderType().name(), channel.name(), currency,
                Long.toString(payAmountMinor), sku == null ? "" : sku.id().toString()));

        var existing = orders.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return resume(existing.get(), fingerprint, gateway);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String orderNo = "HL" + UuidV7.next().toString().replace("-", "");
        PaymentOrder candidate = new PaymentOrder(
                UuidV7.next(), orderNo, orgId, userId, request.orderType(), channel, currency,
                payAmountMinor, rate, creditMicros, payAmountMinor,
                sku == null ? null : sku.id(), packSpecJson, fingerprint, null, null,
                null, key, OrderStatus.PENDING, null, null, null, null, null, null, null, 0L,
                now.plusMinutes(properties.getOrderTtlMinutes()), 0L, now, now);

        PaymentOrderWriter.Reservation reservation = writer.reserve(candidate);
        if (!reservation.created()) {
            return existingOrder(reservation.order(), fingerprint);
        }
        PaymentOrder order = reservation.order();
        String subject = sku == null ? "HyperLLM wallet top-up" : sku.name();
        try {
            PaymentGateway.PaymentInstruction instruction = gateway.create(order, subject);
            PaymentInstructionDto dto = new PaymentInstructionDto(instruction.action(), instruction.method(),
                    instruction.fields(), instruction.redirectUrl(), instruction.codeUrl());
            PaymentOrder attached = writer.attachInstruction(order.id(), instruction.channelRef(),
                    writeJson(dto));
            return toDto(attached);
        } catch (PaymentChannelException ex) {
            writer.markFailed(order.id(), "CHANNEL_CREATE_FAILED", ex.getMessage());
            throw new HyperLlmException(ErrorCode.PAYMENT_CHANNEL_UNAVAILABLE, "Payment channel is unavailable");
        }
    }

    public PaymentOrderDto get(UUID orgId, UUID orderId, UUID userId) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN, OrgRole.BILLING);
        PaymentOrder order = orders.findById(orderId)
                .filter(candidate -> candidate.orgId().equals(orgId))
                .orElseThrow(() -> new HyperLlmException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        return toDto(order);
    }

    public PageResponse<PaymentOrderDto> list(UUID orgId, UUID userId, PageQuery pageQuery) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN, OrgRole.BILLING);
        PageResponse<PaymentOrder> page = orders.listByOrg(orgId, pageQuery);
        List<PaymentOrderDto> items = page.items().stream().map(this::toDto).toList();
        return new PageResponse<>(items, page.page(), page.pageSize(), page.total());
    }

    public List<PackSkuDto> listPackSkus(UUID orgId, UUID userId) {
        rbac.requireOrgMembership(orgId, userId);
        return skus.listActive().stream().map(PaymentOrderService::toSkuDto).toList();
    }

    private PaymentOrderDto existingOrder(PaymentOrder order, String fingerprint) {
        if (order.requestFingerprint() != null && !order.requestFingerprint().equals(fingerprint)) {
            throw new HyperLlmException(ErrorCode.PAYMENT_ORDER_CONFLICT);
        }
        return toDto(order);
    }

    /**
     * A retry of the same request reuses the committed order. If the previous attempt committed the
     * order but failed before storing the payment surface, the channel is asked again with the same
     * merchant order number, which the channel treats as the same payment.
     */
    private PaymentOrderDto resume(PaymentOrder order, String fingerprint, PaymentGateway gateway) {
        existingOrder(order, fingerprint);
        if (order.status() != OrderStatus.PENDING || order.channelPayload() != null) {
            return toDto(order);
        }
        try {
            PaymentGateway.PaymentInstruction instruction = gateway.create(order, subject(order));
            PaymentInstructionDto dto = new PaymentInstructionDto(instruction.action(), instruction.method(),
                    instruction.fields(), instruction.redirectUrl(), instruction.codeUrl());
            return toDto(writer.attachInstruction(order.id(), instruction.channelRef(), writeJson(dto)));
        } catch (PaymentChannelException ex) {
            throw new HyperLlmException(ErrorCode.PAYMENT_CHANNEL_UNAVAILABLE, "Payment channel is unavailable");
        }
    }

    private String subject(PaymentOrder order) {
        JsonNode spec = readJson(order.packSpecJson());
        return spec != null && spec.hasNonNull("name") ? spec.get("name").asText() : "HyperLLM wallet top-up";
    }

    private PaymentOrderDto toDto(PaymentOrder order) {
        String fulfillment = "NONE";
        if (order.status() == OrderStatus.PAID) {
            fulfillment = ledger.findFirstByRefOrderId(order.id()).isPresent()
                    ? "APPLIED"
                    : order.fulfillmentEventId() == null ? "NONE" : "PENDING";
        }
        return new PaymentOrderDto(
                order.id(), order.orderNo(), order.orderType(), order.paymentChannel(),
                order.payCurrency(), order.payAmountMinor(), order.fxRate().toPlainString(),
                order.creditMicros(), readJson(order.packSpecJson()),
                order.status(), fulfillment, order.fulfillmentEventId(),
                instruction(order), order.paidAt(), order.expiresAt(), order.createdAt());
    }

    private PaymentInstructionDto instruction(PaymentOrder order) {
        if (order.status() != OrderStatus.PENDING || order.channelPayload() == null) {
            return null;
        }
        try {
            return mapper.readValue(order.channelPayload(), PaymentInstructionDto.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode readJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot encode payment payload", ex);
        }
    }

    private static String requireIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new HyperLlmException(ErrorCode.PAYMENT_ORDER_CONFLICT,
                    "Idempotency-Key is required");
        }
        String key = raw.trim();
        if (key.length() > 128) {
            throw new HyperLlmException(ErrorCode.PAYMENT_ORDER_CONFLICT,
                    "Idempotency-Key is longer than 128 characters");
        }
        return key;
    }

    static PackSkuDto toSkuDto(ResourcePackSku sku) {
        return new PackSkuDto(sku.id(), sku.code(), sku.name(), sku.unitType(),
                sku.applicableModels(), sku.totalUnits(), sku.priceMicros(), sku.validDays(),
                sku.status(), sku.version());
    }
}