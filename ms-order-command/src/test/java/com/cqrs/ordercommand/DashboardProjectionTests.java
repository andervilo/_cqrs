package com.cqrs.ordercommand;

import com.cqrs.ordercommand.application.dashboard.*;
import com.cqrs.ordercommand.application.dto.*;
import com.cqrs.ordercommand.application.service.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@Import(DashboardProjectionTests.TimeConfiguration.class)
@SpringBootTest(properties={"outbox.relay.enabled=false","dashboard.enabled=false","dashboard.debounce-ms=0"})
class DashboardProjectionTests {
    static final Instant NOW=Instant.parse("2026-11-23T14:00:00Z");
    @TestConfiguration(proxyBeanMethods=false)
    static class TimeConfiguration {
        @Bean @Primary Clock testClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean @org.springframework.boot.testcontainers.service.connection.ServiceConnection
        org.testcontainers.postgresql.PostgreSQLContainer dashboardDatabase(){
            return new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17");
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DashboardRefreshWorker worker;
    @Autowired DashboardRefreshDispatcher dispatcher;
    @Autowired DashboardRefreshRequests requests;
    @Autowired DashboardFailureStore failures;
    @Autowired OrderService orders;
    @Autowired CustomerService customers;
    @Autowired ProductService products;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @MockitoSpyBean DashboardProjectionBuilder builder;

    @BeforeEach void clear() {
        jdbc.execute("TRUNCATE order_command.dashboard_refresh_request,order_command.order_outbox,order_command.projection_revision,order_command.item,order_command.orders,order_command.customer,order_command.product CASCADE");
        jdbc.update("UPDATE order_command.dashboard_refresh_control SET requested_generation=1,completed_generation=0,next_attempt_at=clock_timestamp(),pending_since=clock_timestamp(),attempt_count=0,last_error=NULL,last_source_at=NULL");
    }
    DashboardSnapshot latest(DashboardPeriod period) {
        String payload=jdbc.queryForObject("SELECT payload::text FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD' AND projection_key=? ORDER BY projection_version DESC LIMIT 1",String.class,period.id().toString());
        return json.readValue(payload,DashboardSnapshot.class);
    }
    void calculateAll(){for(int i=0;i<5;i++)assertThat(worker.processOne()).isTrue();assertThat(worker.processOne()).isFalse();}

    @Test void buildsEmptySnapshotsWithHonestUnavailableMetricsAndAllFiveStableKeys() {
        calculateAll();
        var s=latest(DashboardPeriod.TODAY);
        com.cqrs.ordercommand.dashboard.DashboardSchema.export(s,json,"dashboard-empty");
        assertThat(s.orders().orderCount()).isZero();
        assertThat(s.summary().get("cancellationRate").value()).isNull();
        assertThat(s.summary().get("orderCount").comparisonStatus()).isEqualTo("NO_BASELINE");
        assertThat(s.unavailableMetrics()).allSatisfy(m->assertThat(m.value()).isNull());
        assertThat(s.openPortfolio().ageBuckets()).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD' AND order_id IS NULL",Integer.class)).isEqualTo(5);
    }

    @Test void calculatesComparisonsDriversDistinctBuyersRankingsAndSignalsInTheDatabase() {
        seed(100,10,NOW.minusSeconds(3600));
        seed(80,4,NOW.minus(Duration.ofDays(1)).minusSeconds(3600));
        calculateAll();
        var s=latest(DashboardPeriod.TODAY);
        com.cqrs.ordercommand.dashboard.DashboardSchema.export(s,json,"dashboard-today");
        assertThat(s.orders().orderCount()).isEqualTo(100);
        assertThat(s.orders().retainedOrderValue()).isEqualByComparingTo("45000.00");
        assertThat(s.previousOrders().retainedOrderValue()).isEqualByComparingTo("38000.00");
        assertThat(s.summary().get("orderCount").changePercent()).isEqualByComparingTo("25.00");
        assertThat(s.summary().get("cancellationRate").changePercentagePoints()).isEqualByComparingTo("5.00");
        assertThat(s.valueChangeDrivers().volumeEffect()).isEqualByComparingTo("7000.00");
        assertThat(s.valueChangeDrivers().averageValueEffect()).isEqualByComparingTo("0.00");
        assertThat(s.products().byRetainedValue()).hasSize(1);
        assertThat(s.products().byRetainedValue().getFirst().retainedValueShare()).isEqualByComparingTo("100.00");
        assertThat(s.insights()).extracting(DashboardSnapshot.Insight::code).contains("CANCELLATION_INCREASE","RETAINED_VALUE_GROWTH");
        assertThat(s.daily().stream().mapToLong(DashboardSnapshot.Day::orderCount).sum()).isEqualTo(100);
        assertThat(s.customers().activeBuyerCount()).isEqualTo(50);
    }

    @Test void invalidationIsTransactionalAndReplacementCancellationConvergeWithoutDuplicatingRequests() {
        var c=customers.create(new CustomerDtos.Create("Buyer","test@example.com",null));
        var p=products.create(new ProductDtos.Create("DASH-1","Keyboard",null,new BigDecimal("150.00")));
        var o=orders.create(new OrderDtos.Create(c.id(),List.of(new OrderDtos.ItemRequest(p.id(),2)),null));
        jdbc.update("UPDATE order_command.orders SET created_at=? WHERE id=?",Timestamp.from(NOW.minusSeconds(10)),o.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.dashboard_refresh_request",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{requests.orderChanged();throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.dashboard_refresh_request",Integer.class)).isEqualTo(1);
        dispatcher.dispatch();calculateAll();
        assertThat(latest(DashboardPeriod.TODAY).orders().retainedOrderValue()).isEqualByComparingTo("300");
        orders.replaceItems(o.id(),new OrderDtos.ReplaceItems(List.of(new OrderDtos.ItemRequest(p.id(),3))));
        dispatcher.dispatch();calculateAll();
        assertThat(latest(DashboardPeriod.TODAY).orders().retainedOrderValue()).isEqualByComparingTo("450");
        orders.cancel(o.id());dispatcher.dispatch();calculateAll();
        assertThat(latest(DashboardPeriod.TODAY).orders().orderCount()).isEqualTo(1);
        assertThat(latest(DashboardPeriod.TODAY).orders().cancelledOrderValue()).isEqualByComparingTo("450");
        assertThat(latest(DashboardPeriod.TODAY).products().byRetainedValue()).isEmpty();
    }

