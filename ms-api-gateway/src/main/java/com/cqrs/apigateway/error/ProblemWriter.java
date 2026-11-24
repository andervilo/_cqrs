package com.cqrs.apigateway.error;

import java.util.LinkedHashMap;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProblemWriter {
    private final ObjectMapper mapper;
    public ProblemWriter(ObjectMapper mapper) { this.mapper = mapper; }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String detail) {
        var response = exchange.getResponse();
        if (response.isCommitted()) return Mono.empty();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getURI().getRawPath());
        body.put("correlationId", exchange.getAttributeOrDefault("correlationId", ""));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(mapper.writeValueAsBytes(body))));
    }
}
