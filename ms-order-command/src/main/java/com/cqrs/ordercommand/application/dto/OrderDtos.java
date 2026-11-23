package com.cqrs.ordercommand.application.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

public final class OrderDtos {
    private OrderDtos() {}
    @Schema(name = "OrderItemRequest")
    public record ItemRequest(@NotNull UUID productId, @NotNull @Min(1) @Max(10000) Integer quantity) {}
    @Schema(name = "OrderCreate")
    public record Create(@NotNull UUID customerId,
                         @NotEmpty @Size(max = 1000) List<@NotNull @Valid ItemRequest> items,
                         @Size(max = 1000) String notes) {}
    @Schema(name = "OrderReplaceItems")
    public record ReplaceItems(@NotEmpty @Size(max = 1000) List<@NotNull @Valid ItemRequest> items) {}
    @Schema(name = "OrderItemResponse")
    public record ItemResponse(UUID id, UUID productId, String productSku, String productName,
                               int quantity, BigDecimal unitPrice, BigDecimal subtotal) {}
    @Schema(name = "OrderResponse")
    public record Response(UUID id, UUID customerId, OrderStatus status, BigDecimal totalAmount, String notes,
                           List<ItemResponse> items, Instant createdAt, Instant updatedAt, Long version) {}
}
