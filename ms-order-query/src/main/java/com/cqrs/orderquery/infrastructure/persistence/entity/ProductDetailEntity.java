package com.cqrs.orderquery.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="product_detail_projection")
public class ProductDetailEntity extends ProjectionEntity {
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name="product_id", nullable=false)
    private UUID productId;
    public UUID getProductId() { return productId; }
    @Column(name="name", nullable=false, length=160)
    private String name;
    public String getName() { return name; }
    @Column(name="description", length=2000)
    private String description;
    public String getDescription() { return description; }
    @Column(name="price", nullable=false, precision=19, scale=2)
    private BigDecimal price;
    public BigDecimal getPrice() { return price; }
    @Column(name="currency", nullable=false, length=3)
    private String currency;
    public String getCurrency() { return currency; }
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="image_urls", nullable=false, columnDefinition="json")
    private String imageUrls;
    public String getImageUrls() { return imageUrls; }
    @Column(name="available", nullable=false)
    private boolean available;
    public boolean isAvailable() { return available; }
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="attributes", nullable=false, columnDefinition="json")
    private String attributes;
    public String getAttributes() { return attributes; }
    protected ProductDetailEntity() {}
}
