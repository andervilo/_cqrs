package com.cqrs.ordercommand.application.projection;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Keyset pages include revision keys for deleted resources, preserving tombstones on rebuild. */
@Service
public class ProjectionRebuildService {
    public enum ResourceType { CUSTOMER, PRODUCT }
    public record Result(ResourceType resourceType, int processed, UUID nextCursor, boolean hasMore) {}
    private final JdbcTemplate jdbc;
    private final ProjectionRebuildWorker worker;
    public ProjectionRebuildService(JdbcTemplate jdbc, ProjectionRebuildWorker worker) {
        this.jdbc=jdbc; this.worker=worker;
    }
    public Result rebuild(ResourceType type, UUID after, int size) {
        if (size < 1 || size > 100) throw new IllegalArgumentException("Page size must be between 1 and 100");
        String sources = switch(type) {
            case CUSTOMER -> "SELECT id FROM order_command.customer UNION SELECT projection_key::uuid AS id FROM order_command.projection_revision WHERE event_type='CUSTOMER_SUMMARY'";
            case PRODUCT -> "SELECT id FROM order_command.product UNION SELECT projection_key::uuid AS id FROM order_command.projection_revision WHERE event_type='PRODUCT_CATALOG'";
        };
        var ids = after == null
                ? jdbc.queryForList("SELECT id FROM ("+sources+") resources ORDER BY id LIMIT ?", UUID.class,size+1)
                : jdbc.queryForList("SELECT id FROM ("+sources+") resources WHERE id>? ORDER BY id LIMIT ?", UUID.class,after,size+1);
        boolean more = ids.size()>size;
        var page=ids.subList(0, Math.min(ids.size(),size));
        for (var id : page) {
            switch(type) {
                case CUSTOMER -> worker.customer(id);
                case PRODUCT -> worker.product(id);
            }
        }
        return new Result(type,page.size(),page.isEmpty()?after:page.getLast(),more);
    }
}
