package com.cqrs.orderquery.infrastructure.messaging;

import com.cqrs.orderquery.application.inbox.ProjectionInboxProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="query.projections",name="enabled",havingValue="true")
public class ProjectionKafkaListener {
    private final ProjectionEventDecoder decoder;
    private final ProjectionInboxProcessor processor;
    public ProjectionKafkaListener(ProjectionEventDecoder decoder,ProjectionInboxProcessor processor) {
        this.decoder=decoder;this.processor=processor;
    }
    @KafkaListener(id="projection-consumer",topics="${query.projections.topic}",
            groupId="${spring.kafka.consumer.group-id}",containerFactory="projectionKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String,String> record) {
        // The separate transaction bean commits MySQL before this record listener returns.
        processor.process(decoder.decode(record));
    }
}
