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
@Tag(name="CustomerDetail")
public class CustomerDetailController {
    private final CustomerDetailQueryService service;
    public CustomerDetailController(CustomerDetailQueryService service) { this.service=service; }
    @GetMapping("/{customerId}")
    public CustomerDetailResponse get(@PathVariable UUID customerId) { return service.get(customerId); }
}
