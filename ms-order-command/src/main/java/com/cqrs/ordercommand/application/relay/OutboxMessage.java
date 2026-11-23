package com.cqrs.ordercommand.application.relay;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(UUID id, String eventType, String operation, String projectionKey,
        long projectionVersion, int schemaVersion, Instant occurredAt, String payload, int attemptCount) {
    public String kafkaKey() { return eventType+":"+projectionKey; }
}
