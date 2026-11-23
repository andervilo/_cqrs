package com.cqrs.ordercommand.application.projection;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.cqrs.ordercommand.application.projection.ProjectionSnapshots.*;

@Component
public class CustomerProjectionBuilder {
    private final JdbcTemplate jdbc;
    public CustomerProjectionBuilder(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Views build(UUID id) {
        return jdbc.queryForObject("""
                SELECT c.*, (SELECT count(*) FROM order_command.orders o WHERE o.customer_id=c.id) AS order_count,
                  (SELECT max(created_at) FROM order_command.orders o WHERE o.customer_id=c.id) AS last_order_at,
                  (SELECT coalesce(sum(total_amount),0) FROM order_command.orders o
                    WHERE o.customer_id=c.id AND status IN ('PAID','SHIPPED','DELIVERED')) AS lifetime_value
                FROM order_command.customer c WHERE c.id=?
                """, (rs,n) -> {
            var last = rs.getTimestamp("last_order_at");
            return new Views(new CustomerSummary(id, rs.getString("name"), rs.getString("email"),
                    rs.getLong("order_count"), last == null ? null : last.toInstant()),
                new CustomerDetail(id, rs.getString("name"), rs.getString("email"), rs.getString("phone"),
                    List.of(), rs.getTimestamp("created_at").toInstant(), rs.getLong("order_count"),
                    rs.getBigDecimal("lifetime_value").setScale(2)));
        }, id);
    }
    public record Views(CustomerSummary summary, CustomerDetail detail) {}
}
