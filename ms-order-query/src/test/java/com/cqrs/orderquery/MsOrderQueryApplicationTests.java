package com.cqrs.orderquery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@Import({TestcontainersConfiguration.class, MsOrderQueryApplicationTests.FixtureConfiguration.class})
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties="query.projections.enabled=false")
class MsOrderQueryApplicationTests {

    private static final String TOPIC = "ms-order-query.integration.v1";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumers;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void connectsToMySqlAndInitializesFlywayHistory() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("cqrs");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false",
                Integer.class)).isZero();
    }

    @Test
    void publishesAndConsumesKafkaMessage() throws Exception {
        String payload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"schemaVersion\":1}";
        try (var consumer = consumers.createConsumer("test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of(TOPIC));
            kafka.send(TOPIC, "order-1", payload).get(20, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            boolean received = false;
            while (!received && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofSeconds(1))) {
                    if (payload.equals(record.value()) && "order-1".equals(record.key())) {
                        received = true;
                    }
                }
            }
            assertThat(received).as("Kafka deve entregar a mensagem publicada").isTrue();
        }
    }

    @Test
    void exposesOpenApiSwaggerAndHealth() throws Exception {
        var docs = get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("Order Query API");
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
        var health = get("/actuator/health/readiness");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    void returnsProblemDetailForInvalidAndMalformedRequests() throws Exception {
        for (String body : List.of("{\"name\":\"\"}", "{")) {
            var response = http.send(HttpRequest.newBuilder(uri("/test/validation"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .contains("application/problem+json");
            assertThat(response.body()).contains("\"status\":400");
        }
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        var response = get("/test/failure");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("Nao foi possivel processar a requisicao.")
                .doesNotContain("sensitive-internal-detail", "stackTrace");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        NewTopic integrationTopic() {
            return new NewTopic(TOPIC, 1, (short) 1);
        }

        @Bean
        ValidationController validationController() {
            return new ValidationController();
        }
    }

    @RestController
    static class ValidationController {
        @PostMapping("/test/validation")
        void validate(@Valid @RequestBody Input input) {
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/failure")
        void fail() {
            throw new IllegalStateException("sensitive-internal-detail");
        }
    }

    record Input(@NotBlank String name) {
    }
}