    @Test void concurrentWorkersPublishOneRevisionPerWindowAndRefreshRequestsAreNotLost() throws Exception {
        try(var pool=Executors.newFixedThreadPool(5)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<5;i++)futures.add(pool.submit(worker::processOne));
            for(var future:futures)assertThat(future.get(45,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD'",Integer.class)).isEqualTo(5);
        var accepted=requests.rebuild();assertThat(accepted.periodCodes()).hasSize(5);
        dispatcher.dispatch();calculateAll();
        assertThat(jdbc.queryForObject("SELECT min(projection_version) FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT max(projection_version) FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.dashboard_refresh_request WHERE processed_at IS NULL",Integer.class)).isZero();
    }

    @Test void calculationFailuresRollBackAndExhaustWithoutTimerBypassAndRebuildRecovers() {
        jdbc.update("UPDATE order_command.dashboard_refresh_control SET next_attempt_at=NULL WHERE period_code<>'TODAY'");
        doThrow(new IllegalStateException("simulated")).when(builder).build(any(),any());
        for(int i=0;i<3;i++) {
            jdbc.update("UPDATE order_command.dashboard_refresh_control SET next_attempt_at=clock_timestamp() WHERE period_code='TODAY'");
            var error=catchThrowableOfType(DashboardRefreshWorker.CalculationFailure.class,worker::processOne);
            failures.failed(error);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT completed_generation FROM order_command.dashboard_refresh_control WHERE period_code='TODAY'",Long.class)).isZero();
        }
        requests.timer();dispatcher.dispatch();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM order_command.dashboard_refresh_control WHERE period_code='TODAY'",Timestamp.class)).isNull();
        doCallRealMethod().when(builder).build(any(),any());
        requests.rebuild();dispatcher.dispatch();calculateAll();
        assertThat(latest(DashboardPeriod.TODAY).orders().orderCount()).isZero();
    }

    @Test void newInvalidationDuringCalculationRemainsPendingAndAgeCutoffsAreDisjoint() throws Exception {
        seed(1,0,NOW.minus(Duration.ofHours(48)));
        jdbc.update("UPDATE order_command.dashboard_refresh_control SET next_attempt_at=NULL WHERE period_code<>'TODAY'");
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();if(!resume.await(20,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout");return invocation.callRealMethod();})
                .when(builder).build(eq(DashboardPeriod.TODAY),any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var computing=pool.submit(worker::processOne);
            assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
            var request=requests.rebuild();resume.countDown();assertThat(computing.get(30,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT processed_at FROM order_command.dashboard_refresh_request WHERE id=?",Timestamp.class,request.requestId())).isNull();
        }finally{resume.countDown();}
        doCallRealMethod().when(builder).build(any(),any());
        dispatcher.dispatch();calculateAll();
        var portfolio=latest(DashboardPeriod.TODAY).openPortfolio();
        assertThat(portfolio.olderThanOrEqual48h().orderCount()).isEqualTo(1);
        assertThat(portfolio.ageBuckets().get(1).orderCount()).isZero();
        assertThat(portfolio.ageBuckets().get(2).orderCount()).isEqualTo(1);
    }

    private void seed(int count,int cancelled,Instant at) {
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            UUID product=UUID.randomUUID();
                jdbc.update("INSERT INTO order_command.product(id,sku,name,unit_price) VALUES (?,?,?,500)",product,product.toString().toUpperCase(Locale.ROOT),"Historical Product");
            var buyers=new ArrayList<UUID>();
            for(int i=0;i<Math.min(50,count);i++){
                UUID id=UUID.randomUUID();buyers.add(id);
                jdbc.update("INSERT INTO order_command.customer(id,name,email) VALUES (?,?,?)",id,"Buyer",id+"@example.com");
            }
            for(int i=0;i<count;i++){
                UUID id=UUID.randomUUID();
                jdbc.update("INSERT INTO order_command.orders(id,customer_id,status,total_amount,created_at) VALUES (?,?,?,?,?)",id,buyers.get(i%buyers.size()),i>=count-cancelled?"CANCELLED":"CREATED",new BigDecimal("500"),Timestamp.from(at));
                jdbc.update("INSERT INTO order_command.item(id,order_id,product_id,product_sku,product_name,quantity,unit_price) VALUES (?,?,?,?,?,1,500)",UUID.randomUUID(),id,product,product.toString(),"Historical Product");
            }
        });
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="dashboard.load",matches="true")
    void measuresFiveWindowsAgainstTenThousandOrdersWithoutLoadingFactsIntoJava() {
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            jdbc.update("""
                    INSERT INTO order_command.customer(id,name,email)
                    SELECT md5('dashboard-load-customer-'||g)::uuid,'Load Buyer','load-'||g||'@example.com'
                    FROM generate_series(0,49) g
                    """);
            jdbc.update("""
                    INSERT INTO order_command.product(id,sku,name,unit_price)
                    SELECT md5('dashboard-load-product-'||g)::uuid,'LOAD-'||g,'Load Product '||g,100
                    FROM generate_series(0,9) g
                    """);
            jdbc.update("""
                    INSERT INTO order_command.orders(id,customer_id,status,total_amount,created_at)
                    SELECT md5('dashboard-load-order-'||g)::uuid,md5('dashboard-load-customer-'||(g%50))::uuid,
                      CASE WHEN g%10=0 THEN 'CANCELLED' ELSE 'CREATED' END,100,
                      CAST(? AS timestamptz)-(g%60)*interval '1 day'-(g%120)*interval '1 minute'
                    FROM generate_series(1,10000) g
                    """,Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO order_command.item(id,order_id,product_id,product_sku,product_name,quantity,unit_price)
                    SELECT md5('dashboard-load-item-'||g)::uuid,md5('dashboard-load-order-'||g)::uuid,
                      md5('dashboard-load-product-'||(g%10))::uuid,'LOAD-'||(g%10),'Load Product '||(g%10),1,100
                    FROM generate_series(1,10000) g
                    """);
        });
        var durations=new ArrayList<Long>();
        for(int batch=0;batch<3;batch++){
            requests.rebuild();dispatcher.dispatch();
            for(int window=0;window<5;window++){
                long start=System.nanoTime();assertThat(worker.processOne()).isTrue();
                durations.add((System.nanoTime()-start)/1_000_000);
            }
        }
        durations.sort(Long::compareTo);
        long p95=durations.get((int)Math.ceil(durations.size()*0.95)-1);
        int largest=jdbc.queryForObject("SELECT max(octet_length(payload::text)) FROM order_command.order_outbox WHERE event_type='SALES_DASHBOARD'",Integer.class);
        System.out.println("Dashboard calculation load: orders=10000 items=10000 buyers=50 windows=5 samples="+durations.size()+" p95Ms="+p95+" largestPayloadBytes="+largest);
        assertThat(largest).isLessThan(512*1024);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.dashboard_refresh_control WHERE requested_generation>completed_generation",Integer.class)).isZero();
    }
}
