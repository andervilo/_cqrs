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
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }
    @PostMapping
    @Operation(summary = "Criar pedido e registrar outbox na mesma transacao")
    public ResponseEntity<OrderDtos.Response> create(@Valid @RequestBody OrderDtos.Create input) {
        var result = service.create(input);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + result.id())).body(result);
    }
    @GetMapping("/{id}")
    public OrderDtos.Response get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping
    public PageResponse<OrderDtos.Response> list(@RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(customerId, status, PageRequests.of(page, size));
    }
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancelar pedido sem apagar seu historico")
    public OrderDtos.Response cancel(@PathVariable UUID id) { return service.cancel(id); }
}
