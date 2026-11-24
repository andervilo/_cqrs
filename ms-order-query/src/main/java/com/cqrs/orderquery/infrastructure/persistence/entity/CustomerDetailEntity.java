package com.cqrs.orderquery.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="customer_detail_projection")
public class CustomerDetailEntity extends ProjectionEntity {
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
    @Column(name="phone", length=24)
    private String phone;
    public String getPhone() { return phone; }
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="addresses", nullable=false, columnDefinition="json")
    private String addresses;
    public String getAddresses() { return addresses; }
    @Column(name="created_at", nullable=false)
    private Instant createdAt;
    public Instant getCreatedAt() { return createdAt; }
    @Column(name="order_count", nullable=false)
    private long orderCount;
    public long getOrderCount() { return orderCount; }
    @Column(name="lifetime_value", nullable=false, precision=19, scale=2)
    private BigDecimal lifetimeValue;
    public BigDecimal getLifetimeValue() { return lifetimeValue; }
    protected CustomerDetailEntity() {}
}
