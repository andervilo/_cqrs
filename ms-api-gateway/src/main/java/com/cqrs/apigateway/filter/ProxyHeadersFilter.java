package com.cqrs.apigateway.filter;

import com.cqrs.apigateway.config.GatewayProperties;
import java.net.URI;
import java.util.*;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ProxyHeadersFilter implements GlobalFilter, Ordered {
    private final GatewayProperties properties;
    public ProxyHeadersFilter(GatewayProperties properties) { this.properties = properties; }
    @Override public int getOrder() { return -1; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest().mutate().headers(headers -> {
            new ArrayList<>(headers.headerNames()).stream().filter(name ->
                    name.equalsIgnoreCase("Forwarded") || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-")
                    || name.equalsIgnoreCase("X-User-Id") || name.equalsIgnoreCase("X-Roles"))
                    .forEach(headers::remove);
        }).build();
        exchange.getResponse().beforeCommit(() -> {
            var headers = exchange.getResponse().getHeaders();
            String location = headers.getFirst(HttpHeaders.LOCATION);
            if (location != null) {
                try {
                    URI uri = URI.create(location);
                    if (uri.isAbsolute() && (sameOrigin(uri, properties.commandServiceUrl())
                            || sameOrigin(uri, properties.queryServiceUrl()))) {
                        String suffix = Optional.ofNullable(uri.getRawPath()).orElse("")
                                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
                        headers.set(HttpHeaders.LOCATION, properties.publicUrl() + suffix);
                    }
                } catch (IllegalArgumentException ignored) { /* Preserve invalid upstream Location as received. */ }
            }
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build());
    }

    private boolean sameOrigin(URI a, URI b) {
        return Objects.equals(a.getScheme(), b.getScheme()) && Objects.equals(a.getRawAuthority(), b.getRawAuthority());
    }
}
