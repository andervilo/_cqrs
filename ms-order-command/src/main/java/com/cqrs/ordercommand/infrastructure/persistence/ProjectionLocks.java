package com.cqrs.ordercommand.infrastructure.persistence;

import java.util.UUID;
import com.cqrs.ordercommand.application.exception.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Lock dependency roots before loading JPA entities; customer always precedes order writes. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ProjectionLocks {
    private final JdbcTemplate jdbc;
    public ProjectionLocks(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean customerIfPresent(UUID id) {
        return !jdbc.queryForList("SELECT id FROM order_command.customer WHERE id=? FOR UPDATE", UUID.class, id).isEmpty();
    }
    public boolean productIfPresent(UUID id) {
        return !jdbc.queryForList("SELECT id FROM order_command.product WHERE id=? FOR UPDATE", UUID.class, id).isEmpty();
    }
    public void customer(UUID id) {
        if (!customerIfPresent(id)) throw new NotFoundException("Customer");
    }
    public void product(UUID id) {
        if (!productIfPresent(id)) throw new NotFoundException("Product");
    }
    public void orderCustomer(UUID orderId) {
        var ids = jdbc.queryForList("SELECT customer_id FROM order_command.orders WHERE id=?", UUID.class, orderId);
        if (ids.isEmpty()) throw new NotFoundException("Order");
        customer(ids.getFirst());
    }
}
