package com.cqrs.orderquery;

import com.cqrs.orderquery.application.dashboard.*;
import com.cqrs.orderquery.application.inbox.*;
import com.cqrs.orderquery.domain.*;
import com.cqrs.orderquery.infrastructure.messaging.ProjectionEventDecoder;
import com.cqrs.orderquery.infrastructure.persistence.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Import(DashboardPersistenceTests.DatabaseConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"query.projections.enabled=false","query.dashboard.stale-seconds=120"})
class DashboardPersistenceTests {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class DatabaseConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.boot.testcontainers.service.connection.ServiceConnection
        org.testcontainers.mysql.MySQLContainer dashboardDatabase(){
            return new org.testcontainers.mysql.MySQLContainer("mysql:8.4")
                    .withDatabaseName("cqrs").withUsername("cqrs").withPassword("mysql_dev")
                    .withUrlParam("sslMode","DISABLED").withUrlParam("allowPublicKeyRetrieval","true");
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ProjectionEventDecoder decoder;
    @Autowired ProjectionInboxProcessor processor;
    @MockitoSpyBean SalesDashboardProjectionHandler dashboardHandler;
    @LocalServerPort int port;
    @BeforeEach void clear(){jdbc.update("DELETE FROM sales_dashboard_projection");jdbc.update("DELETE FROM inbox_event");jdbc.update("DELETE FROM projection_state");}
    DecodedProjectionEvent event(long version,ProjectionOperation operation){return decoder.decode(ProjectionFixtures.event(
            ProjectionEventType.SALES_DASHBOARD,DashboardPeriod.TODAY.id(),version,UUID.randomUUID(),operation,
            operation==ProjectionOperation.DELETE?"{}":DashboardContractTests.fixture("dashboard-today")));}
    @Test void appliesWholeSnapshotAndHandlesReplayStaleEventsAndPersistentTombstones(){
        var initial=event(2,ProjectionOperation.UPSERT);
        assertThat(processor.process(initial)).isEqualTo(ProjectionInboxTransaction.Outcome.APPLIED);
        assertThat(processor.process(initial)).isEqualTo(ProjectionInboxTransaction.Outcome.DUPLICATE);
        assertThat(processor.process(event(1,ProjectionOperation.UPSERT))).isEqualTo(ProjectionInboxTransaction.Outcome.IGNORED_STALE);
        assertThat(jdbc.queryForObject("SELECT projection_version FROM sales_dashboard_projection",Long.class)).isEqualTo(2);
        processor.process(event(3,ProjectionOperation.DELETE));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_dashboard_projection",Long.class)).isZero();
        processor.process(initial);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_dashboard_projection",Long.class)).isZero();
        processor.process(event(4,ProjectionOperation.UPSERT));
        assertThat(jdbc.queryForObject("SELECT projection_version FROM sales_dashboard_projection",Long.class)).isEqualTo(4);
    }
    @Test void rollsBackSnapshotInboxAndStateTogetherAfterWriteFailure(){
        doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("injected failure after snapshot");})
                .when(dashboardHandler).upsert(any(),anyLong());
        var e=event(1,ProjectionOperation.UPSERT);
        assertThatThrownBy(()->processor.process(e)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_dashboard_projection",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_event",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_state",Long.class)).isZero();
        doCallRealMethod().when(dashboardHandler).upsert(any(),anyLong());
        assertThat(processor.process(e)).isEqualTo(ProjectionInboxTransaction.Outcome.APPLIED);
    }
    @Test void concurrentRevisionsConvergeToHighestSnapshot() throws Exception {
        var a=event(1,ProjectionOperation.UPSERT);var b=event(2,ProjectionOperation.UPSERT);
        try(var pool=Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->processor.process(a));var two=pool.submit(()->processor.process(b));
            one.get(30,TimeUnit.SECONDS);two.get(30,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT projection_version FROM sales_dashboard_projection",Long.class)).isEqualTo(2);
    }
    @Test void httpDistinguishesInitializingReadyStaleAndRejectsUnsupportedInputs() throws Exception {
        var http=HttpClient.newHttpClient();String url="http://localhost:"+port+"/api/queries/dashboard/sales";
        var initial=http.send(HttpRequest.newBuilder(URI.create(url+"?period=TODAY")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(initial.statusCode()).isEqualTo(503);assertThat(initial.headers().firstValue("Retry-After")).contains("5");
        assertThat(json.readTree(initial.body()).path("code").asText()).isEqualTo("DASHBOARD_INITIALIZING");
        for(String suffix:List.of("?period=UNKNOWN","?from=2026-11-23T00:00:00Z","?period=TODAY&period=LAST_7_DAYS"))
            assertThat(http.send(HttpRequest.newBuilder(URI.create(url+suffix)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        processor.process(event(1,ProjectionOperation.UPSERT));
        var snapshot=json.readValue(DashboardContractTests.fixture("dashboard-today"),DashboardSnapshot.class);
        var fresh=new SalesDashboardQueryService(jdbc,json,Clock.fixed(snapshot.sourceObservedAt().plusSeconds(30),ZoneOffset.UTC),120).get(DashboardPeriod.TODAY);
        assertThat(fresh.availability().status()).isEqualTo("READY");
        assertThat(fresh.dashboard().orders().orderCount()).isEqualTo(100);
        var stale=new SalesDashboardQueryService(jdbc,json,Clock.fixed(snapshot.sourceObservedAt().plusSeconds(121),ZoneOffset.UTC),120).get(DashboardPeriod.TODAY);
        assertThat(stale.availability().status()).isEqualTo("STALE");
        assertThat(stale.dashboard().sourceObservedAt()).isEqualTo(snapshot.sourceObservedAt());
        var served=http.send(HttpRequest.newBuilder(URI.create(url+"?period=TODAY")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(json.readTree(served.body()).path("dashboard").path("summary").path("orderCount").path("value").asLong()).isEqualTo(100);
    }
}
