package com.cqrs.orderquery.interfaces.rest;

import com.cqrs.orderquery.application.dashboard.*;
import com.cqrs.orderquery.application.exception.InvalidQueryException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/queries/dashboard/sales")
@Tag(name="Sales Dashboard")
public class SalesDashboardController {
    private final SalesDashboardQueryService service;
    public SalesDashboardController(SalesDashboardQueryService service){this.service=service;}
    @GetMapping
    @Operation(summary="Dashboard comercial agregado; scope cqrs.dashboard.read no gateway")
    public SalesDashboardQueryService.Response get(@RequestParam(defaultValue="LAST_30_DAYS") DashboardPeriod period,
            @RequestParam MultiValueMap<String,String> parameters){
        if(!Set.of("period").containsAll(parameters.keySet()) || parameters.values().stream().anyMatch(v->v.size()!=1))
            throw new InvalidQueryException("Dashboard accepts only one period parameter; from/to are not supported");
        return service.get(period);
    }
    @ExceptionHandler(SalesDashboardQueryService.Initializing.class)
    public ResponseEntity<ProblemDetail> initializing(){
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"O dashboard ainda está sendo preparado.");
        problem.setProperty("code","DASHBOARD_INITIALIZING");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER,"5").body(problem);
    }
}
