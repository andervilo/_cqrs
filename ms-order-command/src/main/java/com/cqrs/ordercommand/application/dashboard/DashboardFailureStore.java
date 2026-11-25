package com.cqrs.ordercommand.application.dashboard;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DashboardFailureStore {
    private final JdbcTemplate jdbc; private final DashboardProperties properties; private final MeterRegistry metrics;
    public DashboardFailureStore(JdbcTemplate jdbc,DashboardProperties properties,MeterRegistry metrics) {
        this.jdbc=jdbc;this.properties=properties;this.metrics=metrics;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void failed(DashboardRefreshWorker.CalculationFailure error) {
        var attempts=jdbc.queryForList("""
                SELECT attempt_count FROM order_command.dashboard_refresh_control
                WHERE dashboard_id=? AND completed_generation<? FOR UPDATE
                """,Integer.class,error.id(),error.generation());
        if(attempts.isEmpty()) return; // A concurrent worker already completed this generation.
        int next=attempts.getFirst()+1;
        Throwable root=error;while(root.getCause()!=null)root=root.getCause();
        jdbc.update("""
                UPDATE order_command.dashboard_refresh_control SET attempt_count=?,last_error=?,
                  next_attempt_at=CASE WHEN ? THEN NULL ELSE clock_timestamp()+(? * interval '1 millisecond') END
                WHERE dashboard_id=?
                """,next,root.getClass().getSimpleName(),next>=properties.maxAttempts(),properties.retryDelay(next),error.id());
        metrics.counter("dashboard.refresh.failed").increment();
        if(next>=properties.maxAttempts())metrics.counter("dashboard.refresh.exhausted").increment();
    }
}
