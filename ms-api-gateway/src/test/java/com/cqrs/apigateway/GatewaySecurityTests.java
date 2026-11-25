package com.cqrs.apigateway;

import com.cqrs.apigateway.config.EndpointCatalog;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.maintenance-enabled=true", "gateway.public-url=https://api.example.com",
                "management.server.port=0"})
@ActiveProfiles("prod")
class GatewaySecurityTests {
    static final TestIdentityProvider identity = new TestIdentityProvider();
    static final BackendStub command = new BackendStub("command");
    static final BackendStub query = new BackendStub("query");
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Value("${local.management.port}") int managementPort;
    WebTestClient client;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.command-service-url", command::url);
        registry.add("gateway.query-service-url", query::url);
        registry.add("gateway.jwt-issuer-uri", identity::issuer);
        registry.add("gateway.jwt-audience", () -> "cqrs-api");
    }
    @BeforeEach void setup() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(15)).build();
        command.calls.clear(); query.calls.clear();
    }
    @AfterAll static void close() { identity.close(); command.close(); query.close(); }

    @Test void rejectsAbsentInvalidExpiredWrongIssuerAudienceAndSignature() throws Exception {
        client.get().uri("/api/queries/orders").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectHeader().exists("X-Correlation-Id").expectBody().jsonPath("$.status").isEqualTo(401);
        List<String> invalid = List.of("invalid",
                identity.token("cqrs.read", identity.issuer(), "other", Instant.now().plusSeconds(300), identity.key()),
                identity.token("cqrs.read", "https://other.example", "cqrs-api", Instant.now().plusSeconds(300), identity.key()),
                identity.token("cqrs.read", identity.issuer(), "cqrs-api", Instant.now().minusSeconds(300), identity.key()),
                identity.token("cqrs.read", identity.issuer(), "cqrs-api", Instant.now().plusSeconds(300),
                        new RSAKeyGenerator(2048).keyID("test-key").generate()));
        for (String token : invalid) client.get().uri("/api/queries/orders").headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.status").isEqualTo(401);
        assertThat(command.calls).isEmpty(); assertThat(query.calls).isEmpty();
    }

    @Test void enforcesEachCataloguedScopeAndForwardsOriginalBearer() {
        for (var endpoint : new EndpointCatalog().all()) {
            String path = endpoint.path().replaceAll("\\{[^}]+}", "11111111-1111-4111-8111-111111111111");
            client.method(endpoint.method()).uri(path).headers(h -> h.setBearerAuth(identity.token("unrelated")))
                    .exchange().expectStatus().isForbidden();
            String token = identity.token(endpoint.scope());
            client.method(endpoint.method()).uri(path).headers(h -> h.setBearerAuth(token))
                    .exchange().expectStatus().is2xxSuccessful();
            var destination = endpoint.query() ? query : command;
            assertThat(destination.calls.getLast().headers().get("Authorization")).containsExactly("Bearer " + token);
        }
        assertThat(command.calls).hasSize(21); assertThat(query.calls).hasSize(8);
    }

    @Test void normalScopesDoNotGrantOperationsOrDocs() {
        client.get().uri("/api/queries/dashboard/sales").headers(h->h.setBearerAuth(identity.token("cqrs.read cqrs.command.read cqrs.write")))
                .exchange().expectStatus().isForbidden();
        for (String path : List.of("/api/v1/order-outbox", "/v3/api-docs/command"))
            client.get().uri(path).headers(h -> h.setBearerAuth(identity.token("cqrs.read cqrs.write cqrs.command.read")))
                    .exchange().expectStatus().isForbidden();
        for (String path : List.of("/api/v1/order-outbox/id/retry", "/api/v1/projections/rebuild"))
            client.post().uri(path).headers(h -> h.setBearerAuth(identity.token("cqrs.write")))
                    .exchange().expectStatus().isForbidden();
        assertThat(command.calls).isEmpty();
        client.get().uri("/v3/api-docs/command").headers(h -> h.setBearerAuth(identity.token("cqrs.docs")))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.paths['/api/v1/customers'].post.security[0].bearerAuth").isArray()
                .jsonPath("$.servers[0].url").isEqualTo("https://api.example.com");
    }

    @Test void permitsPreflightAndMinimalHealthButNotDetailedPublicManagement() {
        client.options().uri("/api/v1/orders").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
        client.get().uri("http://127.0.0.1:" + managementPort + "/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("http://127.0.0.1:" + managementPort + "/actuator/prometheus").exchange().expectStatus().isOk();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/unknown").exchange().expectStatus().isNotFound();
        assertThat(command.calls).isEmpty();
    }
}
