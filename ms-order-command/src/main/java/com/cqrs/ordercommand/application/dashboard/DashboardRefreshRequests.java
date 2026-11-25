package com.cqrs.ordercommand.application.dashboard;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DashboardRefreshRequests {
    private final JdbcTemplate jdbc;
    public DashboardRefreshRequests(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void orderChanged() { insert("ORDER_CHANGE"); }

    @Transactional
    public Accepted rebuild() {
        return new Accepted(insert("REBUILD"),Arrays.stream(DashboardPeriod.values()).map(Enum::name).toList());
    }
    @Transactional
    public void timer() { insert("TIMER"); }

    private UUID insert(String reason) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO order_command.dashboard_refresh_request(id,reason) VALUES (?,?)",id,reason);
        return id;
    }
    public record Accepted(UUID requestId,List<String> periodCodes) {}
}
