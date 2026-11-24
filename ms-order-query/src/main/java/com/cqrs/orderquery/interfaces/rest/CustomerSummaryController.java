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
@RequestMapping("/api/queries/customers")
@Tag(name="CustomerSummary")
public class CustomerSummaryController {
    private final CustomerSummaryQueryService service;
    public CustomerSummaryController(CustomerSummaryQueryService service) { this.service=service; }
    @GetMapping
    public PageResponse<CustomerSummaryResponse> list(@RequestParam(required=false) @Size(max=160) String search,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="name,asc") String sort) {
        return service.list(search,page,size,sort);
    }
}
