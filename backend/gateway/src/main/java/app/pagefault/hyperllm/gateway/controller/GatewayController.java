package app.pagefault.hyperllm.gateway.controller;

import app.pagefault.hyperllm.gateway.proxy.GatewayProxyService;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayProxyService proxyService;

    @PostMapping(value = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Flux<DataBuffer>>> chatCompletions(
            ServerWebExchange exchange,
            @RequestBody(required = false) byte[] body
    ) {
        return proxyService.proxy(exchange, ProtocolKind.OPENAI_CHAT, InboundRequestContext.empty(), body);
    }

    @PostMapping(value = "/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Flux<DataBuffer>>> openAiResponses(
            ServerWebExchange exchange,
            @RequestBody(required = false) byte[] body
    ) {
        return proxyService.proxy(exchange, ProtocolKind.OPENAI_RESPONSES, InboundRequestContext.empty(), body);
    }

    @PostMapping(value = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Flux<DataBuffer>>> anthropicMessages(
            ServerWebExchange exchange,
            @RequestBody(required = false) byte[] body
    ) {
        String version = exchange.getRequest().getHeaders().getFirst("anthropic-version");
        String beta = exchange.getRequest().getHeaders().getFirst("anthropic-beta");
        return proxyService.proxy(
                exchange,
                ProtocolKind.ANTHROPIC_MESSAGES,
                InboundRequestContext.forAnthropic(version, beta),
                body
        );
    }

    @GetMapping("/models")
    public Mono<Map<String, Object>> listModels(ServerWebExchange exchange) {
        return proxyService.listModels(exchange);
    }
}
