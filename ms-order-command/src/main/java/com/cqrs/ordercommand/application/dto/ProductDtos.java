package com.cqrs.ordercommand.application.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

public final class ProductDtos {
    private ProductDtos() {}
    @Schema(name = "ProductCreate")
    public record Create(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String sku,
                         @NotBlank @Size(max = 160) String name,
                         @Size(max = 2000) String description, @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2) BigDecimal unitPrice) {}
    @Schema(name = "ProductUpdate")
    public record Update(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String sku,
                         @NotBlank @Size(max = 160) String name,
                         @Size(max = 2000) String description, @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2) BigDecimal unitPrice, @NotNull Boolean active) {}
    @Schema(name = "ProductResponse")
    public record Response(UUID id, String sku, String name, String description, BigDecimal unitPrice,
                           boolean active, Instant createdAt, Instant updatedAt, Long version) {}
}
