package com.cqrs.apigateway.config;

import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RouteConfiguration {
    @Bean
    RouteLocator businessRoutes(RouteLocatorBuilder builder, EndpointCatalog catalog, GatewayProperties p) {
        var routes = builder.routes();
        for (var e : catalog.all()) {
            if (e.maintenance() && !p.maintenanceEnabled()) continue;
            int timeout = e.rebuild() ? p.rebuildTimeoutMs()
                    : e.method().matches("GET") ? p.queryTimeoutMs() : p.commandTimeoutMs();
            routes.route(e.id(), r -> r.predicate(exchange ->
                    e.matches(exchange.getRequest().getPath().pathWithinApplication())
                            && e.method().equals(exchange.getRequest().getMethod()))
                    .metadata("connect-timeout", p.connectTimeoutMs())
                    .metadata("response-timeout", timeout)
                    .uri(e.query() ? p.queryServiceUrl() : p.commandServiceUrl()));
        }
        return routes.build();
    }

    // Reactor Netty may otherwise retry an aborted connection even without a Gateway Retry filter.
    @Bean
    HttpClientCustomizer disableTransportRetry() { return client -> client.disableRetry(true); }
}
