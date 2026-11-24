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
@Tag(name="ProductCatalog")
public class ProductCatalogController {
    private final ProductCatalogQueryService service;
    public ProductCatalogController(ProductCatalogQueryService service) { this.service=service; }
    @GetMapping
    public PageResponse<ProductCatalogResponse> list(@RequestParam(required=false) @Size(max=160) String search,
            @RequestParam(required=false) @Size(max=160) String category,
            @RequestParam(required=false) Boolean available,
            @RequestParam(required=false) @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal minPrice,
            @RequestParam(required=false) @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal maxPrice,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="name,asc") String sort) {
        return service.list(search,category,available,minPrice,maxPrice,page,size,sort);
    }
}
