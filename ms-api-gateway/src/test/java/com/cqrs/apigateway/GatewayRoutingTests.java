package com.cqrs.apigateway;

import com.cqrs.apigateway.config.EndpointCatalog;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.maintenance-enabled=true", "gateway.query-timeout-ms=1000",
                "gateway.command-timeout-ms=1000", "gateway.max-request-bytes=1048576"})
@ActiveProfiles("local")
class GatewayRoutingTests {
    static final BackendStub command = new BackendStub("command");
    static final BackendStub query = new BackendStub("query");
    @LocalServerPort int port;
    WebTestClient client;

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        registry.add("gateway.command-service-url", command::url);
        registry.add("gateway.query-service-url", query::url);
    }

    @BeforeEach void client() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
        command.calls.clear(); query.calls.clear();
    }

    @AfterAll static void close() { command.close(); query.close(); }

    static Stream<EndpointCatalog.Endpoint> endpoints() { return new EndpointCatalog().all().stream(); }

    @ParameterizedTest
    @MethodSource("endpoints")
    void forwardsEveryCataloguedOperation(EndpointCatalog.Endpoint endpoint) {
        String path = endpoint.path().replaceAll("\\{[^}]+}", "11111111-1111-4111-8111-111111111111");
        String payload = endpoint.method().matches("GET") || endpoint.method().matches("DELETE") ? "" : "{\"value\":12.50}";
        var request = client.method(endpoint.method()).uri(path);
        if (!payload.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).bodyValue(payload);
        request.exchange().expectStatus().is2xxSuccessful().expectHeader().exists("X-Correlation-Id");
        var destination = endpoint.query() ? query : command;
        assertThat(destination.calls).hasSize(1);
        assertThat((endpoint.query() ? command : query).calls).isEmpty();
        assertThat(destination.calls.getFirst()).satisfies(call -> {
            assertThat(call.method()).isEqualTo(endpoint.method().name());
            assertThat(call.uri()).isEqualTo(path);
            assertThat(call.body()).isEqualTo(payload);
        });
    }

    @Test void preservesEncodedQueryAndRepeatedParameters() {
        String uri = "/api/queries/orders?search=Jo%C3%A3o%20Silva&sort=createdAt,desc&sort=totalAmount,asc&from=2026-11-24T12%3A00%3A00Z";
        client.get().uri(URI.create("http://127.0.0.1:" + port + uri)).exchange().expectStatus().isOk();
        assertThat(query.calls.getFirst().uri()).isEqualTo(uri);
    }

    @Test void preservesCreationAndNormalizesOnlyInternalAbsoluteLocation() {
        client.post().uri("/api/v1/customers").bodyValue("{}").exchange().expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/v1/customers/created");
        client.post().uri("/api/v1/customers?absolute=true").bodyValue("{}").exchange().expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "http://localhost:8082/api/v1/customers/created?ref=a%20b");
    }

    @Test void preservesBackendProblemDetailsWithoutEnvelope() {
        client.get().uri("/api/queries/orders?scenario=conflict").exchange().expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("business conflict").jsonPath("$.backend").doesNotExist();
    }

    @Test void rejectsUnknownPathsAndMethodsWithoutCallingBackends() {
        client.get().uri("/api/queries/dashboard/sales").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/projections/unknown").exchange().expectStatus().isNotFound();
        client.post().uri("/api/queries/orders").exchange().expectStatus().isEqualTo(405)
                .expectHeader().valueEquals("Allow", "GET");
        client.head().uri("/api/queries/orders").exchange().expectStatus().isEqualTo(405);
        assertThat(command.calls).isEmpty(); assertThat(query.calls).isEmpty();
    }

    @Test void handlesCorsPreflightAndRejectsUnknownOrigin() {
        client.options().uri("/api/v1/orders").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:4200");
        client.options().uri("/api/v1/orders").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin")
                .expectBody().jsonPath("$.status").isEqualTo(403);
        assertThat(command.calls).isEmpty();
    }

    @Test void centralizesCorsWithoutDuplicatingUpstreamHeaders() {
        client.get().uri("/api/queries/orders?upstreamCors=true").header("Origin", "http://localhost:4200")
                .exchange().expectStatus().isOk().expectHeader()
                .values("Access-Control-Allow-Origin", values -> assertThat(values).containsExactly("http://localhost:4200"))
                .expectHeader().value("Access-Control-Expose-Headers", value -> assertThat(value).contains("Location"));
    }

    @Test void sanitizesUntrustedHeadersAndPropagatesCorrelation() {
        String id = UUID.randomUUID().toString();
        client.get().uri("/api/v1/orders/not-a-uuid")
                .header("X-Correlation-Id", id).header("X-User-Id", "admin").header("X-Roles", "admin")
                .header("Forwarded", "host=evil").header("X-Forwarded-For", "evil")
                .header("X-Forwarded-Host", "evil").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", id);
        var headers = command.calls.getFirst().headers();
        assertThat(headers).doesNotContainKeys("X-user-id", "X-roles", "Forwarded");
        assertThat(headers.get("X-correlation-id")).containsExactly(id);
        assertThat(headers.get("X-forwarded-host")).containsExactly("localhost:8082");
        assertThat(headers.get("X-forwarded-for")).doesNotContain("evil");
        client.get().uri("/api/missing").header("X-Correlation-Id", "bad").exchange()
                .expectStatus().isNotFound().expectHeader().value("X-Correlation-Id", value ->
                        assertThat(UUID.fromString(value).toString()).isEqualTo(value));
    }

    @Test void timeoutAndDisconnectNeverRepeatCommandsOrFallBack() throws Exception {
        client.post().uri("/api/v1/orders?scenario=slow").bodyValue("{}").exchange()
                .expectStatus().isEqualTo(504).expectBody().jsonPath("$.detail")
                .value(detail -> assertThat(detail.toString()).contains("indeterminado"));
        assertThat(command.calls).hasSize(1); assertThat(query.calls).isEmpty();
        command.calls.clear();
        client.post().uri("/api/v1/orders?scenario=disconnect").bodyValue("{}").exchange()
                .expectStatus().isEqualTo(502);
        assertThat(command.calls).hasSize(1);
        client.get().uri("/api/queries/orders?scenario=slow").exchange().expectStatus().isEqualTo(504);
        assertThat(command.calls).hasSize(1);
    }

    @Test void rejectsOversizedFixedAndChunkedBodiesBeforeForwarding() throws Exception {
        byte[] body = new byte[1048577];
        Arrays.fill(body, (byte) 'x');
        var http = HttpClient.newHttpClient();
        for (var publisher : List.of(HttpRequest.BodyPublishers.ofByteArray(body),
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/orders"))
                    .POST(publisher).build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        }
        assertThat(command.calls).isEmpty();
    }

    @Test void acceptsAThousandItemsWithinTheExistingCommandContract() {
        String item = "{\"productId\":\"11111111-1111-4111-8111-111111111111\",\"quantity\":10000}";
        String body = "{\"customerId\":\"11111111-1111-4111-8111-111111111111\",\"items\":["
                + String.join(",", Collections.nCopies(1000, item)) + "]}";
        client.post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        assertThat(command.calls.getFirst().body()).isEqualTo(body);
    }

    @Test void servesIndependentOpenApiDocumentsAndSwagger() {
        client.get().uri("/v3/api-docs/command").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.servers[0].url").isEqualTo("http://localhost:8082")
                .jsonPath("$.paths['/api/v1/customers'].post.servers").doesNotExist()
                .jsonPath("$.paths['/api/v1/order-outbox'].get['x-required-scope']").isEqualTo("cqrs.ops.read")
                .jsonPath("$.paths['/api/queries/orders']").doesNotExist();
        client.get().uri("/v3/api-docs/query").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.paths['/api/queries/orders'].get").exists();
        client.get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection();
        client.get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.urls[0].url").isEqualTo("/v3/api-docs/command")
                .jsonPath("$.urls[1].url").isEqualTo("/v3/api-docs/query");
    }
}
