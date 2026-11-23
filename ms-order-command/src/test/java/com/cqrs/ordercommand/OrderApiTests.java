package com.cqrs.ordercommand;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "outbox.relay.enabled=false")
class OrderApiTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newHttpClient();

    private JsonNode request(String method, String path, Object body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(java.time.Duration.ofSeconds(15)).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body().isBlank() ? null : json.readTree(response.body());
    }

    private String customer() throws Exception {
        return request("POST", "/customers", Map.of("name", "Cliente", "email", UUID.randomUUID() + "@example.com"), 201)
                .path("id").asText();
    }

    private JsonNode product(String price) throws Exception {
        return request("POST", "/products", Map.of("sku", UUID.randomUUID().toString(),
                "name", "Produto", "unitPrice", new BigDecimal(price)), 201);
    }

    @Test
    void createsOrderWithSnapshotsAndTransactionalOutbox() throws Exception {
        var customer = customer();
        assertThat(request("GET", "/orders?customerId=" + customer, null, 200).path("totalElements").asLong()).isZero();
        var product = product("12.30");
        String productId = product.path("id").asText();
        var order = request("POST", "/orders", Map.of("customerId", customer,
                "items", List.of(Map.of("productId", productId, "quantity", 3))), 201);
        String orderId = order.path("id").asText();
        assertThat(UUID.fromString(orderId)).isNotNull();
        assertThat(order.path("totalAmount").decimalValue()).isEqualByComparingTo("36.90");
        assertThat(order.path("items").get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("12.30");
        request("PUT", "/products/" + productId, Map.of("sku", product.path("sku").asText(),
                "name", "Novo nome", "unitPrice", new BigDecimal("99.99"), "active", true), 200);
        var stored = request("GET", "/orders/" + orderId, null, 200);
        assertThat(stored.path("items").get(0).path("productName").asText()).isEqualTo("Produto");
        assertThat(stored.path("totalAmount").decimalValue()).isEqualByComparingTo("36.90");
        var outbox = request("GET", "/order-outbox?orderId=" + orderId, null, 200);
        assertThat(outbox.path("totalElements").asLong()).isEqualTo(3);
        assertThat(outbox.path("content").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(outbox.path("content").get(0).path("dataEnvio").isNull()).isTrue();
        request("DELETE", "/customers/" + customer, null, 409);
        request("DELETE", "/products/" + productId, null, 409);
        request("PUT", "/orders/" + orderId + "/items", Map.of("items", List.of()), 400);
        request("PUT", "/orders/" + orderId + "/items", Map.of("items",
                List.of(Map.of("productId", productId, "quantity", 2))), 200);
        assertThat(request("GET", "/orders/" + orderId, null, 200).path("totalAmount").decimalValue())
                .isEqualByComparingTo("199.98");
        request("POST", "/orders/" + orderId + "/cancel", null, 200);
        request("PUT", "/orders/" + orderId + "/items", Map.of("items",
                List.of(Map.of("productId", productId, "quantity", 1))), 409);
        assertThat(request("GET", "/order-outbox?orderId=" + orderId, null, 200)
                .path("totalElements").asLong()).isEqualTo(9);
    }

    @Test
    void rejectsInvalidCommandsWithoutPartialOrderOrOutbox() throws Exception {
        String customer = customer();
        var p = product("10.00");
        String product = p.path("id").asText();
        request("POST", "/products", Map.of("sku", p.path("sku").asText(), "name", "Duplicado", "unitPrice", 10), 409);
        request("POST", "/orders", Map.of("customerId", customer, "items", List.of()), 400);
        request("POST", "/orders", Map.of("customerId", customer,
                "items", List.of(Map.of("productId", product, "quantity", 0))), 400);
        request("POST", "/orders", Map.of("customerId", customer,
                "items", List.of(Map.of("productId", UUID.randomUUID(), "quantity", 1))), 404);
        var item = Map.of("productId", product, "quantity", 1);
        request("POST", "/orders", Map.of("customerId", customer, "items", List.of(item, item)), 400);
        request("GET", "/orders/" + UUID.randomUUID(), null, 404);
        request("GET", "/orders/not-a-uuid", null, 400);
        request("GET", "/orders?size=101", null, 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.orders WHERE customer_id = ?",
                Integer.class, UUID.fromString(customer))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox o JOIN order_command.orders r ON r.id=o.order_id WHERE r.customer_id=?",
                Integer.class, UUID.fromString(customer))).isZero();
    }

    @Test
    void databaseEnforcesOrderMinimumItemAndOutboxStatus() throws Exception {
        UUID customer = UUID.fromString(customer());
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> jdbc.update(
                "INSERT INTO order_command.orders(id,customer_id,status,total_amount,created_at,updated_at,version) VALUES (?,?,'CREATED',0,now(),now(),0)",
                UUID.randomUUID(), customer))).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("Order must contain 1..1000 items");
        var product = product("10.00");
        var order = request("POST", "/orders", Map.of("customerId", customer, "items", List.of(
                Map.of("productId", product.path("id").asText(), "quantity", 1))), 201);
        UUID orderId = UUID.fromString(order.path("id").asText());
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> jdbc.update(
                "DELETE FROM order_command.item WHERE order_id=?", orderId))).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("Order must contain 1..1000 items");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.item WHERE order_id=?", Integer.class, orderId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE order_command.order_outbox SET status='SENDED' WHERE order_id=?", orderId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rollsBackOrderWhenOutboxCannotBePersisted() throws Exception {
        String customer = customer();
        var product = product("15.00");
        jdbc.execute("ALTER TABLE order_command.order_outbox ADD CONSTRAINT test_block_outbox CHECK (false) NOT VALID");
        try {
            request("POST", "/orders", Map.of("customerId", customer, "items", List.of(
                    Map.of("productId", product.path("id").asText(), "quantity", 1))), 409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.orders WHERE customer_id=?",
                    Integer.class, UUID.fromString(customer))).isZero();
            assertThat(jdbc.queryForObject("SELECT max(version) FROM order_command.projection_revision WHERE projection_key=?",
                    Long.class, customer)).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE order_command.order_outbox DROP CONSTRAINT test_block_outbox");
        }
    }

    @Test
    void supportsMultipleOrdersItemsAndScopedItemReads() throws Exception {
        String customer = customer();
        var first = product("0.10");
        var second = product("20.25");
        var command = Map.of("customerId", customer, "items", List.of(
                Map.of("productId", first.path("id").asText(), "quantity", 3),
                Map.of("productId", second.path("id").asText(), "quantity", 2)));
        var order = request("POST", "/orders", command, 201);
        var other = request("POST", "/orders", command, 201);
        assertThat(order.path("items").size()).isEqualTo(2);
        assertThat(order.path("totalAmount").decimalValue()).isEqualByComparingTo("40.80");
        String itemId = order.path("items").get(0).path("id").asText();
        request("GET", "/orders/" + order.path("id").asText() + "/items/" + itemId, null, 200);
        request("GET", "/orders/" + other.path("id").asText() + "/items/" + itemId, null, 404);
        assertThat(request("GET", "/orders?customerId=" + customer, null, 200)
                .path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void exposesProjectionPayloadAsJsonAndSupportsRebuildPages() throws Exception {
        String id=customer();
        var events=request("GET", "/order-outbox?eventType=CUSTOMER_SUMMARY&projectionKey="+id, null, 200);
        assertThat(events.path("totalElements").asLong()).isEqualTo(1);
        var event=events.path("content").get(0);
        assertThat(event.path("orderId").isNull()).isTrue();
        assertThat(event.path("payload").isObject()).isTrue();
        assertThat(event.path("payload").path("customerId").asText()).isEqualTo(id);
        assertThat(event.path("projectionVersion").asLong()).isEqualTo(1);
        assertThat(event.path("operation").asText()).isEqualTo("UPSERT");
        var page=request("POST", "/projections/rebuild?resourceType=PRODUCT&size=1",null,200);
        assertThat(page.path("resourceType").asText()).isEqualTo("PRODUCT");
        request("POST", "/projections/rebuild?resourceType=CUSTOMER&size=101",null,400);
    }

    @Test
    void rejectsInactiveReferencesAndInvalidPricesAndNormalizesUniqueKeys() throws Exception {
        String email = UUID.randomUUID() + "@EXAMPLE.COM";
        var customer = request("POST", "/customers", Map.of("name", "Cliente", "email", email), 201);
        assertThat(customer.path("email").asText()).isEqualTo(email.toLowerCase(Locale.ROOT));
        request("POST", "/customers", Map.of("name", "Outro", "email", email.toLowerCase(Locale.ROOT)), 409);
        request("POST", "/products", Map.of("sku", "SKU-INVALID", "name", "Produto", "unitPrice", "1.001"), 400);
        var product = product("12.00");
        request("PUT", "/products/" + product.path("id").asText(), Map.of("sku", product.path("sku").asText(),
                "name", "Produto", "unitPrice", 12, "active", false), 200);
        request("POST", "/orders", Map.of("customerId", customer.path("id").asText(), "items", List.of(
                Map.of("productId", product.path("id").asText(), "quantity", 1))), 409);
        var active = product("5.00");
        request("PUT", "/customers/" + customer.path("id").asText(), Map.of("name", "Cliente", "email", email,
                "active", false), 200);
        request("POST", "/orders", Map.of("customerId", customer.path("id").asText(), "items", List.of(
                Map.of("productId", active.path("id").asText(), "quantity", 1))), 409);
        request("DELETE", "/customers/" + customer.path("id").asText(), null, 204);
        request("DELETE", "/products/" + active.path("id").asText(), null, 204);
    }
}
