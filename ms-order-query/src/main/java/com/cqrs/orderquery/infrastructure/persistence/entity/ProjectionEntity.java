package com.cqrs.orderquery.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;

@MappedSuperclass
public abstract class ProjectionEntity {
    @Column(nullable=false) private long projectionVersion;
    @Column(nullable=false) private Instant projectedAt;
    public long getProjectionVersion() { return projectionVersion; }
    public Instant getProjectedAt() { return projectedAt; }
}
