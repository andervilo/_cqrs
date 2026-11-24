package com.cqrs.orderquery.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="customer_summary_projection")
public class CustomerSummaryEntity extends ProjectionEntity {
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name="customer_id", nullable=false)
    private UUID customerId;
    public UUID getCustomerId() { return customerId; }
    @Column(name="name", nullable=false, length=160)
    private String name;
    public String getName() { return name; }
    @Column(name="email", nullable=false, length=254)
    private String email;
    public String getEmail() { return email; }
    @Column(name="order_count", nullable=false)
    private long orderCount;
    public long getOrderCount() { return orderCount; }
    @Column(name="last_order_at")
    private Instant lastOrderAt;
    public Instant getLastOrderAt() { return lastOrderAt; }
    protected CustomerSummaryEntity() {}
}
