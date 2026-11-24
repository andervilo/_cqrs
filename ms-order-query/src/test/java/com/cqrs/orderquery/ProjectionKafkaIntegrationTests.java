package com.cqrs.orderquery;

import com.cqrs.orderquery.application.inbox.*;
import com.cqrs.orderquery.domain.*;
import com.cqrs.orderquery.infrastructure.persistence.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.TransientDataAccessResourceException;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "query.projections.enabled=true","query.projections.topic=query-integration.v1",
    "query.projections.dlt-topic=query-integration.v1.DLT","query.projections.partitions=2",
    "query.projections.concurrency=2","query.projections.retry-initial-delay-ms=10",
    "query.projections.retry-max-delay-ms=20","spring.kafka.consumer.group-id=query-integration-consumer"
})
class ProjectionKafkaIntegrationTests {
    private static final String TOPIC="query-integration.v1",DLT=TOPIC+".DLT";
    @MockitoSpyBean KafkaTemplate<?,?> kafkaSpy;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired ConsumerFactory<String,String> consumers;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean InboxEventRepository inbox;

    InboxEventRepository inboxSpy() { return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(inbox); }

    UUID eventId(ConsumerRecord<String,String> record) {
        return UUID.fromString(new String(record.headers().lastHeader("eventId").value(),java.nio.charset.StandardCharsets.UTF_8));
    }
    int inboxCount(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM inbox_event WHERE event_id=?",Integer.class,UuidBytes.from(id)); }
    void send(ConsumerRecord<String,String> source,int partition) throws Exception {
        var record=new ProducerRecord<String,String>(TOPIC,partition,source.key(),source.value());
        source.headers().forEach(header -> record.headers().add(header));
        kafka.send(record).get(20,TimeUnit.SECONDS);
    }
    void processed(UUID id) { await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(inboxCount(id)).isEqualTo(1)); }

