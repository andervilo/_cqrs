package com.cqrs.ordercommand.application.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Transport contracts: each record is serialized directly into its own outbox payload. */
public final class ProjectionSnapshots {
    private ProjectionSnapshots() {}
    public record CustomerSummary(UUID customerId, String name, String email, long orderCount, Instant lastOrderAt) {}
    public record CustomerDetail(UUID customerId, String name, String email, String phone,
            List<Object> addresses, Instant createdAt, long orderCount, BigDecimal lifetimeValue) {}
    public record ProductCatalog(UUID productId, String name, BigDecimal price, String currency,
            String thumbnailUrl, boolean available, String category) {}
    public record ProductDetail(UUID productId, String name, String description, BigDecimal price,
            String currency, List<String> imageUrls, boolean available, Map<String,String> attributes) {}
    public record OrderSummary(UUID orderId, UUID customerId, String customerName, Instant createdAt,
            BigDecimal totalAmount, String currency, String status, int itemCount) {}
    public record CustomerInfo(UUID customerId, String name) {}
    public record OrderItemInfo(UUID productId, String productName, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {}
    public record OrderDetail(UUID orderId, Instant createdAt, CustomerInfo customer, List<OrderItemInfo> items,
            BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal shippingAmount,
            BigDecimal totalAmount, String currency, String status) {}
    public record CustomerOrderHistory(UUID orderId, UUID customerId, Instant createdAt, String status,
            BigDecimal totalAmount, String currency, int itemCount, String firstProductName) {}
}
