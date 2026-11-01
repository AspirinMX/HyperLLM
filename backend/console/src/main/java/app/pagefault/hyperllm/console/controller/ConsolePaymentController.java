package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.console.dto.PaymentDtos.CreatePaymentOrderRequest;
import app.pagefault.hyperllm.console.dto.PaymentDtos.PackSkuDto;
import app.pagefault.hyperllm.console.dto.PaymentDtos.PaymentOrderDto;
import app.pagefault.hyperllm.console.dto.PaymentDtos.RedeemVoucherRequest;
import app.pagefault.hyperllm.console.dto.PaymentDtos.RedemptionDto;
import app.pagefault.hyperllm.console.payment.PaymentOrderService;
import app.pagefault.hyperllm.console.payment.VoucherRedemptionService;
import app.pagefault.hyperllm.console.web.ConsoleRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsolePaymentController {

    private final PaymentOrderService paymentOrders;
    private final VoucherRedemptionService redemptions;

    @PostMapping("/orgs/{orgId}/payment-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentOrderDto create(
            @PathVariable UUID orgId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentOrderRequest body,
            HttpServletRequest request
    ) {
        return paymentOrders.create(orgId,
                ConsoleRequestContext.requirePrincipal(request).userId(), body, idempotencyKey);
    }

    @GetMapping("/orgs/{orgId}/payment-orders")
    public PageResponse<PaymentOrderDto> list(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        return paymentOrders.list(orgId,
                ConsoleRequestContext.requirePrincipal(request).userId(), PageQuery.of(page, pageSize));
    }

    @GetMapping("/orgs/{orgId}/payment-orders/{orderId}")
    public PaymentOrderDto get(
            @PathVariable UUID orgId,
            @PathVariable UUID orderId,
            HttpServletRequest request
    ) {
        return paymentOrders.get(orgId, orderId,
                ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @GetMapping("/orgs/{orgId}/resource-pack-skus")
    public List<PackSkuDto> listPackSkus(@PathVariable UUID orgId, HttpServletRequest request) {
        return paymentOrders.listPackSkus(orgId,
                ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @PostMapping("/orgs/{orgId}/redemptions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RedemptionDto redeem(
            @PathVariable UUID orgId,
            @Valid @RequestBody RedeemVoucherRequest body,
            HttpServletRequest request
    ) {
        return redemptions.redeem(orgId,
                ConsoleRequestContext.requirePrincipal(request).userId(), body);
    }
}
