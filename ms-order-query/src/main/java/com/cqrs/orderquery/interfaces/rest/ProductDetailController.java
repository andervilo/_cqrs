package com.cqrs.orderquery.interfaces.rest;

import com.cqrs.orderquery.application.query.*;
import com.cqrs.orderquery.application.dto.*;
import com.cqrs.orderquery.application.dto.ProjectionResponses.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/queries/products")
@Tag(name="ProductDetail")
public class ProductDetailController {
    private final ProductDetailQueryService service;
    public ProductDetailController(ProductDetailQueryService service) { this.service=service; }
    @GetMapping("/{productId}")
    public ProductDetailResponse get(@PathVariable UUID productId) { return service.get(productId); }
}
