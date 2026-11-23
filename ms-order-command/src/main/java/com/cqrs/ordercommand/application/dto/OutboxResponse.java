package com.cqrs.ordercommand.application.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

public record OutboxResponse(UUID id, UUID orderId, Instant dataCriacao, Instant dataEnvio, OutboxStatus status) {}
