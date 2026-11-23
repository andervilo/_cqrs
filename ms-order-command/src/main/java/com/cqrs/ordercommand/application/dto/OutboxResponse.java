package com.cqrs.ordercommand.application.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import com.cqrs.ordercommand.domain.model.*;
import java.time.Instant;
import java.util.UUID;

public record OutboxResponse(UUID id, UUID orderId, Instant dataCriacao, Instant dataEnvio, OutboxStatus status,
        ProjectionEventType eventType, ProjectionOperation operation, String projectionKey, long projectionVersion,
        int schemaVersion, @JsonRawValue @Schema(type = "object") String payload) {}
