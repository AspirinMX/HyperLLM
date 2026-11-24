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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GatewayExceptionHandler {

    private final ProtocolErrorFormatter errorFormatter;

    @ExceptionHandler(HyperLlmException.class)
    public Mono<ResponseEntity<String>> handleHyperLlm(HyperLlmException ex, ServerWebExchange exchange) {
        if (exchange.getResponse().isCommitted()) {
            log.debug("Ignoring HyperLlmException after response committed on {}: {}",
                    exchange.getRequest().getURI().getPath(), ex.getMessage());
            return Mono.empty();
        }
        ProtocolKind kind = resolveProtocol(exchange);
        String body = errorFormatter.formatErrorJson(kind, ex.getErrorCode(), ex.getMessage());
        var response = ResponseEntity.status(ex.getErrorCode().httpStatus())
                .contentType(MediaType.APPLICATION_JSON);
        if (ex instanceof BillingAdmissionException admission) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(admission.getRetryAfterSeconds()));
        }
        return Mono.just(response.body(body));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<String>> handleResponseStatus(ResponseStatusException ex, ServerWebExchange exchange) {
        if (exchange.getResponse().isCommitted()) {
            log.debug("Ignoring ResponseStatusException after response committed on {}: {}",
                    exchange.getRequest().getURI().getPath(), ex.getMessage());
            return Mono.empty();
        }
        ProtocolKind kind = resolveProtocol(exchange);
        int status = ex.getStatusCode().value();
        ErrorCode mapped = switch (status) {
            case 404 -> ErrorCode.MODEL_NOT_FOUND;
            case 401 -> ErrorCode.AUTH_INVALID_KEY;
            case 403 -> ErrorCode.AUTH_FORBIDDEN;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> status >= 400 && status < 500
                    ? ErrorCode.PROTOCOL_INVALID_REQUEST
                    : ErrorCode.UPSTREAM_ERROR;
        };
        String message = ex.getReason() != null && !ex.getReason().isBlank()
                ? ex.getReason() : ex.getMessage();
        String body = errorFormatter.formatErrorJson(kind, mapped, message);
        return Mono.just(ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<String>> handleUnexpected(Exception ex, ServerWebExchange exchange) {
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected mid-request on {}: {}",
                    exchange.getRequest().getURI().getPath(), ex.getMessage());
            return Mono.empty();
        }
        if (exchange.getResponse().isCommitted()) {
            log.warn("Error after response already committed on {}: {}",
                    exchange.getRequest().getURI().getPath(), ex.toString());
            return Mono.empty();
        }
        log.error("Unhandled gateway exception", ex);
        ProtocolKind kind = resolveProtocol(exchange);
        String body = errorFormatter.formatErrorJson(kind, ErrorCode.UPSTREAM_ERROR, "Internal gateway error");
        return Mono.just(ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    static boolean isClientDisconnect(Throwable ex) {
        Throwable cursor = ex;
        int depth = 0;
        while (cursor != null && depth++ < 16) {
            if (cursor instanceof AbortedException || cursor instanceof ClosedChannelException) {
                return true;
            }
            if (cursor instanceof IOException) {
                String msg = cursor.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase(Locale.ROOT);
                    if (lower.contains("broken pipe")
                            || lower.contains("connection reset")
                            || lower.contains("connection prematurely closed")) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static ProtocolKind resolveProtocol(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        if (path == null) {
            return ProtocolKind.OPENAI_CHAT;
        }
        if (path.startsWith("/v1/messages")) {
            return ProtocolKind.ANTHROPIC_MESSAGES;
        }
        if (path.startsWith("/v1/responses")) {
            return ProtocolKind.OPENAI_RESPONSES;
        }
        if (path.startsWith("/v1beta/models")) {
            return ProtocolKind.GEMINI_GENERATE_CONTENT;
        }
        return ProtocolKind.OPENAI_CHAT;
    }
}
