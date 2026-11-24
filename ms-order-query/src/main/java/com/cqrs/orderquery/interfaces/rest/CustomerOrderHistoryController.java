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
@RequestMapping("/api/queries/customers/{customerId}/orders")
@Tag(name="CustomerOrderHistory")
public class CustomerOrderHistoryController {
    private final CustomerOrderHistoryQueryService service;
    public CustomerOrderHistoryController(CustomerOrderHistoryQueryService service) { this.service=service; }
    @GetMapping
    public PageResponse<CustomerOrderHistoryResponse> list(@PathVariable UUID customerId,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="createdAt,desc") String sort) {
        return service.list(customerId,page,size,sort);
    }
}
