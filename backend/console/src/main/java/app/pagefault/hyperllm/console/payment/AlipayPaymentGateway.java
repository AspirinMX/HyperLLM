package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import app.pagefault.hyperllm.domain.model.payment.PaymentOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Alipay desktop website payment, CNY, signed form POST. */
@Component
public class AlipayPaymentGateway implements PaymentGateway {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PaymentProperties properties;
    private final PaymentProperties.Alipay config;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public AlipayPaymentGateway(PaymentProperties properties, ObjectMapper mapper, RestClient.Builder builder) {
        this.properties = properties;
        this.config = properties.getAlipay();
        this.mapper = mapper;
        this.http = builder.build();
        this.privateKey = config.isEnabled() ? PaymentCrypto.privateKey(config.getMerchantPrivateKey()) : null;
        this.publicKey = config.isEnabled() ? PaymentCrypto.publicKey(config.getAlipayPublicKey()) : null;
    }

    @Override
    public PaymentChannel channel() {
        return PaymentChannel.ALIPAY;
    }

    @Override
    public boolean enabled() {
        return config.isEnabled();
    }

    @Override
    public PaymentInstruction create(PaymentOrder order, String subject) {
        ObjectNode biz = mapper.createObjectNode();
        biz.put("out_trade_no", order.orderNo());
        biz.put("total_amount", cnyAmount(order.payAmountMinor()));
        biz.put("subject", subject);
        biz.put("product_code", "FAST_INSTANT_TRADE_PAY");

        Map<String, String> params = baseParams("alipay.trade.page.pay");
        params.put("notify_url", config.getNotifyUrl());
        params.put("return_url", properties.getPublicBaseUrl() + config.getReturnUrl()
                + "?orderNo=" + order.orderNo());
        params.put("biz_content", biz.toString());
        params.put("sign", sign(params));

        return new PaymentInstruction(order.orderNo(), config.getGatewayUrl(), "POST", params, null, null,
                new TreeMap<>(params).toString());
    }

    @Override
    public PaymentQueryResult query(PaymentOrder order) {
        ObjectNode biz = mapper.createObjectNode();
        biz.put("out_trade_no", order.orderNo());
        Map<String, String> params = baseParams("alipay.trade.query");
        params.put("biz_content", biz.toString());
        params.put("sign", sign(params));

        String body = http.post()
                .uri(config.getGatewayUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(StripePaymentGateway.encode(params))
                .retrieve()
                .body(String.class);
        JsonNode response = read(body).path("alipay_trade_query_response");
        String status = response.path("trade_status").asText("");
        PaymentState state = switch (status) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> PaymentState.PAID;
            case "TRADE_CLOSED" -> PaymentState.CLOSED;
            case "WAIT_BUYER_PAY" -> PaymentState.PENDING;
            default -> PaymentState.UNKNOWN;
        };
        return new PaymentQueryResult(
                state,
                response.path("trade_no").asText(null),
                minor(response.path("total_amount").asText(null)),
                "CNY",
                paidAt(response.path("send_pay_date").asText(null)),
                response.toString());
    }

    @Override
    public void close(PaymentOrder order) {
        ObjectNode biz = mapper.createObjectNode();
        biz.put("out_trade_no", order.orderNo());
        Map<String, String> params = baseParams("alipay.trade.close");
        params.put("biz_content", biz.toString());
        params.put("sign", sign(params));
        try {
            http.post()
                    .uri(config.getGatewayUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(StripePaymentGateway.encode(params))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            throw new PaymentChannelException("Alipay order could not be closed", ex);
        }
    }

    @Override
    public VerifiedPayment verify(Map<String, String> headers, String body) {
        Map<String, String> params = parseForm(body);
        String signature = params.get("sign");
        if (signature == null) {
            throw new PaymentChannelException("Missing Alipay signature");
        }
        Map<String, String> signed = new TreeMap<>(params);
        signed.remove("sign");
        signed.remove("sign_type");
        if (!PaymentCrypto.verifySha256Rsa(canonical(signed), signature, publicKey)) {
            throw new PaymentChannelException("Alipay signature does not match");
        }
        if (!config.getAppId().equals(params.get("app_id"))) {
            throw new PaymentChannelException("Alipay app id does not match");
        }
        String tradeStatus = params.getOrDefault("trade_status", "");
        boolean paid = "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
        String notifyId = params.getOrDefault("notify_id", params.get("trade_no"));
        if (notifyId == null || notifyId.isBlank()) {
            throw new PaymentChannelException("Alipay notification has no identity");
        }
        return new VerifiedPayment(
                notifyId,
                params.get("out_trade_no"),
                params.get("trade_no"),
                paid ? PaymentState.PAID : PaymentState.UNKNOWN,
                minor(params.get("total_amount")),
                "CNY",
                paidAt(params.get("gmt_payment")),
                body);
    }

    private Map<String, String> baseParams(String method) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", config.getAppId());
        params.put("method", method);
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", ZonedDateTime.now(CHINA).format(TIMESTAMP));
        params.put("version", "1.0");
        return params;
    }

    private String sign(Map<String, String> params) {
        return PaymentCrypto.signSha256Rsa(canonical(params), privateKey);
    }

    /** Sorted, non-empty, excluding sign/sign_type: the documented Alipay canonical form. */
    private static String canonical(Map<String, String> params) {
        return new TreeMap<>(params).entrySet().stream()
                .filter(entry -> !"sign".equals(entry.getKey()) && !"sign_type".equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw new PaymentChannelException("Alipay response is not JSON", ex);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> params = new TreeMap<>();
        if (body == null || body.isBlank()) {
            return params;
        }
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, equals), java.nio.charset.StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private static String cnyAmount(long minor) {
        return BigDecimal.valueOf(minor).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static long minor(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return 0L;
        }
        try {
            return new BigDecimal(yuan).movePointRight(2).setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return 0L;
        }
    }

    private static OffsetDateTime paidAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(value, TIMESTAMP).atZone(CHINA).toOffsetDateTime();
        } catch (Exception ex) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}