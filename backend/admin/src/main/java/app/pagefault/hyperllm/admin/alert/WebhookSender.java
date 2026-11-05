package app.pagefault.hyperllm.admin.alert;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Posts a signed alert to a tenant-owned webhook.
 *
 * <p>Redirects are never followed and the resolved address must be public: the target is supplied
 * by a tenant, so the sender must not be usable as a probe of the internal network. The body is read
 * with a hard bound for the same reason. The signature covers the timestamp and the exact bytes.
 */
@Slf4j
@Component
public class WebhookSender {

    private static final int MAX_RESPONSE_BYTES = 4_096;

    private final AdminProperties properties;
    private final HttpClient client;

    public WebhookSender(AdminProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void send(String target, String notificationId, String secret, String body) {
        URI uri = validate(target);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = "v1=" + HmacSha256.hmacHex(timestamp + "." + body, secret);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "HyperLLM-Alerts/1")
                .header("X-HyperLLM-Notification-Id", notificationId)
                .header("X-HyperLLM-Timestamp", timestamp)
                .header("X-HyperLLM-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NotificationException("WEBHOOK_INTERRUPTED", "Interrupted while delivering", false, ex);
        } catch (Exception ex) {
            throw new NotificationException("WEBHOOK_CONNECT", "Cannot reach the webhook", false, ex);
        }
        try (InputStream in = response.body()) {
            in.readNBytes(MAX_RESPONSE_BYTES);
        } catch (Exception ex) {
            log.debug("Cannot drain the webhook response", ex);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        boolean retryable = status == 408 || status == 429 || status >= 500;
        throw new NotificationException("WEBHOOK_HTTP_" + status,
                "The webhook answered " + status, !retryable);
    }

    private URI validate(String target) {
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException ex) {
            throw new NotificationException("WEBHOOK_INVALID_URL", "The webhook URL is invalid", true, ex);
        }
        boolean schemeOk = "https".equalsIgnoreCase(uri.getScheme())
                || (properties.getAlerts().isAllowHttp() && "http".equalsIgnoreCase(uri.getScheme()));
        if (!schemeOk || uri.getHost() == null) {
            throw new NotificationException("WEBHOOK_INVALID_URL", "The webhook URL must be HTTPS", true);
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!properties.getAlerts().isAllowLoopback() && isInternal(address)) {
                    throw new NotificationException("WEBHOOK_PRIVATE_ADDRESS",
                            "The webhook resolves to a non-public address", true);
                }
            }
        } catch (NotificationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NotificationException("WEBHOOK_DNS", "Cannot resolve the webhook host", false, ex);
        }
        return uri;
    }

    private static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4 && (raw[0] & 0xff) == 100 && (raw[1] & 0xc0) == 64) {
            return true; // 100.64.0.0/10 carrier-grade NAT
        }
        return address instanceof Inet6Address && (raw[0] & 0xfe) == 0xfc; // fc00::/7
    }
}
