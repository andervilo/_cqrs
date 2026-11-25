package com.cqrs.apigateway.config;

import java.util.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** The single allowlist shared by routing, authorization, method checks and OpenAPI. */
@Component
public class EndpointCatalog {
    public record Endpoint(String id, String path, PathPattern pattern, HttpMethod method,
                           String scope, boolean query, boolean maintenance, boolean rebuild) {
        public boolean matches(PathContainer path) { return pattern.matches(path); }
    }

    private final List<Endpoint> endpoints = new ArrayList<>();

    public EndpointCatalog() {
        for (String resource : List.of("customers", "products")) {
            command("/api/v1/" + resource, "GET", "POST");
            command("/api/v1/" + resource + "/{id}", "GET", "PUT", "DELETE");
        }
        command("/api/v1/orders", "GET", "POST");
        command("/api/v1/orders/{id}", "GET");
        command("/api/v1/orders/{id}/cancel", "POST");
        command("/api/v1/orders/{orderId}/items", "GET", "PUT");
        command("/api/v1/orders/{orderId}/items/{itemId}", "GET");
        add("/api/v1/order-outbox", "GET", "cqrs.ops.read", false, true, false);
        add("/api/v1/order-outbox/{id}", "GET", "cqrs.ops.read", false, true, false);
        add("/api/v1/order-outbox/{id}/retry", "POST", "cqrs.ops.write", false, true, false);
        add("/api/v1/projections/rebuild", "POST", "cqrs.ops.write", false, true, true);
        for (String resource : List.of("customers", "products", "orders")) {
            add("/api/queries/" + resource, "GET", "cqrs.read", true, false, false);
            add("/api/queries/" + resource + "/{" + resource.substring(0, resource.length()-1) + "Id}",
                    "GET", "cqrs.read", true, false, false);
        }
        add("/api/queries/customers/{customerId}/orders", "GET", "cqrs.read", true, false, false);
        add("/api/queries/dashboard/sales", "GET", "cqrs.dashboard.read", true, false, false);
    }

    private void command(String path, String... methods) {
        for (String method : methods) add(path, method,
                method.equals("GET") ? "cqrs.command.read" : "cqrs.write", false, false, false);
    }

    private void add(String path, String method, String scope, boolean query, boolean maintenance, boolean rebuild) {
        endpoints.add(new Endpoint("endpoint-" + endpoints.size(), path, PathPatternParser.defaultInstance.parse(path),
                HttpMethod.valueOf(method), scope, query, maintenance, rebuild));
    }

    public List<Endpoint> all() { return List.copyOf(endpoints); }
    public List<Endpoint> matching(PathContainer path, boolean maintenanceEnabled) {
        return endpoints.stream().filter(e -> (!e.maintenance() || maintenanceEnabled) && e.matches(path)).toList();
    }
}
