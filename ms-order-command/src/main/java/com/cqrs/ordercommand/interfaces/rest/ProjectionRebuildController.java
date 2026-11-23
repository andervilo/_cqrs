package com.cqrs.ordercommand.interfaces.rest;

import com.cqrs.ordercommand.application.projection.ProjectionRebuildService;
import com.cqrs.ordercommand.application.projection.ProjectionRebuildService.*;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/projections")
@Tag(name = "Projection maintenance")
public class ProjectionRebuildController {
    private final ProjectionRebuildService service;
    public ProjectionRebuildController(ProjectionRebuildService service) { this.service=service; }
    @PostMapping("/rebuild")
    @Operation(summary = "Gerar um lote de snapshots atuais na outbox, sem resetar revisoes")
    public Result rebuild(@RequestParam ResourceType resourceType, @RequestParam(required=false) UUID after,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.rebuild(resourceType,after,size);
    }
}
