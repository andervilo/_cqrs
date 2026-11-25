package com.cqrs.ordercommand.application.dashboard;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardRefreshDispatcher {
    private final JdbcTemplate jdbc;
    private final DashboardProperties properties;
    public DashboardRefreshDispatcher(JdbcTemplate jdbc,DashboardProperties properties) { this.jdbc=jdbc;this.properties=properties; }

    @Transactional(timeout=15)
    public int dispatch() {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        var requests=jdbc.query("""
                SELECT id,reason FROM order_command.dashboard_refresh_request WHERE processed_at IS NULL
                ORDER BY requested_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                """,(rs,n)->new Request(rs.getObject("id",UUID.class),rs.getString("reason")),properties.dispatchBatchSize());
        if(requests.isEmpty()) return 0;
        boolean reset=requests.stream().anyMatch(r->r.reason().equals("REBUILD"));
        // Deterministic order; this dispatcher never takes a customer/order lock.
        for(var period:DashboardPeriod.values()) {
            jdbc.update("""
                    UPDATE order_command.dashboard_refresh_control SET requested_generation=requested_generation+1,
                      pending_since=coalesce(pending_since,clock_timestamp()),
                      next_attempt_at=CASE
                        WHEN ? THEN clock_timestamp()
                        WHEN attempt_count>=? THEN NULL
                        WHEN attempt_count>0 THEN next_attempt_at
                        WHEN requested_generation=completed_generation THEN clock_timestamp()+(? * interval '1 millisecond')
                        ELSE least(next_attempt_at,clock_timestamp()+(? * interval '1 millisecond')) END,
                      attempt_count=CASE WHEN ? THEN 0 ELSE attempt_count END,
                      last_error=CASE WHEN ? THEN NULL ELSE last_error END
                    WHERE dashboard_id=?
                    """,reset,properties.maxAttempts(),properties.debounceMs(),properties.debounceMs(),reset,reset,period.id());
        }
        for(var request:requests) jdbc.update("UPDATE order_command.dashboard_refresh_request SET processed_at=clock_timestamp() WHERE id=?",request.id());
        return requests.size();
    }
    private record Request(UUID id,String reason) {}
}
