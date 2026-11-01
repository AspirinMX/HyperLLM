package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** WeChat Pay API v3 Native, CNY, QR code content. */
@Component
public class WechatPaymentGateway implements PaymentGateway {

    private final PaymentProperties properties;
    private final PaymentProperties.Wechat config;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final PrivateKey privateKey;
    private final PublicKey platformPublicKey;
    private final byte[] apiV3Key;

    public WechatPaymentGateway(PaymentProperties properties, ObjectMapper mapper, RestClient.Builder builder) {
        this.properties = properties;
        this.config = properties.getWechat();
        this.mapper = mapper;
        this.http = builder.baseUrl(config.getApiBase()).build();
        this.privateKey = config.isEnabled() ? PaymentCrypto.privateKey(config.getMerchantPrivateKey()) : null;
        this.platformPublicKey = config.isEnabled() && !config.getPlatformPublicKey().isBlank()
                ? PaymentCrypto.publicKey(config.getPlatformPublicKey()) : null;
        this.apiV3Key = config.getApiV3Key().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public PaymentChannel channel() {
        return PaymentChannel.WECHAT_PAY;
    }

    @Override
    public boolean enabled() {
        return config.isEnabled();
    }

    @Override
    public PaymentInstruction create(PaymentOrder order, String subject) {
        ObjectNode body = mapper.createObjectNode();
        body.put("appid", config.getAppId());
        body.put("mchid", config.getMerchantId());
        body.put("description", subject);
        body.put("out_trade_no", order.orderNo());
        body.put("notify_url", config.getNotifyUrl());
        ObjectNode amount = body.putObject("amount");
        amount.put("total", order.payAmountMinor());
        amount.put("currency", "CNY");

        String path = "/v3/pay/transactions/native";
        String json = write(body);
        JsonNode response = http.post()
                .uri(path)
                .header("Authorization", authorization("POST", path, json))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("code_url").isMissingNode()) {
            throw new PaymentChannelException("WeChat did not return a code_url");
        }
        return new PaymentInstruction(order.orderNo(), null, null, Map.of(), null,
                response.path("code_url").asText(), response.toString());
    }

    @Override
    public PaymentQueryResult query(PaymentOrder order) {
        String path = "/v3/pay/transactions/out-trade-no/" + order.orderNo()
                + "?mchid=" + config.getMerchantId();
        JsonNode response = http.get()
                .uri(path)
                .header("Authorization", authorization("GET", path, ""))
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return new PaymentQueryResult(PaymentState.UNKNOWN, null, 0L, "CNY", null, null);
        }
        String state = response.path("trade_state").asText("");
        return new PaymentQueryResult(
                switch (state) {
                    case "SUCCESS" -> PaymentState.PAID;
                    case "CLOSED", "REVOKED" -> PaymentState.CLOSED;
                    case "NOTPAY", "USERPAYING" -> PaymentState.PENDING;
                    default -> PaymentState.UNKNOWN;
                },
                response.path("transaction_id").asText(null),
                response.path("amount").path("total").asLong(0L),
                "CNY",
                paidAt(response.path("success_time").asText(null)),
                response.toString());
    }

    @Override
    public VerifiedPayment verify(Map<String, String> headers, String body) {
        String signature = StripePaymentGateway.header(headers, "Wechatpay-Signature");
        String timestamp = StripePaymentGateway.header(headers, "Wechatpay-Timestamp");
        String nonce = StripePaymentGateway.header(headers, "Wechatpay-Nonce");
        if (signature == null || timestamp == null || nonce == null) {
            throw new PaymentChannelException("Missing WeChat signature headers");
        }
        if (platformPublicKey == null) {
            throw new PaymentChannelException("WeChat platform certificate is not configured");
        }
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        if (!PaymentCrypto.verifySha256Rsa(message, signature, platformPublicKey)) {
            throw new PaymentChannelException("WeChat signature does not match");
        }
        long age = Math.abs(System.currentTimeMillis() / 1000L - Long.parseLong(timestamp));
        if (age > properties.getNotificationToleranceSeconds()) {
            throw new PaymentChannelException("WeChat signature timestamp is outside the tolerance");
        }

        JsonNode envelope = read(body);
        String eventType = envelope.path("event_type").asText("");
        if (!"TRANSACTION.SUCCESS".equals(eventType)) {
            return new VerifiedPayment(envelope.path("id").asText(), null, null,
                    PaymentState.UNKNOWN, 0L, "CNY", null, body);
        }
        JsonNode resource = envelope.path("resource");
        if (apiV3Key.length != 32) {
            throw new PaymentChannelException("WeChat API v3 key must be 32 bytes");
        }
        String plaintext = PaymentCrypto.decryptAesGcm(apiV3Key,
                resource.path("ciphertext").asText(),
                resource.path("nonce").asText(),
                resource.path("associated_data").asText(null));
        JsonNode transaction = read(plaintext);
        if (!config.getMerchantId().equals(transaction.path("mchid").asText())) {
            throw new PaymentChannelException("WeChat merchant id does not match");
        }
        String tradeState = transaction.path("trade_state").asText("");
        boolean paid = "SUCCESS".equals(tradeState);
        return new VerifiedPayment(
                envelope.path("id").asText(),
                transaction.path("out_trade_no").asText(),
                transaction.path("transaction_id").asText(null),
                paid ? PaymentState.PAID : PaymentState.UNKNOWN,
                transaction.path("amount").path("total").asLong(0L),
                "CNY",
                paidAt(transaction.path("success_time").asText(null)),
                plaintext);
    }

    @Override
    public void close(PaymentOrder order) {
        String path = "/v3/pay/transactions/out-trade-no/" + order.orderNo() + "/close";
        ObjectNode body = mapper.createObjectNode();
        body.put("mchid", config.getMerchantId());
        String json = write(body);
        try {
            http.post().uri(path)
                    .header("Authorization", authorization("POST", path, json))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            throw new PaymentChannelException("WeChat close failed", ex);
        }
    }

    private String authorization(String method, String path, String body) {
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String message = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = PaymentCrypto.signSha256Rsa(message, privateKey);
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + config.getMerchantId()
                + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature
                + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + config.getMerchantSerialNumber() + "\"";
    }

    private String write(ObjectNode body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new PaymentChannelException("Cannot encode WeChat request", ex);
        }
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw new PaymentChannelException("WeChat payload is not JSON", ex);
        }
    }

    private static OffsetDateTime paidAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
