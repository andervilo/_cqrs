package com.cqrs.ordercommand.application.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

public final class CustomerDtos {
    private CustomerDtos() {}
    @Schema(name = "CustomerCreate")
    public record Create(@NotBlank @Size(max = 160) String name,
                         @NotBlank @Email @Size(max = 254) String email,
                         @Size(max = 24) String phone) {}
    @Schema(name = "CustomerUpdate")
    public record Update(@NotBlank @Size(max = 160) String name,
                         @NotBlank @Email @Size(max = 254) String email,
                         @Size(max = 24) String phone, @NotNull Boolean active) {}
    @Schema(name = "CustomerResponse")
    public record Response(UUID id, String name, String email, String phone, boolean active,
                           Instant createdAt, Instant updatedAt, Long version) {}
}
