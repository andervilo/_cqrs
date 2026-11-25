package com.cqrs.orderquery.infrastructure.persistence;

import com.cqrs.orderquery.application.dashboard.DashboardSnapshot;
import com.cqrs.orderquery.application.inbox.*;
import com.cqrs.orderquery.domain.ProjectionEventType;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SalesDashboardProjectionHandler implements ProjectionHandler {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public SalesDashboardProjectionHandler(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public ProjectionEventType type(){return ProjectionEventType.SALES_DASHBOARD;}
    public Class<DashboardSnapshot> payloadType(){return DashboardSnapshot.class;}
    public void upsert(ProjectionSnapshot snapshot,long version){
        var s=(DashboardSnapshot)snapshot;
        jdbc.update("""
                INSERT INTO sales_dashboard_projection
                  (dashboard_id,period_code,payload,source_observed_at,computed_at,projection_version,projected_at)
                VALUES (?,?,CAST(? AS JSON),?,?,?,UTC_TIMESTAMP(6)) AS incoming
                ON DUPLICATE KEY UPDATE payload=incoming.payload,source_observed_at=incoming.source_observed_at,
                  computed_at=incoming.computed_at,projection_version=incoming.projection_version,projected_at=incoming.projected_at
                """,UuidBytes.from(s.dashboardId()),s.periodCode().name(),json.writeValueAsString(s),
                LocalDateTime.ofInstant(s.sourceObservedAt(),ZoneOffset.UTC),LocalDateTime.ofInstant(s.computedAt(),ZoneOffset.UTC),version);
    }
    public void delete(UUID key){jdbc.update("DELETE FROM sales_dashboard_projection WHERE dashboard_id=?",UuidBytes.from(key));}
}
