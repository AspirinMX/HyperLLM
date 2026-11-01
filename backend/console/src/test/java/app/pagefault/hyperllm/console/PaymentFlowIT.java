package app.pagefault.hyperllm.console;

import app.pagefault.hyperllm.common.billing.RedemptionCodeFormat;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.billing.RedemptionCode;
import app.pagefault.hyperllm.domain.model.billing.ResourcePackSku;
import app.pagefault.hyperllm.domain.model.billing.Voucher;
import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;
import app.pagefault.hyperllm.domain.model.enums.RedemptionStatus;
import app.pagefault.hyperllm.domain.model.enums.VoucherStatus;
import app.pagefault.hyperllm.domain.model.enums.VoucherType;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.domain.repository.RedemptionCodeRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackSkuRepository;
import app.pagefault.hyperllm.domain.repository.VoucherRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(
        classes = ConsoleApplication.class,
        properties = {
                "hyperllm.security.console-jwt-secret=test-console-jwt-secret-at-least-32-bytes",
                "hyperllm.security.aes-master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "hyperllm.security.key-pepper-v1=test-pepper-v1",
                "hyperllm.auth.otp-debug-echo-enabled=true",
                "hyperllm.billing.signup-bonus-micros=0",
                "hyperllm.payment.maintenance-enabled=false",
                "hyperllm.payment.voucher-code-pepper=test-voucher-pepper",
                "hyperllm.payment.stripe.enabled=true",
                "hyperllm.payment.stripe.secret-key=sk_test_123",
                "hyperllm.payment.stripe.webhook-secret=whsec_test"
        }
)
@AutoConfigureMockMvc
class PaymentFlowIT {

    private static final String WEBHOOK_SECRET = "whsec_test";
    private static final HttpServer STRIPE;

