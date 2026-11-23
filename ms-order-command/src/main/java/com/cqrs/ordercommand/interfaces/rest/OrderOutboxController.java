package com.cqrs.ordercommand.interfaces.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.cqrs.ordercommand.application.dto.*;
import com.cqrs.ordercommand.application.service.*;
import com.cqrs.ordercommand.domain.model.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1/order-outbox")
@Tag(name = "Order outbox")
public class OrderOutboxController {
    private final OrderOutboxService service;
    public OrderOutboxController(OrderOutboxService service) { this.service=service; }
    @GetMapping("/{id}")
    public OutboxResponse get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping
    public PageResponse<OutboxResponse> list(@RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(required = false) ProjectionEventType eventType,
            @RequestParam(required = false) @Size(max = 128) String projectionKey,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(orderId, status, eventType, projectionKey, PageRequests.of(page, size, "dataCriacao"));
    }
}
