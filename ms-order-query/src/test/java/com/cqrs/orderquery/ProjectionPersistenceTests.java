package com.cqrs.orderquery;

import com.cqrs.orderquery.application.inbox.*;
import com.cqrs.orderquery.application.inbox.ProjectionInboxTransaction.Outcome;
import com.cqrs.orderquery.domain.*;
import com.cqrs.orderquery.infrastructure.messaging.ProjectionEventDecoder;
import com.cqrs.orderquery.infrastructure.persistence.UuidBytes;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="query.projections.enabled=false")
class ProjectionPersistenceTests {
    @Autowired ProjectionEventDecoder decoder;
    @Autowired ProjectionInboxProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private final HttpClient http=HttpClient.newHttpClient();
    Outcome apply(ProjectionEventType type,UUID key,long version) {
        return processor.process(decoder.decode(ProjectionFixtures.event(type,key,version)));
    }
    JsonNode request(String method,String path,int expected) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/queries"+path))
                .timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        assertThat(response.body()).doesNotContain("projectionVersion","projectedAt","contentHash","sourceOffset");
        return response.body().isBlank()?null:json.readTree(response.body());
    }
    @Test void materializesSevenIndependentTablesAndReturnsTypedReadOnlyDtos() throws Exception {
        UUID customer=UUID.randomUUID(),product=UUID.randomUUID(),order=UUID.randomUUID();
        for(var type:ProjectionEventType.values()) {
            if(type==ProjectionEventType.SALES_DASHBOARD) continue;
            UUID key=switch(type) {
                case CUSTOMER_SUMMARY,CUSTOMER_DETAIL -> customer;
                case PRODUCT_CATALOG,PRODUCT_DETAIL -> product;
                default -> order;
            };
            var event=ProjectionFixtures.event(type,key,1,UUID.randomUUID(),ProjectionOperation.UPSERT,
                    ProjectionFixtures.payload(type,customer,order,product).replace("Maria Silva","Customer-"+customer).replace("Teclado","Product-"+product));
            assertThat(processor.process(decoder.decode(event))).isEqualTo(Outcome.APPLIED);
        }
        var c=request("GET","/customers/"+customer,200);
        assertThat(c.path("customerId").asText()).isEqualTo(customer.toString());
        assertThat(c.path("addresses").isArray()).isTrue();
        var pd=request("GET","/products/"+product,200);
        assertThat(pd.path("attributes").isObject()).isTrue();assertThat(pd.path("imageUrls").isArray()).isTrue();
        var o=request("GET","/orders/"+order,200);
        assertThat(o.path("customer").path("customerId").asText()).isEqualTo(customer.toString());
        assertThat(o.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(o.path("items").get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(Instant.parse(o.path("createdAt").asText())).isEqualTo(Instant.parse("2026-11-23T14:00:00Z"));
        assertThat(request("GET","/customers?search="+customer,200).path("totalElements").asLong()).isEqualTo(1);
        assertThat(request("GET","/products?search="+product+"&minPrice=100&maxPrice=200&available=true",200).path("totalElements").asLong()).isEqualTo(1);
        assertThat(request("GET","/orders?customerId="+customer+"&status=CREATED&from=2026-11-23T14:00:00Z&to=2026-11-23T14:00:01Z",200).path("totalElements").asLong()).isEqualTo(1);
        assertThat(request("GET","/orders?customerId="+customer+"&to=2026-11-23T14:00:00Z",200).path("totalElements").asLong()).isZero();
        assertThat(request("GET","/customers/"+customer+"/orders",200).path("totalElements").asLong()).isEqualTo(1);
        request("POST","/customers",405);request("PUT","/products/"+product,405);request("DELETE","/orders/"+order,405);
    }

    @Test void duplicateEventAtAnotherOffsetDoesNotRewriteProjection() {
        UUID key=UUID.randomUUID(),id=UUID.randomUUID();
        var first=ProjectionFixtures.event(ProjectionEventType.PRODUCT_CATALOG,key,1,id,ProjectionOperation.UPSERT,ProjectionFixtures.payload(ProjectionEventType.PRODUCT_CATALOG,key));
        assertThat(processor.process(decoder.decode(first))).isEqualTo(Outcome.APPLIED);
        var projected=jdbc.queryForObject("SELECT projected_at FROM product_catalog_projection WHERE product_id=?",LocalDateTime.class,UuidBytes.from(key));
        var replay=ProjectionFixtures.event(ProjectionEventType.PRODUCT_CATALOG,key,1,id,ProjectionOperation.UPSERT,first.value());
        assertThat(processor.process(decoder.decode(replay))).isEqualTo(Outcome.DUPLICATE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_event WHERE event_id=?",Integer.class,UuidBytes.from(id))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT projected_at FROM product_catalog_projection WHERE product_id=?",LocalDateTime.class,UuidBytes.from(key))).isEqualTo(projected);
    }
    @Test void olderSnapshotsAreRecordedWithoutOverwritingAndDeletesRetainTombstones() {
        UUID key=UUID.randomUUID();
        assertThat(apply(ProjectionEventType.PRODUCT_DETAIL,key,4)).isEqualTo(Outcome.APPLIED);
        assertThat(apply(ProjectionEventType.PRODUCT_DETAIL,key,2)).isEqualTo(Outcome.IGNORED_STALE);
        var deleted=decoder.decode(ProjectionFixtures.event(ProjectionEventType.PRODUCT_DETAIL,key,5,UUID.randomUUID(),ProjectionOperation.DELETE,"{}"));
        assertThat(processor.process(deleted)).isEqualTo(Outcome.APPLIED);
        assertThat(apply(ProjectionEventType.PRODUCT_DETAIL,key,3)).isEqualTo(Outcome.IGNORED_STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_detail_projection WHERE product_id=?",Integer.class,UuidBytes.from(key))).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted FROM projection_state WHERE event_type='PRODUCT_DETAIL' AND projection_key=?",Boolean.class,key.toString())).isTrue();
        assertThat(processor.process(deleted)).isEqualTo(Outcome.DUPLICATE);
        assertThat(apply(ProjectionEventType.PRODUCT_DETAIL,key,6)).isEqualTo(Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT deleted FROM projection_state WHERE event_type='PRODUCT_DETAIL' AND projection_key=?",Boolean.class,key.toString())).isFalse();
    }
    @Test void conflictingIdentityOrRevisionIsRejectedWithoutChangingGoodData() {
        UUID key=UUID.randomUUID(),id=UUID.randomUUID();
        String payload=ProjectionFixtures.payload(ProjectionEventType.CUSTOMER_SUMMARY,key);
        processor.process(decoder.decode(ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1,id,ProjectionOperation.UPSERT,payload)));
        var conflicting=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1,id,ProjectionOperation.UPSERT,payload.replace("Maria Silva","Different name"));
        assertThatThrownBy(() -> processor.process(decoder.decode(conflicting))).isInstanceOf(InvalidProjectionEvent.class);
        assertThatThrownBy(() -> apply(ProjectionEventType.CUSTOMER_SUMMARY,key,1)).isInstanceOf(InvalidProjectionEvent.class);
        UUID other=UUID.randomUUID();
        assertThatThrownBy(() -> processor.process(decoder.decode(ProjectionFixtures.event(ProjectionEventType.PRODUCT_CATALOG,other,1,id,ProjectionOperation.UPSERT,
                ProjectionFixtures.payload(ProjectionEventType.PRODUCT_CATALOG,other))))).isInstanceOf(InvalidProjectionEvent.class);
        assertThat(jdbc.queryForObject("SELECT name FROM customer_summary_projection WHERE customer_id=?",String.class,UuidBytes.from(key))).isEqualTo("Maria Silva");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_state WHERE projection_key=?",Integer.class,other.toString())).isZero();
    }
    @Test void databaseFailureRollsBackInboxStateAndProjectionThenAllowsReplay() {
        UUID key=UUID.randomUUID(),id=UUID.randomUUID();
        var event=decoder.decode(ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1,id,ProjectionOperation.UPSERT,ProjectionFixtures.payload(ProjectionEventType.CUSTOMER_SUMMARY,key)));
        String hex=java.util.HexFormat.of().formatHex(UuidBytes.from(id));
        jdbc.execute("ALTER TABLE inbox_event ADD CONSTRAINT test_block_event CHECK(event_id <> X'"+hex+"')");
        try {
            assertThatThrownBy(() -> processor.process(event)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasStackTraceContaining("test_block_event");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_summary_projection WHERE customer_id=?",Integer.class,UuidBytes.from(key))).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_state WHERE projection_key=?",Integer.class,key.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_event WHERE event_id=?",Integer.class,UuidBytes.from(id))).isZero();
        } finally { jdbc.execute("ALTER TABLE inbox_event DROP CHECK test_block_event"); }
        assertThat(processor.process(event)).isEqualTo(Outcome.APPLIED);
    }
    @Test void concurrentDuplicateDeliveriesApplyExactlyOnceAndNewerVersionWins() throws Exception {
        UUID key=UUID.randomUUID();var event=decoder.decode(ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<Outcome>>();
            for(int i=0;i<6;i++) results.add(executor.submit(() -> { start.await();return processor.process(event); }));
            start.countDown();var outcomes=new ArrayList<Outcome>();
            for(var future:results) outcomes.add(future.get(30,TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(o -> o==Outcome.APPLIED).count()).isEqualTo(1);
        }
        var nextStart=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<Outcome>>();
            for(long version:new long[]{2,7,3,9,4,6}) {
                results.add(executor.submit(() -> {
                    nextStart.await();
                    String payload=ProjectionFixtures.payload(ProjectionEventType.CUSTOMER_SUMMARY,key).replace("Maria Silva","Version-"+version);
                    return processor.process(decoder.decode(ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,version,UUID.randomUUID(),ProjectionOperation.UPSERT,payload)));
                }));
            }
            nextStart.countDown();for(var future:results) future.get(30,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT name FROM customer_summary_projection WHERE customer_id=?",String.class,UuidBytes.from(key))).isEqualTo("Version-9");
        assertThat(jdbc.queryForObject("SELECT applied_version FROM projection_state WHERE projection_key=?",Long.class,key.toString())).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_event WHERE projection_key=?",Integer.class,key.toString())).isEqualTo(7);
    }
    @Test void validatesQueriesAndUsesStableUuidTieBreakers() throws Exception {
        String name="Page-"+UUID.randomUUID();var ids=new ArrayList<UUID>();
        for(int i=0;i<3;i++) {
            UUID id=UUID.randomUUID();ids.add(id);
            String payload=ProjectionFixtures.payload(ProjectionEventType.CUSTOMER_SUMMARY,id).replace("Maria Silva",name);
            processor.process(decoder.decode(ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,id,1,UUID.randomUUID(),ProjectionOperation.UPSERT,payload)));
        }
        var expected=ids.stream().map(UUID::toString).sorted().toList();
        var first=request("GET","/customers?search="+name+"&size=2&page=0",200);
        var second=request("GET","/customers?search="+name+"&size=2&page=1",200);
        assertThat(first.path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.path("content").get(0).path("customerId").asText()).isEqualTo(expected.get(0));
        assertThat(second.path("content").get(0).path("customerId").asText()).isEqualTo(expected.get(2));
        request("GET","/customers?sort=email,asc",400);request("GET","/products?size=101",400);
        request("GET","/products?minPrice=200&maxPrice=100",400);
        request("GET","/orders?from=2026-11-24T00:00:00Z&to=2026-11-23T00:00:00Z",400);
        request("GET","/customers/"+UUID.randomUUID(),404);
        request("GET","/orders/not-a-uuid",400);
    }
}
