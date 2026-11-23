package com.cqrs.ordercommand.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderTest {
    private Item item() {
        return new Item(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", "Produto", 3, new BigDecimal("12.30"));
    }

    @Test
    void calculatesTotalWithDecimalPrecisionAndPreservesItems() {
        var order = Order.create(UUID.randomUUID(), List.of(item()), null);
        assertThat(order.totalAmount()).isEqualByComparingTo("36.90");
        assertThatThrownBy(() -> order.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEmptyOrdersInvalidQuantitiesAndDuplicateProducts() {
        assertThatThrownBy(() -> Order.create(UUID.randomUUID(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        var item = item();
        assertThatThrownBy(() -> Order.create(UUID.randomUUID(), List.of(item, item), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Item(UUID.randomUUID(), UUID.randomUUID(), "SKU", "Produto", 0, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelledOrdersCannotChangeItems() {
        var order = Order.create(UUID.randomUUID(), List.of(item()), null).cancel();
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> order.replaceItems(List.of(item())))
                .isInstanceOf(IllegalStateException.class);
    }
}
