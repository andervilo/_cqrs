package com.cqrs.ordercommand.application.projection;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.cqrs.ordercommand.application.projection.ProjectionSnapshots.*;

@Component
public class OrderProjectionBuilder {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final JdbcTemplate jdbc;
    public OrderProjectionBuilder(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Views build(UUID id) {
        var items = jdbc.query("SELECT * FROM order_command.item WHERE order_id=? ORDER BY id", (rs,n) ->
            new OrderItemInfo(rs.getObject("product_id",UUID.class), rs.getString("product_name"),
                rs.getInt("quantity"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("unit_price").multiply(BigDecimal.valueOf(rs.getInt("quantity")))), id);
        int count = items.stream().mapToInt(OrderItemInfo::quantity).sum();
        BigDecimal subtotal = items.stream().map(OrderItemInfo::subtotal).reduce(ZERO, BigDecimal::add);
        return jdbc.queryForObject("""
                SELECT o.*,c.name AS customer_name FROM order_command.orders o
                JOIN order_command.customer c ON c.id=o.customer_id WHERE o.id=?
                """, (rs,n) -> {
            var customerId = rs.getObject("customer_id",UUID.class);
            var created = rs.getTimestamp("created_at").toInstant();
            var total = rs.getBigDecimal("total_amount");
            var status = rs.getString("status");
            return new Views(new OrderSummary(id, customerId, rs.getString("customer_name"), created, total, "BRL", status, count),
                new OrderDetail(id, created, new CustomerInfo(customerId,rs.getString("customer_name")), List.copyOf(items),
                    subtotal, ZERO, ZERO, total, "BRL", status),
                new CustomerOrderHistory(id, customerId, created, status, total, "BRL", count,
                    items.isEmpty() ? null : items.getFirst().productName()));
        }, id);
    }
    public record Views(OrderSummary summary, OrderDetail detail, CustomerOrderHistory history) {}
}
