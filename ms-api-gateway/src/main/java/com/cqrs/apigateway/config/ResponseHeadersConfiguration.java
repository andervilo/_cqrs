package com.cqrs.apigateway.config;

import java.util.ArrayList;
import java.util.Locale;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

@Configuration(proxyBeanMethods = false)
public class ResponseHeadersConfiguration {
    @Bean
    HttpHeadersFilter upstreamCorsHeaders() {
        return new HttpHeadersFilter() {
            @Override public boolean supports(Type type) { return type == Type.RESPONSE; }
            @Override public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
                var filtered = new HttpHeaders();
                filtered.putAll(input);
                new ArrayList<>(filtered.headerNames()).stream()
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("access-control-"))
                        .forEach(filtered::remove);
                return filtered;
            }
        };
    }
}
