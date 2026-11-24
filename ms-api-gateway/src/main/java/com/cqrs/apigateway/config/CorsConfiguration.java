package com.cqrs.apigateway.config;

import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.reactive.*;
import com.cqrs.apigateway.error.ProblemWriter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class CorsConfiguration {
    @Bean
    @Order(-200)
    CorsWebFilter corsWebFilter(GatewayProperties properties, ProblemWriter writer) {
        var cors = new org.springframework.web.cors.CorsConfiguration();
        cors.setAllowedOrigins(properties.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id", "traceparent", "tracestate"));
        cors.setExposedHeaders(List.of("Location", "X-Correlation-Id", "Retry-After"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source) {
            @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
                return super.filter(exchange, chain).then(Mono.defer(() ->
                        HttpStatus.FORBIDDEN.equals(exchange.getResponse().getStatusCode())
                                && !exchange.getResponse().isCommitted()
                                ? writer.write(exchange, HttpStatus.FORBIDDEN, "Origem ou preflight CORS não permitido.")
                                : Mono.empty()));
            }
        };
    }
}
