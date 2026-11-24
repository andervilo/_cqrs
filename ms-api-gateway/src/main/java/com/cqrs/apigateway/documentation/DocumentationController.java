package com.cqrs.apigateway.documentation;

import com.cqrs.apigateway.config.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v3/api-docs")
public class DocumentationController {
    private final GatewayProperties properties;
    private final EndpointCatalog catalog;
    private final ObjectMapper mapper;
    private final WebClient client;
    private final boolean local;

    public DocumentationController(GatewayProperties properties, EndpointCatalog catalog,
                                   ObjectMapper mapper, WebClient.Builder builder, boolean localGateway) {
        this.properties = properties; this.catalog = catalog; this.mapper = mapper; this.local = localGateway;
        this.client = builder.clientConnector(new ReactorClientHttpConnector(HttpClient.create().disableRetry(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs())))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)).build();
    }

    @GetMapping(value = "/{service:command|query}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> document(@PathVariable String service, ServerWebExchange exchange) {
        var origin = service.equals("command") ? properties.commandServiceUrl() : properties.queryServiceUrl();
        return client.get().uri(origin + "/v3/api-docs").headers(headers -> {
                    String bearer = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                    if (bearer != null) headers.set(HttpHeaders.AUTHORIZATION, bearer);
                    headers.set("X-Correlation-Id", exchange.getAttributeOrDefault("correlationId", ""));
                }).retrieve().bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.queryTimeoutMs()))
                .map(json -> sanitize(json, service.equals("query")))
                .onErrorMap(org.springframework.web.reactive.function.client.WebClientResponseException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_GATEWAY));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitize(String json, boolean query) {
        Map<String, Object> document = mapper.readValue(json, Map.class);
        document.put("servers", List.of(Map.of("url", properties.publicUrl().toString())));
        var sourcePaths = (Map<String, Object>) document.getOrDefault("paths", Map.of());
        Map<String, Object> paths = new LinkedHashMap<>();
        for (var endpoint : catalog.all()) {
            if (endpoint.query() != query || endpoint.maintenance() && !properties.maintenanceEnabled()) continue;
            var source = (Map<String, Object>) sourcePaths.get(endpoint.path());
            if (source == null) continue;
            String method = endpoint.method().name().toLowerCase(Locale.ROOT);
            var operation = (Map<String, Object>) source.get(method);
            if (operation == null) continue;
            operation.remove("servers");
            operation.put("security", local ? List.of() : List.of(Map.of("bearerAuth", List.of())));
            operation.put("x-required-scope", endpoint.scope());
            operation.put("description", Objects.toString(operation.get("description"), "")
                    + "\nGateway scope: `" + endpoint.scope() + "`."
                    + (endpoint.maintenance() ? " Operação de manutenção." : ""));
            var target = (Map<String, Object>) paths.computeIfAbsent(endpoint.path(), ignored -> new LinkedHashMap<>());
            if (source.containsKey("parameters")) target.put("parameters", source.get("parameters"));
            target.put(method, operation);
        }
        document.put("paths", paths);
        var components = (Map<String, Object>) document.computeIfAbsent("components", ignored -> new LinkedHashMap<>());
        var schemes = (Map<String, Object>) components.computeIfAbsent("securitySchemes", ignored -> new LinkedHashMap<>());
        schemes.put("bearerAuth", Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT",
                "description", "Token com o scope indicado em cada operação."));
        document.put("security", local ? List.of() : List.of(Map.of("bearerAuth", List.of())));
        return document;
    }
}
