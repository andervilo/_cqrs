package com.cqrs.ordercommand.application.projection;

import com.cqrs.ordercommand.domain.model.ProjectionEventType;
import java.util.UUID;

public record ProjectionTarget(ProjectionEventType type, UUID id) implements Comparable<ProjectionTarget> {
    public String key() { return id.toString(); }
    @Override public int compareTo(ProjectionTarget other) {
        int byType = type.name().compareTo(other.type.name());
        return byType == 0 ? key().compareTo(other.key()) : byType;
    }
}
