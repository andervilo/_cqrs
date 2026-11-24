package com.cqrs.orderquery.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class ProjectionResponses {
    private ProjectionResponses() {}
    public record CustomerInfoResponse(UUID customerId,String name) {}
    public record OrderItemResponse(UUID productId,String productName,int quantity,BigDecimal unitPrice,BigDecimal subtotal) {}
    public record CustomerSummaryResponse(UUID customerId, String name, String email, long orderCount, Instant lastOrderAt) {}
    public record CustomerDetailResponse(UUID customerId, String name, String email, String phone, List<JsonNode> addresses, Instant createdAt, long orderCount, BigDecimal lifetimeValue) {}
    public record ProductCatalogResponse(UUID productId, String name, BigDecimal price, String currency, String thumbnailUrl, boolean available, String category) {}
    public record ProductDetailResponse(UUID productId, String name, String description, BigDecimal price, String currency, List<String> imageUrls, boolean available, Map<String,String> attributes) {}
    public record OrderSummaryResponse(UUID orderId, UUID customerId, String customerName, Instant createdAt, BigDecimal totalAmount, String currency, String status, int itemCount) {}
    public record OrderDetailResponse(UUID orderId, Instant createdAt, CustomerInfoResponse customer, List<OrderItemResponse> items, BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal shippingAmount, BigDecimal totalAmount, String currency, String status) {}
    public record CustomerOrderHistoryResponse(UUID orderId, UUID customerId, Instant createdAt, String status, BigDecimal totalAmount, String currency, int itemCount, String firstProductName) {}
}
