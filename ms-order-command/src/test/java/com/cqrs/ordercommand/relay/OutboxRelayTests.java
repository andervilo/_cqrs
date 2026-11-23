package com.cqrs.ordercommand.relay;

import com.cqrs.ordercommand.application.relay.*;
import com.cqrs.ordercommand.infrastructure.messaging.KafkaOutboxPublisher;
import com.cqrs.ordercommand.infrastructure.persistence.OutboxRelayStore;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OutboxRelayTests {
    private OutboxRelayProperties properties() {
        return new OutboxRelayProperties(true,"cqrs.projections.v1",3,1000,5000,1000,100,1000,3,true,3,1);
    }
    @Test
    void doesNotMarkSentUntilKafkaConfirmsAndCarriesMetadataOutsideJson() {
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        var store=mock(OutboxRelayStore.class);
        var event=new OutboxMessage(UUID.randomUUID(),"CUSTOMER_SUMMARY","UPSERT",UUID.randomUUID().toString(),
                7,1,Instant.parse("2026-11-23T15:00:00Z"),"{\"name\":\"Maria\"}",0);
        when(store.lockNext(3)).thenReturn(java.util.Optional.of(event));
        CompletableFuture<SendResult<String,String>> ack=new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            verify(store,never()).sent(any());
            ack.complete(mock(SendResult.class));
            return ack;
        });
        var publisher=new KafkaOutboxPublisher(kafka,properties());
        var worker=new OutboxRelayWorker(store,publisher,properties(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.SENT);
        verify(store).sent(event.id());
        @SuppressWarnings("unchecked") ArgumentCaptor<ProducerRecord<String,String>> capture=ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(capture.capture());
        var record=capture.getValue();
        assertThat(record.key()).isEqualTo("CUSTOMER_SUMMARY:"+event.projectionKey());
        assertThat(record.value()).isEqualTo(event.payload());
        assertThat(new String(record.headers().lastHeader("projectionVersion").value(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("7");
        assertThat(new String(record.headers().lastHeader("eventId").value(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(event.id().toString());
        assertThat(record.headers().toArray()).hasSize(7);
    }
    @Test
    void failureRecordsBackoffAndStopsBatchWithoutMarkingSent() {
        var store=mock(OutboxRelayStore.class);
        var publisher=mock(KafkaOutboxPublisher.class);
        var event=new OutboxMessage(UUID.randomUUID(),"PRODUCT_DETAIL","UPSERT",UUID.randomUUID().toString(),1,1,Instant.now(),"{}",1);
        when(store.lockNext(3)).thenReturn(java.util.Optional.of(event));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(event);
        var worker=new OutboxRelayWorker(store,publisher,properties(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.FAILED);
        verify(store).failed(eq(event.id()),eq(2),eq(200L),eq("IllegalStateException"));
        verify(store,never()).sent(any());
        assertThat(properties().backoffMillis(100)).isEqualTo(1000);
        var scheduler=new OutboxRelayScheduler(worker,properties());
        scheduler.poll();
        verify(store,times(2)).lockNext(3);
    }
    @Test
    void acknowledgementTimeoutKeepsEventRetryableAndLateAckCannotMarkItSent() {
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        var store=mock(OutboxRelayStore.class);
        var event=new OutboxMessage(UUID.randomUUID(),"PRODUCT_DETAIL","UPSERT",UUID.randomUUID().toString(),1,1,Instant.now(),"{}",0);
        when(store.lockNext(3)).thenReturn(java.util.Optional.of(event));
        CompletableFuture<SendResult<String,String>> delayed=new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(delayed);
        var shortWait=new OutboxRelayProperties(true,"test",1,1000,0,10,100,1000,3,false,1,1);
        var worker=new OutboxRelayWorker(store,new KafkaOutboxPublisher(kafka,shortWait),shortWait,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThat(worker.publishNext()).isEqualTo(OutboxRelayWorker.Result.FAILED);
        verify(store).failed(event.id(),1,100L,"TimeoutException");
        delayed.complete(mock(SendResult.class));
        verify(store,never()).sent(any());
    }
}
