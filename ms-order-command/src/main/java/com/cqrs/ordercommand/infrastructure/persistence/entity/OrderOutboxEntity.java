package com.cqrs.ordercommand.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;
import com.cqrs.ordercommand.domain.model.*;

@Entity
@Table(name = "order_outbox")
public class OrderOutboxEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", updatable = false)
    private OrderEntity order;
    @Column(name = "data_criacao", nullable = false, updatable = false)
    private Instant dataCriacao;
    @Column(name = "data_envio") private Instant dataEnvio;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private OutboxStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64, updatable = false)
    private ProjectionEventType eventType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16, updatable = false)
    private ProjectionOperation operation;
    @Column(nullable = false, length = 128, updatable = false) private String projectionKey;
    @Column(nullable = false, updatable = false) private long projectionVersion;
    @Column(nullable = false, updatable = false) private int schemaVersion;

    @Column(insertable=false,updatable=false) private int attemptCount;
    @Column(insertable=false,updatable=false) private Instant nextAttemptAt;
    @Column(insertable=false,updatable=false,length=256) private String lastError;

    protected OrderOutboxEntity() {}
    public OrderOutboxEntity(OrderEntity order, ProjectionEventType type, ProjectionOperation operation,
            String key, long version, String payload) {
        this.id = UUID.randomUUID(); this.order = order; this.dataCriacao = Instant.now();
        this.status = OutboxStatus.PENDING; this.eventType = type; this.operation = operation;
        this.projectionKey = key; this.projectionVersion = version; this.schemaVersion = 1; this.payload = payload;
    }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public UUID getId() { return id; }
    public OrderEntity getOrder() { return order; }
    public Instant getDataCriacao() { return dataCriacao; }
    public Instant getDataEnvio() { return dataEnvio; }
    public OutboxStatus getStatus() { return status; }
    public ProjectionEventType getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public ProjectionOperation getOperation() { return operation; }
    public String getProjectionKey() { return projectionKey; }
    public long getProjectionVersion() { return projectionVersion; }
    public int getSchemaVersion() { return schemaVersion; }
}
