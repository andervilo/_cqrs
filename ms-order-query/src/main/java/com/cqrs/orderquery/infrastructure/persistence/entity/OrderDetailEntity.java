package com.cqrs.orderquery.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="order_detail_projection")
public class OrderDetailEntity extends ProjectionEntity {
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name="order_id", nullable=false)
    private UUID orderId;
    public UUID getOrderId() { return orderId; }
    @Column(name="created_at", nullable=false)
    private Instant createdAt;
    public Instant getCreatedAt() { return createdAt; }
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name="customer_id", nullable=false)
    private UUID customerId;
    public UUID getCustomerId() { return customerId; }
    @Column(name="customer_name", nullable=false, length=160)
    private String customerName;
    public String getCustomerName() { return customerName; }
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="items", nullable=false, columnDefinition="json")
    private String items;
    public String getItems() { return items; }
    @Column(name="subtotal_amount", nullable=false, precision=19, scale=2)
    private BigDecimal subtotalAmount;
    public BigDecimal getSubtotalAmount() { return subtotalAmount; }
    @Column(name="discount_amount", nullable=false, precision=19, scale=2)
    private BigDecimal discountAmount;
    public BigDecimal getDiscountAmount() { return discountAmount; }
    @Column(name="shipping_amount", nullable=false, precision=19, scale=2)
    private BigDecimal shippingAmount;
    public BigDecimal getShippingAmount() { return shippingAmount; }
    @Column(name="total_amount", nullable=false, precision=19, scale=2)
    private BigDecimal totalAmount;
    public BigDecimal getTotalAmount() { return totalAmount; }
    @Column(name="currency", nullable=false, length=3)
    private String currency;
    public String getCurrency() { return currency; }
    @Column(name="status", nullable=false, length=30)
    private String status;
    public String getStatus() { return status; }
    protected OrderDetailEntity() {}
}
