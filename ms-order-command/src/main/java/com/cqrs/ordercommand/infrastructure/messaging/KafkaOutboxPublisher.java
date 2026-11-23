package com.cqrs.ordercommand.infrastructure.messaging;

import com.cqrs.ordercommand.application.relay.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.apache.kafka.clients.producer.*;
import org.slf4j.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaOutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(KafkaOutboxPublisher.class);
    private final KafkaTemplate<String,String> kafka;
    private final OutboxRelayProperties properties;
    public KafkaOutboxPublisher(KafkaTemplate<String,String> kafka,OutboxRelayProperties properties) {
        this.kafka=kafka; this.properties=properties;
        // The default listener can log record values. Keep producer failure logs free of projection data.
        kafka.setProducerListener(new ProducerListener<>() {
            @Override public void onError(ProducerRecord<String,String> record,RecordMetadata metadata,Exception error) {
                log.warn("Kafka publication failed: topic={}, error={}",record.topic(),error.getClass().getSimpleName());
            }
        });
    }
    public void publish(OutboxMessage event) {
        var record=new ProducerRecord<String,String>(properties.topic(),event.kafkaKey(),event.payload());
        header(record,"eventId",event.id().toString());
        header(record,"eventType",event.eventType());
        header(record,"operation",event.operation());
        header(record,"projectionKey",event.projectionKey());
        header(record,"projectionVersion",Long.toString(event.projectionVersion()));
        header(record,"schemaVersion",Integer.toString(event.schemaVersion()));
        header(record,"occurredAt",event.occurredAt().toString());
        try {
            kafka.send(record).get(properties.sendTimeoutMs(),TimeUnit.MILLISECONDS);
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new PublicationException("Interrupted while awaiting Kafka acknowledgement",error);
        } catch(ExecutionException | TimeoutException error) {
            throw new PublicationException("Kafka acknowledgement failed",error);
        }
    }
    private void header(ProducerRecord<String,String> record,String name,String value) {
        record.headers().add(name,value.getBytes(StandardCharsets.UTF_8));
    }
    public static class PublicationException extends RuntimeException {
        public PublicationException(String message,Throwable cause) { super(message,cause); }
    }
}
