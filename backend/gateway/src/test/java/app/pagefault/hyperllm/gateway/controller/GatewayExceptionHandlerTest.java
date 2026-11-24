package app.pagefault.hyperllm.gateway.controller;

import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.netty.channel.AbortedException;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler =
            new GatewayExceptionHandler(new ProtocolErrorFormatter(new ObjectMapper()));

    @Test
    void silentlySwallowsClientDisconnectExceptions() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions"));

        assertThat(handler.handleUnexpected(new AbortedException(new ClosedChannelException()), exchange).block())
                .isNull();
        assertThat(handler.handleUnexpected(new IOException("Connection reset by peer"), exchange).block())
                .isNull();
    }

    @Test
    void doesNotWriteErrorBodyWhenResponseAlreadyCommitted() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions"));
        exchange.getResponse().setComplete().block();
        assertThat(exchange.getResponse().isCommitted()).isTrue();

        assertThat(handler.handleUnexpected(new IllegalStateException("late stream error"), exchange).block())
                .isNull();
    }

    @Test
    void returnsFormatted500JsonWhenUncommittedUnexpectedErrorOccurs() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/messages"));

        ResponseEntity<String> response = handler.handleUnexpected(new RuntimeException("boom"), exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).contains("Internal gateway error");
    }
}
