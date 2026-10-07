package app.pagefault.hyperllm.protocol.format;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Formats gateway pre-stream error responses into native OpenAI or Anthropic error JSON envelopes.
 */
public final class ProtocolErrorFormatter {

    private final ObjectMapper objectMapper;

    public ProtocolErrorFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String formatErrorJson(ProtocolKind protocol, ErrorCode errorCode, String detail) {
        String message = (detail == null || detail.isBlank()) ? errorCode.defaultMessage() : detail;
        Map<String, Object> body = switch (protocol) {
            case OPENAI_CHAT -> Map.of("error", openAiErrorBody(errorCode, message));
            case ANTHROPIC_MESSAGES -> Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", anthropicErrorType(errorCode),
                            "message", message
                    )
            );
        };
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

    private static String openAiErrorType(ErrorCode errorCode) {
        return switch (errorCode) {
            case BILLING_INSUFFICIENT_BALANCE -> "insufficient_quota";
            case RATE_LIMITED, AUTH_OTP_RATE_LIMITED -> "rate_limit_error";
            case AUTH_INVALID_CREDENTIALS, AUTH_INVALID_KEY, KEY_EXPIRED, KEY_ALREADY_REVOKED,
                 AUTH_FORBIDDEN, AUTH_IP_BLOCKED, TENANT_ORG_SUSPENDED, MODEL_NOT_ALLOWED -> "authentication_error";
            case PROTOCOL_INVALID_REQUEST, MODEL_NOT_FOUND -> "invalid_request_error";
            default -> "server_error";
        };
    }

    private static String anthropicErrorType(ErrorCode errorCode) {
        return switch (errorCode) {
            case BILLING_INSUFFICIENT_BALANCE -> "billing_error";
            case RATE_LIMITED, AUTH_OTP_RATE_LIMITED -> "rate_limit_error";
            case AUTH_INVALID_CREDENTIALS, AUTH_INVALID_KEY, KEY_EXPIRED -> "authentication_error";
            case KEY_ALREADY_REVOKED, AUTH_FORBIDDEN, AUTH_IP_BLOCKED,
                 TENANT_ORG_SUSPENDED, MODEL_NOT_ALLOWED -> "permission_error";
            case MODEL_NOT_FOUND -> "not_found_error";
            case PROTOCOL_INVALID_REQUEST -> "invalid_request_error";
            default -> "api_error";
        };
    }
}
