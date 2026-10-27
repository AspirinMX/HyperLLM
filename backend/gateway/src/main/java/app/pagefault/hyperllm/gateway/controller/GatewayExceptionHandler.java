package app.pagefault.hyperllm.gateway.controller;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.gateway.billing.BillingAdmissionException;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GatewayExceptionHandler {

    private final ProtocolErrorFormatter errorFormatter;

    @ExceptionHandler(HyperLlmException.class)
    public ResponseEntity<String> handleHyperLlm(HyperLlmException ex, ServerWebExchange exchange) {
        ProtocolKind kind = resolveProtocol(exchange);
        String body = errorFormatter.formatErrorJson(kind, ex.getErrorCode(), ex.getMessage());
        var response = ResponseEntity.status(ex.getErrorCode().httpStatus())
                .contentType(MediaType.APPLICATION_JSON);
        if (ex instanceof BillingAdmissionException admission) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(admission.getRetryAfterSeconds()));
        }
        return response.body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled gateway exception", ex);
        ProtocolKind kind = resolveProtocol(exchange);
        String body = errorFormatter.formatErrorJson(kind, ErrorCode.UPSTREAM_ERROR, "Internal gateway error");
        return ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static ProtocolKind resolveProtocol(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        return path != null && path.startsWith("/v1/messages")
                ? ProtocolKind.ANTHROPIC_MESSAGES
                : ProtocolKind.OPENAI_CHAT;
    }
}
