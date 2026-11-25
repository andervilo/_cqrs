package com.cqrs.ordercommand.interfaces.rest;

import com.cqrs.ordercommand.application.projection.ProjectionRebuildService;
import com.cqrs.ordercommand.application.projection.ProjectionRebuildService.*;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cqrs.ordercommand.application.dashboard.DashboardRefreshRequests;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/v1/projections")
@Tag(name = "Projection maintenance")
public class ProjectionRebuildController {
    private final ProjectionRebuildService service;
    private final DashboardRefreshRequests dashboard;
    public ProjectionRebuildController(ProjectionRebuildService service,DashboardRefreshRequests dashboard) { this.service=service;this.dashboard=dashboard; }
    @PostMapping("/rebuild")
    @Operation(summary = "Reconstruir página de CUSTOMER/PRODUCT (200) ou agendar dashboard (202)")
    public ResponseEntity<?> rebuild(@RequestParam @Schema(allowableValues={"CUSTOMER","PRODUCT","DASHBOARD"}) String resourceType,
            @RequestParam(required=false) UUID after,
            @RequestParam(required=false) @Min(1) @Max(100) Integer size) {
        if(resourceType.equals("DASHBOARD")) {
            if(after!=null||size!=null) throw new IllegalArgumentException("DASHBOARD does not accept after or size");
            return ResponseEntity.accepted().body(dashboard.rebuild());
        }
        return ResponseEntity.ok(service.rebuild(ResourceType.valueOf(resourceType),after,size==null?20:size));
    }
}
