package com.cqrs.ordercommand;

import com.cqrs.ordercommand.application.relay.*;
import com.cqrs.ordercommand.infrastructure.messaging.KafkaOutboxPublisher;
import com.cqrs.ordercommand.infrastructure.persistence.OutboxRelayStore;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "outbox.relay.enabled=false","outbox.relay.topic=relay-integration-test.v1",
    "outbox.relay.max-attempts=2","outbox.relay.retry-initial-delay-ms=2000","outbox.relay.retry-max-delay-ms=4000",
    "dashboard.enabled=false"
})
class OutboxRelayIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRelayWorker worker;
    @Autowired OutboxRelayStore store;
    @Autowired KafkaAdmin admin;
    @Autowired ConsumerFactory<String,String> consumers;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean KafkaOutboxPublisher publisher;
    @LocalServerPort int port;
    private static final String TOPIC="relay-integration-test.v1";

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM order_command.order_outbox");
        admin.createOrModifyTopics(new NewTopic(TOPIC,3,(short)1));
    }
    UUID event(UUID key,int version) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_command.order_outbox(id,data_criacao,status,event_type,payload,operation,
                projection_key,projection_version,schema_version) VALUES (?,clock_timestamp(),'PENDING',
                'CUSTOMER_SUMMARY',?::jsonb,'UPSERT',?,?,1)
                """,id,"{\"customerId\":\""+key+"\",\"orderCount\":"+version+"}",key.toString(),version);
        return id;
    }
    String status(UUID id) { return jdbc.queryForObject("SELECT status FROM order_command.order_outbox WHERE id=?",String.class,id); }

    @Test
    void skipsLockedRowsAndNeverOvertakesAnUnsentPredecessor() throws Exception {
        UUID key=UUID.randomUUID(),first=event(key,1),second=event(key,2),other=event(UUID.randomUUID(),1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("test lock timeout");
            return invocation.callRealMethod();
        }).when(publisher).publish(argThat(message -> message.id().equals(first)));
        try(var executor=Executors.newSingleThreadExecutor()) {
            var sending=executor.submit(worker::publishNext);
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(status(first)).isEqualTo("PENDING");
            try {
                assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.SENT);
                assertThat(status(other)).isEqualTo("SENDED");
                assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.EMPTY);
                assertThat(status(second)).isEqualTo("PENDING");
            } finally { release.countDown(); }
            assertThat(sending.get(15,TimeUnit.SECONDS)).isEqualTo(OutboxRelayWorker.Result.SENT);
        }
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.SENT);
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.EMPTY);
        var received=consume(Set.of(first,second,other));
        assertThat(received).hasSize(3);
        var ordered=received.stream().filter(record -> record.key().equals("CUSTOMER_SUMMARY:"+key)).toList();
        assertThat(ordered.stream().map(record -> text(record,"projectionVersion")).toList()).containsExactly("1","2");
        var record=received.stream().filter(r -> text(r,"eventId").equals(first.toString())).findFirst().orElseThrow();
        assertThat(record.value()).isEqualTo(jdbc.queryForObject("SELECT payload::text FROM order_command.order_outbox WHERE id=?",String.class,first));
        assertThat(text(record,"schemaVersion")).isEqualTo("1");
        assertThat(text(record,"operation")).isEqualTo("UPSERT");
        assertThat(java.time.Instant.parse(text(record,"occurredAt"))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE status='SENDED' AND data_envio IS NOT NULL AND next_attempt_at IS NULL",Integer.class)).isEqualTo(3);
    }

    @Test
    void backsOffExhaustsAndRetriesSameEventManuallyThroughHttp() throws Exception {
        UUID key=UUID.randomUUID(),first=event(key,1),second=event(key,2);
        String payload=jdbc.queryForObject("SELECT payload::text FROM order_command.order_outbox WHERE id=?",String.class,first);
        doThrow(new IllegalStateException("test broker unavailable")).when(publisher).publish(any());
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.FAILED);
        assertThat(status(first)).isEqualTo("ERRO");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at>clock_timestamp() FROM order_command.order_outbox WHERE id=?",Boolean.class,first)).isTrue();
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.EMPTY);
        jdbc.update("UPDATE order_command.order_outbox SET next_attempt_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",first);
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.FAILED);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM order_command.order_outbox WHERE id=?",Integer.class,first)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NULL AND data_envio IS NULL FROM order_command.order_outbox WHERE id=?",Boolean.class,first)).isTrue();
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.EMPTY);
        assertThat(retryHttp(first)).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM order_command.order_outbox WHERE id=?",Integer.class,first)).isZero();
        doCallRealMethod().when(publisher).publish(any());
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.SENT);
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.SENT);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM order_command.order_outbox WHERE id=?",String.class,first)).isEqualTo(payload);
        assertThat(retryHttp(first)).isEqualTo(409);
        assertThat(retryHttp(UUID.randomUUID())).isEqualTo(404);
        assertThat(consume(Set.of(first,second))).hasSize(2);
    }

    @Test
    void uncommittedEventsAreInvisibleAndRowLocksAreReleasedOnRollback() throws Exception {
        var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var writing=executor.submit(() -> transactions.executeWithoutResult(tx -> {
                event(UUID.randomUUID(),1); ready.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                tx.setRollbackOnly();
            }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.EMPTY); }
            finally { release.countDown(); }
            writing.get(10,TimeUnit.SECONDS);
        }
        UUID id=event(UUID.randomUUID(),1);
        transactions.executeWithoutResult(tx -> { assertThat(store.lockNext(2)).isPresent(); tx.setRollbackOnly(); });
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.SENT);
        assertThat(status(id)).isEqualTo("SENDED");
    }
    @Test
    void databaseFailureAfterAckAllowsRedeliveryWithSameIdentity() {
        UUID id=event(UUID.randomUUID(),1);
        jdbc.execute("ALTER TABLE order_command.order_outbox ADD CONSTRAINT test_block_relay_confirmation CHECK (status <> 'SENDED') NOT VALID");
        try {
            assertThatThrownBy(worker::publishNext).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(status(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM order_command.order_outbox WHERE id=?",Integer.class,id)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE order_command.order_outbox DROP CONSTRAINT test_block_relay_confirmation");
        }
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.SENT);
        var delivered=consume(Set.of(id),2,false);
        assertThat(delivered.stream().map(record -> text(record,"eventId"))).containsOnly(id.toString());
        assertThat(delivered.stream().map(record -> text(record,"projectionVersion"))).containsOnly("1");
        assertThat(delivered.get(0).value()).isEqualTo(delivered.get(1).value());
    }
    int retryHttp(UUID id) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/order-outbox/"+id+"/retry"))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    List<ConsumerRecord<String,String>> consume(Set<UUID> ids) {
        return consume(ids,ids.size(),true);
    }
    List<ConsumerRecord<String,String>> consume(Set<UUID> ids,int expected,boolean unique) {
        var records=new ArrayList<ConsumerRecord<String,String>>();
        try(var consumer=consumers.createConsumer("relay-test-"+UUID.randomUUID(),"relay")) {
            consumer.subscribe(List.of(TOPIC));
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(records.size()<expected && System.nanoTime()<deadline) {
                for(var record:consumer.poll(Duration.ofMillis(500))) {
                    var header=record.headers().lastHeader("eventId");
                    if(header!=null && ids.contains(UUID.fromString(text(record,"eventId")))) records.add(record);
                }
            }
        }
        assertThat(records).hasSize(expected);
        if(unique) assertThat(records.stream().map(record -> text(record,"eventId"))).doesNotHaveDuplicates();
        return records;
    }
    static String text(ConsumerRecord<String,String> record,String header) {
        return new String(record.headers().lastHeader(header).value(),StandardCharsets.UTF_8);
    }
}
