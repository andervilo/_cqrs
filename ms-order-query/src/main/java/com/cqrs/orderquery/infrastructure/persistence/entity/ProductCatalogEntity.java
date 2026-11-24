package com.cqrs.orderquery.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="product_catalog_projection")
public class ProductCatalogEntity extends ProjectionEntity {
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name="product_id", nullable=false)
    private UUID productId;
    public UUID getProductId() { return productId; }
    @Column(name="name", nullable=false, length=160)
    private String name;
    public String getName() { return name; }
    @Column(name="price", nullable=false, precision=19, scale=2)
    private BigDecimal price;
    public BigDecimal getPrice() { return price; }
    @Column(name="currency", nullable=false, length=3)
    private String currency;
    public String getCurrency() { return currency; }
    @Column(name="thumbnail_url", length=2048)
    private String thumbnailUrl;
    public String getThumbnailUrl() { return thumbnailUrl; }
    @Column(name="available", nullable=false)
    private boolean available;
    public boolean isAvailable() { return available; }
    @Column(name="category", length=160)
    private String category;
    public String getCategory() { return category; }
    protected ProductCatalogEntity() {}
}