    static {
        try {
            STRIPE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STRIPE.createContext("/v1/checkout/sessions", exchange -> {
                byte[] body = """
                        {"id":"cs_test_1","url":"https://checkout.test/session"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            STRIPE.start();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_payment_test")
            .withUsername("hyperllm")
            .withPassword("hyperllm");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("hyperllm.payment.stripe.api-base", () -> "http://127.0.0.1:" + STRIPE.getAddress().getPort());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private ResourcePackSkuRepository skus;
    @Autowired private VoucherRepository vouchers;
    @Autowired private RedemptionCodeRepository codes;
    @Autowired private StringRedisTemplate redis;

    @Test
    void createsATopUpAndFulfillsItOnceConsumesAVoucherAndGrantsAPack() throws Exception {
        Session session = register();
        String idempotencyKey = "topup-" + UUID.randomUUID();

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/payment-orders", session.orgId())
                        .header("Authorization", "Bearer " + session.token())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderType":"WALLET_TOPUP","paymentChannel":"STRIPE","payAmountMinor":1000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.instruction.redirectUrl").value("https://checkout.test/session"))
                .andReturn();
        JsonNode order = objectMapper.readTree(created.getResponse().getContentAsString());
        String orderNo = order.path("orderNo").asText();
        String orderId = order.path("id").asText();

        // The same key and terms replay the committed order, not a second one.
        MvcResult replay = mockMvc.perform(post("/api/v1/orgs/{orgId}/payment-orders", session.orgId())
                        .header("Authorization", "Bearer " + session.token())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderType":"WALLET_TOPUP","paymentChannel":"STRIPE","payAmountMinor":1000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).path("id").asText())
                .isEqualTo(orderId);

        // The same key with a different amount is a conflict.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/payment-orders", session.orgId())
                        .header("Authorization", "Bearer " + session.token())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderType":"WALLET_TOPUP","paymentChannel":"STRIPE","payAmountMinor":2000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_CONFLICT"));

        // A valid signature fulfills the order and writes the credit authorization once.
        String payload = stripeEvent("evt_" + UUID.randomUUID(), orderNo, 1000L, "usd");
        mockMvc.perform(post("/api/v1/payment-notifications/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", stripeSignature(payload))
                        .content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payment-notifications/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", stripeSignature(payload))
                        .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/orgs/{orgId}/payment-orders/{orderId}", session.orgId(), orderId)
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PENDING"));
        assertThat(outbox.listUnconfirmedCredits(OffsetDateTime.now().plusSeconds(1), 100))
                .filteredOn(event -> event.aggregateId().equals(session.orgId())
                        && event.type().equals("RECHARGE"))
                .hasSize(1);

        // A bad signature is rejected before it can become evidence.
        mockMvc.perform(post("/api/v1/payment-notifications/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=deadbeef")
                        .content(payload))
                .andExpect(status().isBadRequest());

        // A voucher code credits bonus once and replays its own result.
        Voucher voucher = new Voucher(UuidV7.next(), "Launch", VoucherType.PROMO_CODE, 250_000L,
                List.of("gpt-4o"), 30, null, 10, 1, VoucherStatus.ACTIVE, null, 0L,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        vouchers.insert(voucher);
        String plaintext = RedemptionCodeFormat.generate();
        String normalized = RedemptionCodeFormat.normalize(plaintext);
        codes.insert(new RedemptionCode(UuidV7.next(), voucher.id(), null,
                RedemptionCodeFormat.hash(normalized, "test-voucher-pepper"),
                RedemptionCodeFormat.hint(plaintext), null, null, null, null,
                RedemptionStatus.UNUSED, null, OffsetDateTime.now(ZoneOffset.UTC)));

        MvcResult redemption = mockMvc.perform(post("/api/v1/orgs/{orgId}/redemptions", session.orgId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(plaintext)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.amountMicros").value(250_000))
                .andReturn();
        String grantEventId = objectMapper.readTree(redemption.getResponse().getContentAsString())
                .path("grantEventId").asText();
        mockMvc.perform(post("/api/v1/orgs/{orgId}/redemptions", session.orgId())
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(plaintext)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.grantEventId").value(grantEventId));

        // A pack purchase snapshots the SKU and grants the batch through the same outbox.
        ResourcePackSku sku = new ResourcePackSku(UuidV7.next(), "starter-1m", "Starter 1M",
                PackUnitType.TOKENS, List.of("gpt-4o"), 1_000_000L, 5_000_000L, 30,
                PackSkuStatus.ACTIVE, null, 0L, OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
        skus.insert(sku);
        MvcResult packOrder = mockMvc.perform(post("/api/v1/orgs/{orgId}/payment-orders", session.orgId())
                        .header("Authorization", "Bearer " + session.token())
                        .header("Idempotency-Key", "pack-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderType":"RESOURCE_PACK_PURCHASE","paymentChannel":"STRIPE","packSkuId":"%s"}
                                """.formatted(sku.id())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode pack = objectMapper.readTree(packOrder.getResponse().getContentAsString());
        String packPayload = stripeEvent("evt_" + UUID.randomUUID(),
                pack.path("orderNo").asText(), pack.path("payAmountMinor").asLong(), "usd");
        mockMvc.perform(post("/api/v1/payment-notifications/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", stripeSignature(packPayload))
                        .content(packPayload))
                .andExpect(status().isOk());
        assertThat(outbox.listUnconfirmedCredits(OffsetDateTime.now().plusSeconds(1), 100))
                .filteredOn(event -> event.aggregateId().equals(session.orgId())
                        && event.type().equals("PACK_GRANT"))
                .hasSize(1);
    }

    private Session register() throws Exception {
        String email = "pay-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        MvcResult codeRes = mockMvc.perform(post("/api/v1/auth/email-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","purpose":"REGISTER"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        String otp = objectMapper.readTree(codeRes.getResponse().getContentAsString())
                .path("debugCode").asText();
        MvcResult registerRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password123!","displayName":"Payer","emailCode":"%s"}
                                """.formatted(email, otp)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(registerRes.getResponse().getContentAsString());
        return new Session(json.path("accessToken").asText(),
                json.path("user").path("defaultOrgId").asText());
    }

    private record Session(String token, String orgId) {
    }

    private static String stripeEvent(String eventId, String orderNo, long amountMinor, String currency) {
        return """
                {"id":"%s","type":"checkout.session.completed","data":{"object":{
                  "client_reference_id":"%s","payment_status":"paid","amount_total":%d,
                  "currency":"%s","payment_intent":"pi_%s"}}}
                """.formatted(eventId, orderNo, amountMinor, currency,
                        eventId.replace("evt_", "")).replaceAll("\\s+", "");
    }

    private static String stripeSignature(String payload) {
        long timestamp = System.currentTimeMillis() / 1000L;
        String signature = HmacSha256.hmacHex(timestamp + "." + payload, WEBHOOK_SECRET);
        return "t=" + timestamp + ",v1=" + signature;
    }
}