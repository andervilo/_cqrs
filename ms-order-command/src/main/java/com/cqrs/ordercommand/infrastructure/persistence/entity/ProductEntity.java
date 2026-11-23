package com.cqrs.ordercommand.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

@Entity
@Table(name = "product")
public class ProductEntity extends AuditedEntity {
    @Column(nullable = false, unique = true, length = 64)
    private String sku;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(length = 2000)
    private String description;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;
    @Column(nullable = false)
    private boolean active;
    protected ProductEntity() {}

    public ProductEntity(String sku, String name, String description, BigDecimal unitPrice) {
        super(UUID.randomUUID());
        update(sku, name, description, unitPrice, true);
    }
    public void update(String sku, String name, String description, BigDecimal unitPrice, boolean active) {
        this.sku = sku.strip().toUpperCase(Locale.ROOT);
        this.name = name.strip();
        this.description = description == null || description.isBlank() ? null : description.strip();
        this.unitPrice = unitPrice.setScale(2, java.math.RoundingMode.UNNECESSARY);
        this.active = active;
    }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public boolean isActive() { return active; }
}
