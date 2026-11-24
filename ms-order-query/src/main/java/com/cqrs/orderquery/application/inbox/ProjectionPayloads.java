package com.cqrs.orderquery.application.inbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Kafka contracts v1; independent from HTTP response DTOs and persistence entities. */
public final class ProjectionPayloads {
    private ProjectionPayloads() {}
    public record CustomerSummary(@NotNull UUID customerId,@NotBlank @Size(max=160) String name,
            @NotBlank @Email @Size(max=254) String email,@NotNull @PositiveOrZero Long orderCount,Instant lastOrderAt) implements ProjectionSnapshot {
        public UUID identity() { return customerId; }
    }
    public record CustomerDetail(@NotNull UUID customerId,@NotBlank @Size(max=160) String name,
            @NotBlank @Email @Size(max=254) String email,@Size(max=24) String phone,
            @NotNull @Size(max=0) List<JsonNode> addresses,@NotNull Instant createdAt,
            @NotNull @PositiveOrZero Long orderCount,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal lifetimeValue) implements ProjectionSnapshot {
        public UUID identity() { return customerId; }
    }
    public record ProductCatalog(@NotNull UUID productId,@NotBlank @Size(max=160) String name,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer=9,fraction=2) BigDecimal price,
            @NotNull @Pattern(regexp="[A-Z]{3}") String currency,@Size(max=2048) String thumbnailUrl,
            @NotNull Boolean available,@Size(max=160) String category) implements ProjectionSnapshot {
        public UUID identity() { return productId; }
    }
    public record ProductDetail(@NotNull UUID productId,@NotBlank @Size(max=160) String name,@Size(max=2000) String description,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer=9,fraction=2) BigDecimal price,
            @NotNull @Pattern(regexp="[A-Z]{3}") String currency,@NotNull List<@NotNull @Size(max=2048) String> imageUrls,
            @NotNull Boolean available,@NotNull Map<@NotBlank @Size(max=128) String,@NotNull @Size(max=2048) String> attributes) implements ProjectionSnapshot {
        public UUID identity() { return productId; }
    }
    public record OrderSummary(@NotNull UUID orderId,@NotNull UUID customerId,@NotBlank @Size(max=160) String customerName,
            @NotNull Instant createdAt,@NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal totalAmount,
            @NotNull @Pattern(regexp="[A-Z]{3}") String currency,
            @NotNull @Pattern(regexp="CREATED|CANCELLED") String status,@NotNull @Min(1) @Max(10000000) Integer itemCount) implements ProjectionSnapshot {
        public UUID identity() { return orderId; }
    }
    public record CustomerInfo(@NotNull UUID customerId,@NotBlank @Size(max=160) String name) {}
    public record OrderItemInfo(@NotNull UUID productId,@NotBlank @Size(max=160) String productName,
            @NotNull @Min(1) @Max(10000) Integer quantity,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer=9,fraction=2) BigDecimal unitPrice,
            @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal subtotal) {}
    public record OrderDetail(@NotNull UUID orderId,@NotNull Instant createdAt,@NotNull @Valid CustomerInfo customer,
            @NotEmpty @Size(max=1000) List<@NotNull @Valid OrderItemInfo> items,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal subtotalAmount,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal discountAmount,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal shippingAmount,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal totalAmount,
            @NotNull @Pattern(regexp="[A-Z]{3}") String currency,
            @NotNull @Pattern(regexp="CREATED|CANCELLED") String status) implements ProjectionSnapshot {
        public UUID identity() { return orderId; }
    }
    public record CustomerOrderHistory(@NotNull UUID orderId,@NotNull UUID customerId,@NotNull Instant createdAt,
            @NotNull @Pattern(regexp="CREATED|CANCELLED") String status,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal totalAmount,
            @NotNull @Pattern(regexp="[A-Z]{3}") String currency,@NotNull @Min(1) @Max(10000000) Integer itemCount,
            @Size(max=160) String firstProductName) implements ProjectionSnapshot {
        public UUID identity() { return orderId; }
    }
}
