package com.cqrs.apigateway.filter;

import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter implements WebFilter {
    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);
    public static final String HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = UUID.randomUUID().toString();
        var supplied = exchange.getRequest().getHeaders().get(HEADER);
        if (supplied != null && supplied.size() == 1) {
            try {
                String normalized = UUID.fromString(supplied.getFirst()).toString();
                if (normalized.equalsIgnoreCase(supplied.getFirst())) id = normalized;
            } catch (IllegalArgumentException ignored) { /* Replace untrusted correlation identifiers. */ }
        }
        exchange.getAttributes().put("correlationId", id);
        exchange.getResponse().getHeaders().set(HEADER, id);
        String correlationId = id;
        var request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, correlationId)).build();
        long start = System.nanoTime();
        exchange.getResponse().beforeCommit(() -> {
            log.info("HTTP method={} route={} status={} durationMs={} correlationId={}",
                    request.getMethod(), exchange.getAttributeOrDefault("endpointTemplate", "local"),
                    exchange.getResponse().getStatusCode(), (System.nanoTime()-start)/1_000_000, correlationId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build());
    }
}
