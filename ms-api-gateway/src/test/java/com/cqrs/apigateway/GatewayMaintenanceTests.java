package com.cqrs.apigateway;

import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class GatewayMaintenanceTests {
    static final BackendStub command = new BackendStub("command");
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.command-service-url", command::url);
        registry.add("gateway.query-service-url", () -> "http://127.0.0.1:1");
    }
    @AfterAll static void close() { command.close(); }

    @Test void maintenanceIsDisabledAndCommandSurvivesUnavailableQuery() {
        var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
        for (String path : new String[]{"/api/v1/order-outbox", "/api/v1/order-outbox/id",
                "/api/v1/order-outbox/id/retry", "/api/v1/projections/rebuild"}) {
            client.get().uri(path).exchange().expectStatus().isNotFound();
            client.post().uri(path).exchange().expectStatus().isNotFound();
        }
        assertThat(command.calls).isEmpty();
        client.get().uri("/v3/api-docs/command").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.paths['/api/v1/order-outbox']").doesNotExist();
        client.get().uri("/api/queries/orders").exchange().expectStatus().isEqualTo(503);
        client.get().uri("/v3/api-docs/query").exchange().expectStatus().isEqualTo(503);
        client.get().uri("/api/v1/orders").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }
}