    @Test void consumerAppliesEveryContractAndIgnoresRedeliveryAtANewOffset() throws Exception {
        UUID customer=UUID.randomUUID(),product=UUID.randomUUID(),order=UUID.randomUUID();
        var events=new ArrayList<ConsumerRecord<String,String>>();
        for(var type:ProjectionEventType.values()) {
            if(type==ProjectionEventType.SALES_DASHBOARD) continue;
            UUID key=switch(type) {
                case CUSTOMER_SUMMARY,CUSTOMER_DETAIL -> customer;
                case PRODUCT_CATALOG,PRODUCT_DETAIL -> product;
                default -> order;
            };
            var event=ProjectionFixtures.event(type,key,1,UUID.randomUUID(),ProjectionOperation.UPSERT,ProjectionFixtures.payload(type,customer,order,product));
            events.add(event);send(event,0);
        }
        for(var event:events) processed(eventId(event));
        var first=events.getFirst();
        long originalOffset=jdbc.queryForObject("SELECT source_offset FROM inbox_event WHERE event_id=?",Long.class,UuidBytes.from(eventId(first)));
        send(first,1); // Deliberate replay in another partition: offset/partition is not the deduplication key.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(inboxSpy(),atLeast(2)).byEventId(anyString(),argThat(e -> e.eventId().equals(eventId(first)))));
        assertThat(inboxCount(eventId(first))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_offset FROM inbox_event WHERE event_id=?",Long.class,UuidBytes.from(eventId(first)))).isEqualTo(originalOffset);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM order_detail_projection WHERE order_id=?",java.math.BigDecimal.class,UuidBytes.from(order))).isEqualByComparingTo("300.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_catalog_projection WHERE product_id=?",Integer.class,UuidBytes.from(product))).isEqualTo(1);
    }

    @Test void transientFailureRetriesTheWholeTransactionBeforeAcknowledgement() throws Exception {
        UUID key=UUID.randomUUID();var event=ProjectionFixtures.event(ProjectionEventType.PRODUCT_CATALOG,key,1);UUID id=eventId(event);
        doThrow(new TransientDataAccessResourceException("injected transient failure")).doCallRealMethod()
                .when(inboxSpy()).insert(anyString(),argThat(e -> e.eventId().equals(id)),eq("APPLIED"));
        send(event,0);processed(id);
        verify(inboxSpy(),times(2)).insert(anyString(),argThat(e -> e.eventId().equals(id)),eq("APPLIED"));
        assertThat(jdbc.queryForObject("SELECT applied_version FROM projection_state WHERE event_type='PRODUCT_CATALOG' AND projection_key=?",Long.class,key.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT projection_version FROM product_catalog_projection WHERE product_id=?",Long.class,UuidBytes.from(key))).isEqualTo(1);
    }

    @Test void dltFailureBlocksFollowingRecordUntilPublicationSucceeds() throws Exception {
        UUID badKey=UUID.randomUUID(),goodKey=UUID.randomUUID(),id=UUID.randomUUID();
        var bad=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,badKey,1,id,ProjectionOperation.UPSERT,"{");
        var following=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,goodKey,1);
        var blocked=new CountDownLatch(1);var fail=new AtomicBoolean(true);
        doAnswer(invocation -> {
            ProducerRecord<?,?> record=invocation.getArgument(0);
            var header=record.headers().lastHeader("eventId");
            String value=header==null?"":new String(header.value(),java.nio.charset.StandardCharsets.UTF_8);
            if(record.topic().equals(DLT) && value.equals(id.toString()) && fail.get()) {
                blocked.countDown();
                return CompletableFuture.failedFuture(new org.springframework.kafka.KafkaException("Injected DLT failure"));
            }
            return invocation.callRealMethod();
        }).when(kafka).send(any(ProducerRecord.class));
        try {
            send(bad,0);send(following,0);
            assertThat(blocked.await(20,TimeUnit.SECONDS)).isTrue();
            assertThat(inboxCount(eventId(following))).isZero();
            assertThat(inboxCount(id)).isZero();
        } finally { fail.set(false); }
        processed(eventId(following));
        var dead=readDlt(id);
        assertThat(dead.value()).isEqualTo("{");
        assertThat(eventId(dead)).isEqualTo(id);
        assertThat(inboxCount(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_summary_projection WHERE customer_id=?",Integer.class,UuidBytes.from(badKey))).isZero();
    }

    @Test void oldSnapshotsAndTombstonesRemainSafeThroughKafkaReplay() throws Exception {
        UUID key=UUID.randomUUID();
        var current=ProjectionFixtures.event(ProjectionEventType.PRODUCT_DETAIL,key,3);
        var deleted=ProjectionFixtures.event(ProjectionEventType.PRODUCT_DETAIL,key,4,UUID.randomUUID(),ProjectionOperation.DELETE,"{}");
        var old=ProjectionFixtures.event(ProjectionEventType.PRODUCT_DETAIL,key,2);
        send(current,0);send(deleted,0);send(old,0);processed(eventId(old));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_detail_projection WHERE product_id=?",Integer.class,UuidBytes.from(key))).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted FROM projection_state WHERE event_type='PRODUCT_DETAIL' AND projection_key=?",Boolean.class,key.toString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT outcome FROM inbox_event WHERE event_id=?",String.class,UuidBytes.from(eventId(old)))).isEqualTo("IGNORED_STALE");
        send(deleted,1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(inboxSpy(),atLeast(2)).byEventId(anyString(),argThat(e -> e.eventId().equals(eventId(deleted)))));
        assertThat(inboxCount(eventId(deleted))).isEqualTo(1);
    }

    @Test void conflictingReuseGoesToDltWithoutOverwritingProjection() throws Exception {
        UUID key=UUID.randomUUID(),id=UUID.randomUUID();
        String original=ProjectionFixtures.payload(ProjectionEventType.CUSTOMER_SUMMARY,key);
        var first=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1,id,ProjectionOperation.UPSERT,original);
        send(first,0);processed(id);
        var conflict=ProjectionFixtures.event(ProjectionEventType.CUSTOMER_SUMMARY,key,1,id,ProjectionOperation.UPSERT,original.replace("Maria Silva","Changed"));
        send(conflict,0);
        assertThat(readDlt(id).value()).contains("Changed");
        assertThat(jdbc.queryForObject("SELECT name FROM customer_summary_projection WHERE customer_id=?",String.class,UuidBytes.from(key))).isEqualTo("Maria Silva");
        assertThat(inboxCount(id)).isEqualTo(1);
    }
    ConsumerRecord<String,String> readDlt(UUID id) {
        try(var consumer=consumers.createConsumer("dlt-test-"+UUID.randomUUID(),"dlt")) {
            consumer.subscribe(List.of(DLT));
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(System.nanoTime()<deadline) {
                for(var record:consumer.poll(Duration.ofMillis(500))) {
                    var header=record.headers().lastHeader("eventId");
                    if(header!=null && eventId(record).equals(id)) return record;
                }
            }
        }
        throw new AssertionError("Expected event did not reach DLT");
    }
}
