package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Channel credentials and the operational rate. One merchant per channel and environment: the
 * webhook identity check would otherwise have to guess which merchant signed a notification.
 */
@Data
@ConfigurationProperties(prefix = "hyperllm.payment")
public class PaymentProperties {

    /** Public origin used for browser returns; the gateway/console host in front of this service. */
    private String publicBaseUrl = "http://localhost:8081";

    /** How long a created order may still be paid before it is queried and closed. */
    private long orderTtlMinutes = 30;

    /** Clock tolerance for channel signatures. */
    private long notificationToleranceSeconds = 300;

    /** Units of foreign currency per one USD, e.g. CNY 0.14. USD is always 1. */
    private Map<String, BigDecimal> fxRates = new LinkedHashMap<>(Map.of(
            "USD", BigDecimal.ONE,
            "CNY", new BigDecimal("0.14")));

    /** Pepper for the code digest; the plaintext is never stored. */
    private String voucherCodePepper = "";

    private Stripe stripe = new Stripe();
    private Alipay alipay = new Alipay();
    private Wechat wechat = new Wechat();

    public boolean enabled(PaymentChannel channel) {
        return switch (channel) {
            case STRIPE -> stripe.isEnabled();
            case ALIPAY -> alipay.isEnabled();
            case WECHAT_PAY -> wechat.isEnabled();
        };
    }

    public BigDecimal fxRate(String currency) {
        BigDecimal rate = fxRates.get(currency);
        return rate == null ? BigDecimal.ONE : rate;
    }

    public String currency(PaymentChannel channel) {
        return switch (channel) {
            case STRIPE -> "USD";
            case ALIPAY, WECHAT_PAY -> "CNY";
        };
    }

    @Data
    public static class Stripe {
        private boolean enabled = false;
        private String secretKey = "";
        private String webhookSecret = "";
        private String apiBase = "https://api.stripe.com";
        private String successUrl = "/payment/return";
        private String cancelUrl = "/payment/cancel";
    }

    @Data
    public static class Alipay {
        private boolean enabled = false;
        private String appId = "";
        private String merchantPrivateKey = "";
        private String alipayPublicKey = "";
        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";
        private String notifyUrl = "";
        private String returnUrl = "";
        private String sellerId = "";
    }

    @Data
    public static class Wechat {
        private boolean enabled = false;
        private String merchantId = "";
        private String appId = "";
        private String merchantSerialNumber = "";
        private String merchantPrivateKey = "";
        /** API v3 key, 32 bytes, used to decrypt notifications. */
        private String apiV3Key = "";
        /** WeChat platform certificate public key, provisioned by operations. */
        private String platformPublicKey = "";
        private String apiBase = "https://api.mch.weixin.qq.com";
        private String notifyUrl = "";
    }
}
