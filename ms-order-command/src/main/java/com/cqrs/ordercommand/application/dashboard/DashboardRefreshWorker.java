package com.cqrs.ordercommand.application.dashboard;

import com.cqrs.ordercommand.application.projection.ProjectionTarget;
import com.cqrs.ordercommand.application.service.OrderOutboxService;
import com.cqrs.ordercommand.domain.model.*;
import com.cqrs.ordercommand.infrastructure.persistence.ProjectionRevisionStore;
import com.cqrs.ordercommand.infrastructure.persistence.entity.OrderOutboxEntity;
import io.micrometer.core.instrument.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
public class DashboardRefreshWorker {
    private final JdbcTemplate jdbc;
    private final DashboardProjectionBuilder builder;
    private final ProjectionRevisionStore revisions;
    private final OrderOutboxService outbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final DashboardProperties properties;
    private final MeterRegistry metrics;
    public DashboardRefreshWorker(JdbcTemplate jdbc,DashboardProjectionBuilder builder,ProjectionRevisionStore revisions,
            OrderOutboxService outbox,ObjectMapper json,Clock clock,DashboardProperties properties,MeterRegistry metrics) {
        this.jdbc=jdbc;this.builder=builder;this.revisions=revisions;this.outbox=outbox;this.json=json;
        this.clock=clock;this.properties=properties;this.metrics=metrics;
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ,timeout=120)
    public boolean processOne() {
        var selected=jdbc.query("""
                SELECT dashboard_id,period_code,requested_generation,last_source_at FROM order_command.dashboard_refresh_control
                WHERE requested_generation>completed_generation AND next_attempt_at<=clock_timestamp() AND attempt_count<?
                ORDER BY next_attempt_at,dashboard_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,(rs,n)->new Pending(rs.getObject("dashboard_id",UUID.class),DashboardPeriod.valueOf(rs.getString("period_code")),
                rs.getLong("requested_generation"),rs.getTimestamp("last_source_at")==null?null:rs.getTimestamp("last_source_at").toInstant()),properties.maxAttempts());
        if(selected.isEmpty()) return false;
        var pending=selected.getFirst();
        var timer=io.micrometer.core.instrument.Timer.start(metrics);
        try {
            jdbc.execute("SET LOCAL statement_timeout = '"+properties.statementTimeoutMs()+"ms'");
            Instant reference=clock.instant().truncatedTo(ChronoUnit.MICROS);
            if(pending.lastSource()!=null && reference.isBefore(pending.lastSource()))
                throw new IllegalStateException("Dashboard clock moved backwards");
            if(!pending.period().id().equals(pending.id())) throw new IllegalStateException("Dashboard identity catalog mismatch");
            var snapshot=builder.build(pending.period(),reference);
            String payload=json.writeValueAsString(snapshot);
            if(payload.getBytes(StandardCharsets.UTF_8).length>512*1024)
                throw new IllegalStateException("Dashboard snapshot exceeds 512 KiB");
            var target=new ProjectionTarget(ProjectionEventType.SALES_DASHBOARD,pending.id());
            long version=revisions.advance(Set.of(target)).get(target);
            outbox.append(List.of(new OrderOutboxEntity(null,ProjectionEventType.SALES_DASHBOARD,ProjectionOperation.UPSERT,
                    pending.id().toString(),version,payload)));
            jdbc.update("""
                    UPDATE order_command.dashboard_refresh_control SET completed_generation=?,next_attempt_at=NULL,
                      pending_since=NULL,attempt_count=0,last_error=NULL,last_source_at=? WHERE dashboard_id=?
                    """,pending.generation(),Timestamp.from(reference),pending.id());
            return true;
        } catch(RuntimeException error) { throw new CalculationFailure(pending.id(),pending.generation(),error); }
        finally { timer.stop(metrics.timer("dashboard.refresh.calculation","period",pending.period().name())); }
    }
    private record Pending(UUID id,DashboardPeriod period,long generation,Instant lastSource) {}
    public static class CalculationFailure extends RuntimeException {
        private final UUID id; private final long generation;
        public CalculationFailure(UUID id,long generation,RuntimeException cause) {
            super("Dashboard calculation failed",cause);this.id=id;this.generation=generation;
        }
        public UUID id(){return id;} public long generation(){return generation;}
    }
}
