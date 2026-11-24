package com.cqrs.orderquery.application.inbox;

import com.cqrs.orderquery.domain.*;
import java.time.Instant;
import java.util.UUID;

public record DecodedProjectionEvent(UUID eventId,ProjectionEventType eventType,UUID projectionKey,
        long projectionVersion,int schemaVersion,ProjectionOperation operation,Instant occurredAt,
        ProjectionSnapshot snapshot,byte[] contentHash,String topic,int partition,long offset) {
    public DecodedProjectionEvent { contentHash=contentHash.clone(); }
    @Override public byte[] contentHash() { return contentHash.clone(); }
}
