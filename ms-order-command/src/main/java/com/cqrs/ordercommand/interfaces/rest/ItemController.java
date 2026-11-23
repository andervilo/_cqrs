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
@RequestMapping("/api/v1/orders/{orderId}/items")
@Tag(name = "Items")
public class ItemController {
    private final OrderService service;
    private final ItemService items;
    public ItemController(OrderService service, ItemService items) { this.service = service; this.items = items; }
    @GetMapping
    public List<OrderDtos.ItemResponse> list(@PathVariable UUID orderId) { return items.list(orderId); }
    @GetMapping("/{itemId}")
    public OrderDtos.ItemResponse get(@PathVariable UUID orderId, @PathVariable UUID itemId) {
        return items.get(orderId, itemId);
    }
    @PutMapping
    @Operation(summary = "Substituir itens e recalcular total; exige pelo menos um item")
    public OrderDtos.Response replace(@PathVariable UUID orderId, @Valid @RequestBody OrderDtos.ReplaceItems input) {
        return service.replaceItems(orderId, input);
    }
}
