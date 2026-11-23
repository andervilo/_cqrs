package com.cqrs.ordercommand.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

@Entity
@Table(name = "orders")
public class OrderEntity extends AuditedEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerEntity customer;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;
    @Column(length = 1000)
    private String notes;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ItemEntity> items = new ArrayList<>();

    protected OrderEntity() {}
    public OrderEntity(Order order, CustomerEntity customer, Map<UUID, ProductEntity> products) {
        super(order.id());
        this.customer = customer;
        this.notes = order.notes();
        replaceItems(order, products);
    }
    public void replaceItems(Order order, Map<UUID, ProductEntity> products) {
        this.status = order.status();
        this.totalAmount = order.totalAmount();
        this.items.clear();
        order.items().forEach(item -> this.items.add(new ItemEntity(item, this, products.get(item.productId()))));
        touch();
    }
    public void cancel(Order cancelled) {
        status = cancelled.status();
        touch();
    }
    public Order toDomain() {
        return new Order(getId(), customer.getId(), items.stream().map(ItemEntity::toDomain).toList(), status, notes);
    }
    public CustomerEntity getCustomer() { return customer; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getNotes() { return notes; }
    public List<ItemEntity> getItems() { return Collections.unmodifiableList(items); }
}
