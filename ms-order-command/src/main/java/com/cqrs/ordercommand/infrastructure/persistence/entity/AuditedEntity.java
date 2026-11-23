package com.cqrs.ordercommand.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

@MappedSuperclass
public abstract class AuditedEntity {
    @Id
    private UUID id;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    protected AuditedEntity() {}
    protected AuditedEntity(UUID id) { this.id = id; }
    @PrePersist
    void initializeDates() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate
    void updateDate() { updatedAt = Instant.now(); }
    protected void touch() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
