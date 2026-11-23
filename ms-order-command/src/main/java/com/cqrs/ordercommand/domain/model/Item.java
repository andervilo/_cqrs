package com.cqrs.ordercommand.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record Item(UUID id, UUID productId, String productSku, String productName,
                   int quantity, BigDecimal unitPrice) {
    public Item {
        Objects.requireNonNull(id);
        Objects.requireNonNull(productId);
        Objects.requireNonNull(productSku);
        Objects.requireNonNull(productName);
        if (quantity < 1 || quantity > 10000) {
            throw new IllegalArgumentException("Quantidade deve estar entre 1 e 10000.");
        }
        if (unitPrice == null || unitPrice.signum() <= 0
                || unitPrice.compareTo(new BigDecimal("999999999.99")) > 0) {
            throw new IllegalArgumentException("Preco deve ser positivo e no maximo 999999999.99.");
        }
        try {
            unitPrice = unitPrice.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Preco deve ter no maximo duas casas decimais.");
        }
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
