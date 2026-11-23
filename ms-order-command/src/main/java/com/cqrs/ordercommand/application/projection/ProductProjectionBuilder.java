package com.cqrs.ordercommand.application.projection;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.cqrs.ordercommand.application.projection.ProjectionSnapshots.*;

@Component
public class ProductProjectionBuilder {
    private final JdbcTemplate jdbc;
    public ProductProjectionBuilder(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Views build(UUID id) {
        return jdbc.queryForObject("SELECT * FROM order_command.product WHERE id=?", (rs,n) ->
            new Views(new ProductCatalog(id, rs.getString("name"), rs.getBigDecimal("unit_price"), "BRL", null,
                    rs.getBoolean("active"), null),
                new ProductDetail(id, rs.getString("name"), rs.getString("description"), rs.getBigDecimal("unit_price"),
                    "BRL", List.of(), rs.getBoolean("active"), Map.of())), id);
    }
    public record Views(ProductCatalog catalog, ProductDetail detail) {}
}
