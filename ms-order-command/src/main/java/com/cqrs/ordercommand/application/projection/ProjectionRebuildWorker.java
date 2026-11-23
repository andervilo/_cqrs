package com.cqrs.ordercommand.application.projection;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.cqrs.ordercommand.infrastructure.persistence.ProjectionLocks;

/** Each resource and its related snapshots form an independent transaction. */
@Service
public class ProjectionRebuildWorker {
    private final ProjectionLocks locks;
    private final ProjectionUpdateService projections;
    public ProjectionRebuildWorker(ProjectionLocks locks, ProjectionUpdateService projections) {
        this.locks=locks; this.projections=projections;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void customer(UUID id) {
        if (locks.customerIfPresent(id)) projections.rebuildCustomer(id);
        else projections.customerDeleted(id);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void product(UUID id) {
        if (locks.productIfPresent(id)) projections.rebuildProduct(id);
        else projections.productDeleted(id);
    }
}
