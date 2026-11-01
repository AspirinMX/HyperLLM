package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Stripe Checkout, USD, hosted payment page. */
@Component
public class StripePaymentGateway implements PaymentGateway {

    private final PaymentProperties properties;
    private final ObjectMapper mapper;
    private final RestClient http;

    public StripePaymentGateway(PaymentProperties properties, ObjectMapper mapper, RestClient.Builder builder) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = builder.baseUrl(properties.getStripe().getApiBase()).build();
    }

    @Override
    public PaymentChannel channel() {
        return PaymentChannel.STRIPE;
    }

    @Override
    public boolean enabled() {
        return properties.getStripe().isEnabled();
    }

    @Override
    public PaymentInstruction create(PaymentOrder order, String subject) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "payment");
        form.put("success_url", returnUrl(order, "success"));
        form.put("cancel_url", returnUrl(order, "cancel"));
        form.put("client_reference_id", order.orderNo());
        form.put("metadata[order_no]", order.orderNo());
        form.put("payment_intent_data[metadata][order_no]", order.orderNo());
        form.put("line_items[0][quantity]", "1");
        form.put("line_items[0][price_data][currency]", "usd");
        form.put("line_items[0][price_data][unit_amount]", Long.toString(order.payAmountMinor()));
        form.put("line_items[0][price_data][product_data][name]", subject);

        JsonNode response = http.post()
                .uri("/v1/checkout/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getStripe().getSecretKey())
                .header("Idempotency-Key", order.orderNo())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(encode(form))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new PaymentChannelException("Stripe did not return a checkout session");
        }
        return new PaymentInstruction(
                response.path("id").asText(),
                null, null, Map.of(),
                response.path("url").asText(null),
                null,
                response.toString());
    }

    @Override
    public PaymentQueryResult query(PaymentOrder order) {
        JsonNode response = http.get()
                .uri("/v1/checkout/sessions/{id}", order.channelRef())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getStripe().getSecretKey())
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return new PaymentQueryResult(PaymentState.UNKNOWN, null, 0L, order.payCurrency(), null, null);
        }
        String paymentStatus = response.path("payment_status").asText("");
        String sessionStatus = response.path("status").asText("");
        PaymentState state = "paid".equals(paymentStatus)
                ? PaymentState.PAID
                : "expired".equals(sessionStatus) ? PaymentState.CLOSED : PaymentState.PENDING;
        return new PaymentQueryResult(
                state,
                response.path("payment_intent").asText(null),
                response.path("amount_total").asLong(0L),
                response.path("currency").asText(order.payCurrency()).toUpperCase(Locale.ROOT),
                paidAt(response.path("created").asLong(0L)),
                response.toString());
    }

    @Override
    public void close(PaymentOrder order) {
        if (order.channelRef() == null || order.channelRef().isBlank()) {
            return;
        }
        try {
            http.post()
                    .uri("/v1/checkout/sessions/{id}/expire", order.channelRef())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getStripe().getSecretKey())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            throw new PaymentChannelException("Stripe session could not be expired", ex);
        }
    }

    @Override
    public VerifiedPayment verify(Map<String, String> headers, String body) {
        String header = header(headers, "Stripe-Signature");
        if (header == null || header.isBlank()) {
            throw new PaymentChannelException("Missing Stripe signature");
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            if ("t".equals(name)) {
                timestamp = value;
            } else if ("v1".equals(name)) {
                signatures.add(value);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new PaymentChannelException("Malformed Stripe signature");
        }
        long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
        if (age > properties.getNotificationToleranceSeconds()) {
            throw new PaymentChannelException("Stripe signature timestamp is outside the tolerance");
        }
        String expected = HmacSha256.hmacHex(timestamp + "." + body, properties.getStripe().getWebhookSecret());
        boolean valid = signatures.stream().anyMatch(candidate -> HmacSha256.constantTimeEqualsHex(expected, candidate));
        if (!valid) {
            throw new PaymentChannelException("Stripe signature does not match");
        }

        JsonNode event = read(body);
        JsonNode session = event.path("data").path("object");
        String type = event.path("type").asText("");
        String orderNo = session.path("client_reference_id").asText(null);
        boolean paid = "checkout.session.completed".equals(type) || "checkout.session.async_payment_succeeded".equals(type);
        boolean successful = paid && "paid".equals(session.path("payment_status").asText(""));
        return new VerifiedPayment(
                event.path("id").asText(),
                orderNo,
                session.path("payment_intent").asText(null),
                successful ? PaymentState.PAID : PaymentState.UNKNOWN,
                session.path("amount_total").asLong(0L),
                session.path("currency").asText("usd").toUpperCase(Locale.ROOT),
                successful ? OffsetDateTime.now(ZoneOffset.UTC) : null,
                event.toString());
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw new PaymentChannelException("Stripe notification is not JSON", ex);
        }
    }

    private String returnUrl(PaymentOrder order, String outcome) {
        String base = properties.getStripe().getSuccessUrl();
        if ("cancel".equals(outcome)) {
            base = properties.getStripe().getCancelUrl();
        }
        String separator = base.contains("?") ? "&" : "?";
        return properties.getPublicBaseUrl() + base + separator + "orderNo=" + order.orderNo();
    }

    private static OffsetDateTime paidAt(long epochSeconds) {
        return epochSeconds <= 0L ? null : OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}