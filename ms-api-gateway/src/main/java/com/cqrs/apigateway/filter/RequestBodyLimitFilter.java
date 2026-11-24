package com.cqrs.apigateway.filter;

import com.cqrs.apigateway.config.GatewayProperties;
import com.cqrs.apigateway.error.ProblemWriter;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.*;

/** Buffer at most the configured limit before any upstream request, including chunked uploads. */
@Component
@Order(-90)
public class RequestBodyLimitFilter implements WebFilter {
    private final GatewayProperties properties;
    private final ProblemWriter writer;
    public RequestBodyLimitFilter(GatewayProperties properties, ProblemWriter writer) {
        this.properties = properties; this.writer = writer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getAttribute("endpoint") == null) return chain.filter(exchange);
        if (exchange.getRequest().getHeaders().getContentLength() > properties.maxRequestBytes())
            return writer.write(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "Corpo da requisição acima do limite permitido.");
        return DataBufferUtils.join(exchange.getRequest().getBody(), properties.maxRequestBytes())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    try { buffer.read(bytes); } finally { DataBufferUtils.release(buffer); }
                    return bytes;
                }).defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override public HttpHeaders getHeaders() {
                            var headers = new HttpHeaders();
                            headers.putAll(super.getHeaders());
                            headers.remove(HttpHeaders.TRANSFER_ENCODING);
                            headers.setContentLength(bytes.length);
                            return headers;
                        }
                        @Override public Flux<DataBuffer> getBody() {
                            return Flux.defer(() -> bytes.length == 0 ? Flux.empty()
                                    : Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                        }
                    };
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }
}
