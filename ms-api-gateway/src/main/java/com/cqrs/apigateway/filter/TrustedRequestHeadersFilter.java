package com.cqrs.apigateway.filter;

import com.cqrs.apigateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/** Regenerate after Gateway's built-in removal of forwarded headers. No external proxy is trusted. */
@Component
public class TrustedRequestHeadersFilter implements HttpHeadersFilter, Ordered {
    private final GatewayProperties properties;
    public TrustedRequestHeadersFilter(GatewayProperties properties) { this.properties = properties; }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public boolean supports(Type type) { return type == Type.REQUEST; }
    @Override public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        var headers = new HttpHeaders();
        headers.putAll(input);
        headers.set("X-Forwarded-Proto", properties.publicUrl().getScheme());
        headers.set("X-Forwarded-Host", properties.publicUrl().getRawAuthority());
        if (exchange.getRequest().getRemoteAddress() != null)
            headers.set("X-Forwarded-For", exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
        return headers;
    }
}
