package com.cqrs.ordercommand;

import com.cqrs.ordercommand.application.dashboard.*;
import com.cqrs.ordercommand.application.relay.OutboxMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@Import(DashboardEmissionTests.TimeConfiguration.class)
@SpringBootTest(properties={"outbox.relay.enabled=false","dashboard.enabled=true","dashboard.debounce-ms=0"})
class DashboardEmissionTests {
    private static final List<String> WINDOW_IDS = List.of(
            "8b99f328-cb8c-4e22-8aa6-c04fe81cb001","8b99f328-cb8c-4e22-8aa6-c04fe81cb002",
            "8b99f328-cb8c-4e22-8aa6-c04fe81cb003","8b99f328-cb8c-4e22-8aa6-c04fe81cb004",
            "8b99f328-cb8c-4e22-8aa6-c04fe81cb005");

    @TestConfiguration(proxyBeanMethods=false)
    static class TimeConfiguration {
        @Bean @Primary Clock testClock(){return Clock.fixed(Instant.parse("2026-11-23T14:00:00Z"),ZoneOffset.UTC);}
        @Bean @org.springframework.boot.testcontainers.service.connection.ServiceConnection
        org.testcontainers.postgresql.PostgreSQLContainer emissionDatabase(){
            return new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17");
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DashboardRefreshWorker worker;
    @Autowired DashboardRefreshDispatcher dispatcher;
    @Autowired DashboardRefreshRequests requests;
    @Autowired com.cqrs.ordercommand.infrastructure.persistence.OutboxRelayStore relay;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;

    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE order_command.dashboard_refresh_request,order_command.order_outbox,order_command.projection_revision CASCADE");
        jdbc.update("UPDATE order_command.dashboard_refresh_control SET requested_generation=1,completed_generation=0,"
                + "next_attempt_at=clock_timestamp(),pending_since=clock_timestamp(),attempt_count=0,last_error=NULL,last_source_at=NULL");
    }

    @Test void emitsOnePendingSalesDashboardRowPerWindowWithOrderIdNullAndParsableSnapshot() {
        for(int i=0;i<5;i++) assertThat(worker.processOne()).isTrue();
        assertThat(worker.processOne()).isFalse();
        for(var id:WINDOW_IDS) {
            var row=jdbc.queryForMap("SELECT event_type, order_id, projection_key, projection_version, schema_version, operation, status FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD' AND projection_key=?",id);
            assertThat(row).containsEntry("event_type","SALES_DASHBOARD").containsEntry("operation","UPSERT").containsEntry("status","PENDING");
            assertThat(row.get("order_id")).isNull();
            assertThat(row.get("projection_key")).isEqualTo(id);
            assertThat((long)row.get("projection_version")).isEqualTo(1);
            assertThat((int)row.get("schema_version")).isEqualTo(1);
            var payload=jdbc.queryForObject("SELECT payload FROM order_command.order_outbox WHERE projection_key=?",String.class,id);
            var snapshot=json.readValue(payload,DashboardSnapshot.class);
            assertThat(snapshot.dashboardId()).isEqualTo(UUID.fromString(id));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD'",Integer.class)).isEqualTo(5);
    }

    @Test void startupTimerRequestFlowsThroughDispatchToAllFiveWindows() {
        requests.timer();
        assertThat(dispatcher.dispatch()).isEqualTo(1);
        for(int i=0;i<5;i++) assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.dashboard_refresh_request WHERE processed_at IS NOT NULL",Integer.class)).isEqualTo(1);
    }

    @Test void relayCanSelectDashboardEvents() {
        for(int i=0;i<5;i++) assertThat(worker.processOne()).isTrue();
        Optional<OutboxMessage> selected=new TransactionTemplate(transactions).execute(s->relay.lockNext(10));
        assertThat(selected.isPresent()).isTrue();
    }
}
