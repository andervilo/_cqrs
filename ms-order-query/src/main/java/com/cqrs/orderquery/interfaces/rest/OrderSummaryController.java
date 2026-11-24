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
@RequestMapping("/api/queries/orders")
@Tag(name="OrderSummary")
public class OrderSummaryController {
    private final OrderSummaryQueryService service;
    public OrderSummaryController(OrderSummaryQueryService service) { this.service=service; }
    @GetMapping
    public PageResponse<OrderSummaryResponse> list(@RequestParam(required=false) UUID customerId,
            @RequestParam(required=false) @Pattern(regexp="CREATED|CANCELLED") String status,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="createdAt,desc") String sort) {
        return service.list(customerId,status,from,to,page,size,sort);
    }
}
