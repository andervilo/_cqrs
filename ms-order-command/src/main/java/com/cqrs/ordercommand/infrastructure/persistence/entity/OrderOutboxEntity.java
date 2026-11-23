package com.cqrs.ordercommand.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

@Entity
@Table(name = "order_outbox")
public class OrderOutboxEntity {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderEntity order;
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private Instant dataCriacao;
    @Column(name = "data_envio")
    private Instant dataEnvio;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    protected OrderOutboxEntity() {}
    public OrderOutboxEntity(OrderEntity order) {
        this.id = UUID.randomUUID(); this.order = order;
        this.dataCriacao = Instant.now(); this.status = OutboxStatus.PENDING;
    }
    public UUID getId() { return id; }
    public OrderEntity getOrder() { return order; }
    public Instant getDataCriacao() { return dataCriacao; }
    public Instant getDataEnvio() { return dataEnvio; }
    public OutboxStatus getStatus() { return status; }
}
