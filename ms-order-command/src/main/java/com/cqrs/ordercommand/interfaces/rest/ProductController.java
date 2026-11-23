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
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service = service; }
    @PostMapping
    @Operation(summary = "Criar product")
    public ResponseEntity<ProductDtos.Response> create(@Valid @RequestBody ProductDtos.Create input) {
        var result = service.create(input);
        return ResponseEntity.created(URI.create("/api/v1/products/" + result.id())).body(result);
    }
    @GetMapping("/{id}")
    public ProductDtos.Response get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping
    public PageResponse<ProductDtos.Response> list(@RequestParam(required = false) @Size(max = 160) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(q, PageRequests.of(page, size));
    }
    @PutMapping("/{id}")
    public ProductDtos.Response update(@PathVariable UUID id, @Valid @RequestBody ProductDtos.Update input) {
        return service.update(id, input);
    }
    @DeleteMapping("/{id}")
    @Operation(summary = "Excluir product sem vinculos com pedidos")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
