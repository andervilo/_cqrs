package com.cqrs.ordercommand.domain.model;

import java.math.BigDecimal;
import java.util.*;

public record Order(UUID id, UUID customerId, List<Item> items, OrderStatus status, String notes) {
    public Order {
        Objects.requireNonNull(id);
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(status);
        if (items == null || items.isEmpty() || items.size() > 1000) {
            throw new IllegalArgumentException("Pedido deve conter entre 1 e 1000 itens.");
        }
        items = List.copyOf(items);
        if (items.stream().map(Item::productId).distinct().count() != items.size()) {
            throw new IllegalArgumentException("Produto repetido: consolide a quantidade em um unico item.");
        }
        if (notes != null && notes.length() > 1000) {
            throw new IllegalArgumentException("Observacoes devem ter no maximo 1000 caracteres.");
        }
    }

    public static Order create(UUID customerId, List<Item> items, String notes) {
        return new Order(UUID.randomUUID(), customerId, items, OrderStatus.CREATED, notes);
    }

    public BigDecimal totalAmount() {
        return items.stream().map(Item::subtotal).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    public Order replaceItems(List<Item> replacement) {
        requireCreated();
        return new Order(id, customerId, replacement, status, notes);
    }

    public Order cancel() {
        requireCreated();
        return new Order(id, customerId, items, OrderStatus.CANCELLED, notes);
    }

    private void requireCreated() {
        if (status != OrderStatus.CREATED) {
            throw new OrderStateException("Pedido cancelado nao pode ser alterado.");
        }
    }
}
