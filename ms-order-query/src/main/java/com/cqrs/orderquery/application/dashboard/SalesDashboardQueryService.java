package com.cqrs.orderquery.application.dashboard;

import com.cqrs.orderquery.application.dto.DashboardView;
import com.cqrs.orderquery.infrastructure.persistence.UuidBytes;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SalesDashboardQueryService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final Clock clock;private final long staleSeconds;
    public SalesDashboardQueryService(JdbcTemplate jdbc,ObjectMapper json,Clock clock,
            @Value("${query.dashboard.stale-seconds:120}") long staleSeconds){
        if(staleSeconds<1)throw new IllegalArgumentException("Dashboard stale seconds must be positive");
        this.jdbc=jdbc;this.json=json;this.clock=clock;this.staleSeconds=staleSeconds;
    }
    @Transactional(readOnly=true)
    public Response get(DashboardPeriod period) {
        var rows=jdbc.query("SELECT payload,projected_at FROM sales_dashboard_projection WHERE dashboard_id=?",(rs,n)->
                new Row(json.readValue(rs.getString("payload"),DashboardSnapshot.class),rs.getTimestamp("projected_at").toInstant()),UuidBytes.from(period.id()));
        if(rows.isEmpty())throw new Initializing();
        var row=rows.getFirst();Instant served=clock.instant();
        long age=Math.max(0,Duration.between(row.snapshot().sourceObservedAt(),served).getSeconds());
        boolean calendarChanged=!period.window(served).period().from().equals(row.snapshot().period().from());
        boolean stale=age>staleSeconds||calendarChanged;
        return new Response(json.convertValue(row.snapshot(),DashboardView.class),
                new Availability(stale?"STALE":"READY",age,row.projectedAt(),stale?
                        "O dashboard está defasado. Considere a referência do cálculo antes de decidir.":null),served);
    }
    private record Row(DashboardSnapshot snapshot,Instant projectedAt){}
    public record Availability(String status,long ageSeconds,Instant projectedAt,String warning){}
    public record Response(DashboardView dashboard,Availability availability,Instant servedAt){}
    public static class Initializing extends RuntimeException {}
}
