package app.pagefault.hyperllm.gateway.controller;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.gateway.proxy.GatewayProxyService;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Exposes native Google Gemini REST endpoints:
 * {@code POST /v1beta/models/{model}:generateContent} and
 * {@code POST /v1beta/models/{model}:streamGenerateContent}.
 */
@RestController
@RequestMapping("/v1beta/models")
@RequiredArgsConstructor
public class GeminiGatewayController {

    private static final String ACTION_GENERATE = ":generateContent";
    private static final String ACTION_STREAM = ":streamGenerateContent";

    private final GatewayProxyService proxyService;

    @PostMapping(value = "/{modelAction}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Flux<DataBuffer>>> generateContent(
            ServerWebExchange exchange,
            @PathVariable("modelAction") String modelAction,
            @RequestBody(required = false) byte[] body
    ) {
        if (modelAction == null || modelAction.isBlank()) {
            return Mono.error(new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Missing Gemini model and action"));
        }
        String model;
        boolean stream;
        if (modelAction.endsWith(ACTION_GENERATE)) {
            model = modelAction.substring(0, modelAction.length() - ACTION_GENERATE.length()).trim();
            stream = false;
        } else if (modelAction.endsWith(ACTION_STREAM)) {
            model = modelAction.substring(0, modelAction.length() - ACTION_STREAM.length()).trim();
            stream = true;
        } else {
            return Mono.error(new HyperLlmException(
                    ErrorCode.MODEL_NOT_FOUND, "Unsupported Gemini model action: " + modelAction));
        }

        if (model.isBlank()) {
            return Mono.error(new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Gemini model name must not be blank"));
        }
        String altParam = exchange.getRequest().getQueryParams().getFirst("alt");
        boolean altSse = "sse".equalsIgnoreCase(altParam);
        return proxyService.proxy(
                exchange,
                ProtocolKind.GEMINI_GENERATE_CONTENT,
                InboundRequestContext.forGemini(model, stream, altSse),
                body
        );
    }
}
