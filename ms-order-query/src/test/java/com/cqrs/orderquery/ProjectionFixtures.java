package com.cqrs.orderquery;

import com.cqrs.orderquery.domain.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

final class ProjectionFixtures {
    private static final AtomicLong OFFSETS=new AtomicLong();
    static String payload(ProjectionEventType type,UUID key) {
        UUID customer=type==ProjectionEventType.CUSTOMER_SUMMARY || type==ProjectionEventType.CUSTOMER_DETAIL?key:UUID.fromString("223e4567-e89b-42d3-a456-426614174000");
        UUID product=type==ProjectionEventType.PRODUCT_CATALOG || type==ProjectionEventType.PRODUCT_DETAIL?key:UUID.fromString("423e4567-e89b-42d3-a456-426614174000");
        return payload(type,customer,key,product);
    }
    static String payload(ProjectionEventType type,UUID customer,UUID order,UUID product) {
        try(var stream=ProjectionFixtures.class.getResourceAsStream("/contracts/"+type+".json")) {
            if(stream==null) throw new IllegalArgumentException("No contract fixture");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("${CUSTOMER_ID}",customer.toString())
                    .replace("${ORDER_ID}",order.toString()).replace("${PRODUCT_ID}",product.toString());
        } catch(IOException error) { throw new RuntimeException(error); }
    }
    static ConsumerRecord<String,String> event(ProjectionEventType type,UUID key,long version) {
        return event(type,key,version,UUID.randomUUID(),ProjectionOperation.UPSERT,payload(type,key));
    }
    static ConsumerRecord<String,String> event(ProjectionEventType type,UUID key,long version,UUID eventId,ProjectionOperation operation,String payload) {
        var record=new ConsumerRecord<String,String>("fixture-source",0,OFFSETS.incrementAndGet(),type+":"+key,payload);
        add(record,"eventId",eventId.toString());add(record,"eventType",type.name());add(record,"projectionKey",key.toString());
        add(record,"projectionVersion",Long.toString(version));add(record,"schemaVersion","1");add(record,"operation",operation.name());
        add(record,"occurredAt","2026-11-23T15:00:00Z");
        return record;
    }
    static void add(ConsumerRecord<String,String> record,String name,String value) { record.headers().add(name,value.getBytes(StandardCharsets.UTF_8)); }
    static ProducerRecord<String,String> producer(String topic,ConsumerRecord<String,String> source) {
        var result=new ProducerRecord<String,String>(topic,source.key(),source.value());
        source.headers().forEach(header -> result.headers().add(header));
        return result;
    }
}
