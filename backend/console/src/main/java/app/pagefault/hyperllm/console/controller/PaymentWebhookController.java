package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.console.payment.PaymentChannelException;
import app.pagefault.hyperllm.console.payment.PaymentConfirmationService;
import app.pagefault.hyperllm.console.payment.PaymentGateway;
import app.pagefault.hyperllm.console.payment.PaymentGatewayRegistry;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Channel callbacks. These routes are public because the signature is the authentication. */
@RestController
@RequestMapping("/api/v1/payment-notifications")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentGatewayRegistry gateways;
    private final PaymentConfirmationService confirmations;

    @PostMapping("/{channel}")
    public ResponseEntity<?> notify(
            @PathVariable String channel,
            @RequestBody(required = false) String body,
            HttpServletRequest request
    ) {
        PaymentChannel paymentChannel;
        try {
            paymentChannel = parse(channel);
        } catch (PaymentChannelException ex) {
            return ResponseEntity.notFound().build();
        }
        PaymentGateway gateway = gateways.find(paymentChannel);
        if (gateway == null || !gateway.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        try {
            PaymentGateway.VerifiedPayment verified = gateway.verify(headers, body == null ? "" : body);
            confirmations.confirm(verified, paymentChannel);
        } catch (PaymentChannelException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid notification"));
        }
        return switch (paymentChannel) {
            case STRIPE -> ResponseEntity.ok(Map.of());
            case ALIPAY -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("success");
            case WECHAT_PAY -> ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "ok"));
        };
    }

    private static PaymentChannel parse(String raw) {
        String normalized = raw.toUpperCase().replace('-', '_');
        try {
            return PaymentChannel.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new PaymentChannelException("Unknown payment channel");
        }
    }
}