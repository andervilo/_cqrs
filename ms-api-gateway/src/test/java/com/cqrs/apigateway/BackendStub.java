package com.cqrs.apigateway;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

final class BackendStub implements AutoCloseable {
    record Call(String method, String uri, Map<String, List<String>> headers, String body) {}
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final String name;

    BackendStub(String name) {
        this.name = name;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                new TreeMap<>(exchange.getRequestHeaders()), body));
        String query = Objects.toString(exchange.getRequestURI().getRawQuery(), "");
        if (query.contains("scenario=disconnect")) { exchange.close(); return; }
        if (query.contains("scenario=slow")) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        int status = 200;
        Object response = Map.of("backend", name, "method", exchange.getRequestMethod(), "body", body,
                "uri", exchange.getRequestURI().toString());
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (query.contains("upstreamCors=true"))
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "https://wrong.example");
        if (exchange.getRequestURI().getPath().equals("/v3/api-docs")) {
            response = Map.of("openapi", "3.1.0", "info", Map.of("title", name, "version", "v1"),
                    "servers", List.of(Map.of("url", url())), "paths", Map.of(
                            "/api/v1/customers", Map.of("post", Map.of("responses", Map.of("201", Map.of("description", "Created")),
                                    "servers", List.of(Map.of("url", url())))),
                            "/api/v1/order-outbox", Map.of("get", Map.of("responses", Map.of())),
                            "/api/queries/orders", Map.of("get", Map.of("responses", Map.of()))));
        } else if (exchange.getRequestMethod().equals("DELETE")) {
            status = 204;
        } else if (exchange.getRequestURI().getPath().endsWith("/retry")) {
            status = 202;
        } else if (exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().endsWith("customers")) {
            status = 201;
            exchange.getResponseHeaders().set("Location", query.contains("absolute=true")
                    ? url() + "/api/v1/customers/created?ref=a%20b" : "/api/v1/customers/created");
        }
        if (query.contains("scenario=conflict")) {
            status = 409;
            response = Map.of("type", "about:blank", "title", "Conflict", "status", 409, "detail", "business conflict");
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
        }
        if (status == 204 || status == 202) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = JsonMapper.builder().build().writeValueAsBytes(response);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override public void close() { server.stop(0); executor.close(); }
}
