package com.cqrs.apigateway.filter;

import com.cqrs.apigateway.config.*;
import com.cqrs.apigateway.error.ProblemWriter;
import java.util.stream.Collectors;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(-150)
public class EndpointGuardFilter implements WebFilter {
    private final EndpointCatalog catalog;
    private final GatewayProperties properties;
    private final ProblemWriter writer;
    public EndpointGuardFilter(EndpointCatalog catalog, GatewayProperties properties, ProblemWriter writer) {
        this.catalog = catalog; this.properties = properties; this.writer = writer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        if (!path.value().startsWith("/api/") && !path.value().equals("/api")) return chain.filter(exchange);
        var matching = catalog.matching(path, properties.maintenanceEnabled());
        if (matching.isEmpty()) return writer.write(exchange, HttpStatus.NOT_FOUND, "Endpoint não encontrado.");
        var endpoint = matching.stream().filter(e -> e.method().equals(exchange.getRequest().getMethod())).findFirst();
        if (endpoint.isEmpty()) {
            exchange.getResponse().getHeaders().setAllow(matching.stream().map(EndpointCatalog.Endpoint::method)
                    .collect(Collectors.toSet()));
            return writer.write(exchange, HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido para este endpoint.");
        }
        exchange.getAttributes().put("endpoint", endpoint.get());
        exchange.getAttributes().put("endpointTemplate", endpoint.get().path());
        return chain.filter(exchange);
    }
}
