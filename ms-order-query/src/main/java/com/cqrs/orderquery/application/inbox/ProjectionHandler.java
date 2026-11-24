package com.cqrs.orderquery.application.inbox;

import com.cqrs.orderquery.domain.ProjectionEventType;
import java.util.UUID;

public interface ProjectionHandler {
    ProjectionEventType type();
    Class<? extends ProjectionSnapshot> payloadType();
    void upsert(ProjectionSnapshot snapshot,long version);
    void delete(UUID key);
}
