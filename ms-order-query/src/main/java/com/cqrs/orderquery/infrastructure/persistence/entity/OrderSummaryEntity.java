package com.cqrs.orderquery.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="order_summary_projection")
public class OrderSummaryEntity extends ProjectionEntity {
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name="order_id", nullable=false)
    private UUID orderId;
    public UUID getOrderId() { return orderId; }
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name="customer_id", nullable=false)
    private UUID customerId;
    public UUID getCustomerId() { return customerId; }
    @Column(name="customer_name", nullable=false, length=160)
    private String customerName;
    public String getCustomerName() { return customerName; }
    @Column(name="created_at", nullable=false)
    private Instant createdAt;
    public Instant getCreatedAt() { return createdAt; }
    @Column(name="total_amount", nullable=false, precision=19, scale=2)
    private BigDecimal totalAmount;
    public BigDecimal getTotalAmount() { return totalAmount; }
    @Column(name="currency", nullable=false, length=3)
    private String currency;
    public String getCurrency() { return currency; }
    @Column(name="status", nullable=false, length=30)
    private String status;
    public String getStatus() { return status; }
    @Column(name="item_count", nullable=false)
    private int itemCount;
    public int getItemCount() { return itemCount; }
    protected OrderSummaryEntity() {}
}
