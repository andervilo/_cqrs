package com.cqrs.ordercommand.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

@Entity
@Table(name = "item")
public class ItemEntity {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderEntity order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private ProductEntity product;
    @Column(nullable = false, length = 64)
    private String productSku;
    @Column(nullable = false, length = 160)
    private String productName;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    protected ItemEntity() {}
    public ItemEntity(Item item, OrderEntity order, ProductEntity product) {
        this.id = item.id(); this.order = order; this.product = product;
        this.productSku = item.productSku(); this.productName = item.productName();
        this.quantity = item.quantity(); this.unitPrice = item.unitPrice();
    }
    public Item toDomain() { return new Item(id, product.getId(), productSku, productName, quantity, unitPrice); }
    public UUID getId() { return id; }
    public OrderEntity getOrder() { return order; }
    public ProductEntity getProduct() { return product; }
    public String getProductSku() { return productSku; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getSubtotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
}
