package app.pagefault.hyperllm.protocol.format;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Formats gateway pre-stream errors and cross-protocol upstream errors into native OpenAI,
 * Anthropic, or Google Gemini JSON error envelopes.
 */
public final class ProtocolErrorFormatter {

    private final ObjectMapper objectMapper;

    public ProtocolErrorFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String formatErrorJson(ProtocolKind protocol, ErrorCode errorCode, String detail) {
        String message = (detail == null || detail.isBlank()) ? errorCode.defaultMessage() : detail;
        Map<String, Object> body = switch (protocol) {
            case OPENAI_CHAT, OPENAI_RESPONSES -> Map.of("error", openAiErrorBody(errorCode, message));
            case ANTHROPIC_MESSAGES -> Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", anthropicErrorType(errorCode),
                            "message", message
                    )
            );
            case GEMINI_GENERATE_CONTENT -> Map.of("error", geminiErrorBody(errorCode.httpStatus(), geminiStatus(errorCode), message));
        };
        return writeJson(body);
    }

    /**
     * Translates a non-2xx upstream error body from {@code upstreamProtocol} into the caller's
     * expected {@code inboundProtocol} error envelope while preserving the HTTP status code.
     */
    public byte[] translateUpstreamError(
            ProtocolKind inboundProtocol,
            ProtocolKind upstreamProtocol,
            int httpStatus,
            byte[] upstreamBody
    ) {
        if (inboundProtocol == upstreamProtocol && upstreamBody != null && upstreamBody.length > 0) {
            return upstreamBody;
        }
        String extractedMessage = extractUpstreamErrorMessage(upstreamProtocol, upstreamBody, httpStatus);
        ErrorCode mapped = mapHttpStatusToErrorCode(httpStatus);
        String json = switch (inboundProtocol) {
            case OPENAI_CHAT, OPENAI_RESPONSES -> writeJson(Map.of("error", openAiErrorBody(mapped, extractedMessage)));
            case ANTHROPIC_MESSAGES -> writeJson(Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", anthropicStatusErrorType(httpStatus, mapped),
                            "message", extractedMessage
                    )
            ));
            case GEMINI_GENERATE_CONTENT -> writeJson(Map.of(
                    "error", geminiErrorBody(httpStatus, geminiStatusFromHttp(httpStatus), extractedMessage)
            ));
        };
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private String extractUpstreamErrorMessage(ProtocolKind upstreamProtocol, byte[] body, int httpStatus) {
        if (body == null || body.length == 0) {
            return "Upstream returned HTTP " + httpStatus;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.isObject()) {
                JsonNode err = root.path("error");
                if (err.isObject() && err.hasNonNull("message")) {
                    return err.get("message").asText();
                }
                if (root.hasNonNull("message")) {
                    return root.get("message").asText();
                }
            }
        } catch (Exception ignored) {
        }
        String fallback = new String(body, StandardCharsets.UTF_8).trim();
        return fallback.isEmpty() ? "Upstream returned HTTP " + httpStatus
                : (fallback.length() > 512 ? fallback.substring(0, 512) : fallback);
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize protocol error JSON", ex);
        }
    }

    private static Map<String, Object> openAiErrorBody(ErrorCode errorCode, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", openAiErrorType(errorCode));
        error.put("param", null);
        error.put("code", errorCode.name());
        return error;
    }

    private static Map<String, Object> geminiErrorBody(int code, String status, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("status", status);
        return error;
    }

    private static String openAiErrorType(ErrorCode errorCode) {
        return switch (errorCode) {
            case BILLING_INSUFFICIENT_BALANCE -> "insufficient_quota";
            case RATE_LIMITED, AUTH_OTP_RATE_LIMITED, CHANNEL_CAPACITY_EXCEEDED, PROJECT_BUDGET_EXCEEDED ->
                    "rate_limit_error";
            case AUTH_INVALID_CREDENTIALS, AUTH_INVALID_KEY, KEY_EXPIRED, KEY_ALREADY_REVOKED,
                 AUTH_FORBIDDEN, AUTH_IP_BLOCKED, TENANT_ORG_SUSPENDED, MODEL_NOT_ALLOWED ->
                    "authentication_error";
            case PROTOCOL_INVALID_REQUEST, PROTOCOL_UNSUPPORTED_FEATURE, MODEL_NOT_FOUND ->
                    "invalid_request_error";
            default -> "server_error";
        };
    }

    private static String anthropicErrorType(ErrorCode errorCode) {
        return switch (errorCode) {
            case BILLING_INSUFFICIENT_BALANCE -> "billing_error";
            case RATE_LIMITED, AUTH_OTP_RATE_LIMITED, CHANNEL_CAPACITY_EXCEEDED, PROJECT_BUDGET_EXCEEDED ->
                    "rate_limit_error";
            case AUTH_INVALID_CREDENTIALS, AUTH_INVALID_KEY, KEY_EXPIRED -> "authentication_error";
            case KEY_ALREADY_REVOKED, AUTH_FORBIDDEN, AUTH_IP_BLOCKED,
                 TENANT_ORG_SUSPENDED, MODEL_NOT_ALLOWED -> "permission_error";
            case MODEL_NOT_FOUND -> "not_found_error";
            case PROTOCOL_INVALID_REQUEST, PROTOCOL_UNSUPPORTED_FEATURE -> "invalid_request_error";
            case CHANNEL_UNAVAILABLE, LEDGER_BACKLOG, OUTBOX_UNAVAILABLE, PROJECT_BUDGET_UNAVAILABLE ->
                    "overloaded_error";
            default -> "api_error";
        };
    }

    private static String anthropicStatusErrorType(int httpStatus, ErrorCode mapped) {
        return switch (httpStatus) {
            case 413 -> "request_too_large";
            case 529, 503 -> "overloaded_error";
            default -> anthropicErrorType(mapped);
        };
    }

    private static String geminiStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case PROTOCOL_INVALID_REQUEST, PROTOCOL_UNSUPPORTED_FEATURE, BILLING_INVALID_AMOUNT ->
                    "INVALID_ARGUMENT";
            case AUTH_INVALID_CREDENTIALS, AUTH_INVALID_KEY, KEY_EXPIRED -> "UNAUTHENTICATED";
            case BILLING_INSUFFICIENT_BALANCE, KEY_ALREADY_REVOKED, AUTH_FORBIDDEN, AUTH_IP_BLOCKED,
                 TENANT_ORG_SUSPENDED, MODEL_NOT_ALLOWED -> "PERMISSION_DENIED";
            case MODEL_NOT_FOUND -> "NOT_FOUND";
            case RATE_LIMITED, AUTH_OTP_RATE_LIMITED, CHANNEL_CAPACITY_EXCEEDED, PROJECT_BUDGET_EXCEEDED ->
                    "RESOURCE_EXHAUSTED";
            case CHANNEL_UNAVAILABLE, MODEL_PRICING_MISSING, LEDGER_BACKLOG, OUTBOX_UNAVAILABLE,
                 PROJECT_BUDGET_UNAVAILABLE -> "UNAVAILABLE";
            default -> "INTERNAL";
        };
    }

    private static String geminiStatusFromHttp(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHENTICATED";
            case 402, 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 503, 529 -> "UNAVAILABLE";
            default -> "INTERNAL";
        };
    }

    private static ErrorCode mapHttpStatusToErrorCode(int httpStatus) {
        return switch (httpStatus) {
            case 400, 413, 422 -> ErrorCode.PROTOCOL_INVALID_REQUEST;
            case 401 -> ErrorCode.AUTH_INVALID_KEY;
            case 402 -> ErrorCode.BILLING_INSUFFICIENT_BALANCE;
            case 403 -> ErrorCode.AUTH_FORBIDDEN;
            case 404 -> ErrorCode.MODEL_NOT_FOUND;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503, 529 -> ErrorCode.CHANNEL_UNAVAILABLE;
            default -> ErrorCode.UPSTREAM_ERROR;
        };
    }
}
