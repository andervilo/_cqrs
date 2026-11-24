package com.cqrs.orderquery.infrastructure.config;

import com.cqrs.orderquery.application.inbox.InvalidProjectionEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ProjectionConsumerProperties.class)
public class ProjectionKafkaConfiguration {
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(prefix="query.projections",name="enabled",havingValue="true")
    @EnableKafka
    static class EnabledConsumer {
        private static final Logger log=LoggerFactory.getLogger(EnabledConsumer.class);
        @Bean
        DeadLetterPublishingRecoverer projectionDltRecoverer(KafkaTemplate<String,String> template,ProjectionConsumerProperties properties) {
            KafkaUtils.setConsumerRecordFormatter(record -> record.topic()+"-"+record.partition()+"@"+record.offset());
            KafkaUtils.setProducerRecordFormatter(record -> "topic="+record.topic()+",partition="+record.partition());
            template.setProducerListener(new ProducerListener<>() {
                @Override public void onError(ProducerRecord<String,String> record,RecordMetadata metadata,Exception error) {
                    log.warn("DLT publication failed: topic={}, error={}",record.topic(),error.getClass().getSimpleName());
                }
            });
            var recoverer=new DeadLetterPublishingRecoverer(template,(record,error) ->
                    new TopicPartition(properties.dltTopic(),record.partition()));
            recoverer.setFailIfSendResultIsError(true);
            recoverer.setWaitForSendResultTimeout(Duration.ofMillis(properties.dltSendTimeoutMs()));
            recoverer.setLogRecoveryRecord(false);
            return recoverer;
        }
        @Bean
        DefaultErrorHandler projectionErrorHandler(DeadLetterPublishingRecoverer projectionDltRecoverer,
                ProjectionConsumerProperties properties,MeterRegistry metrics) {
            var backoff=new ExponentialBackOffWithMaxRetries(properties.maxAttempts()-1);
            backoff.setInitialInterval(properties.retryInitialDelayMs());
            backoff.setMaxInterval(properties.retryMaxDelayMs());
            backoff.setMultiplier(2.0);
            var handler=new DefaultErrorHandler((record,error) -> {
                projectionDltRecoverer.accept(record,error);
                metrics.counter("projection.consumer.dlt.published").increment();
            },backoff);
            handler.addNotRetryableExceptions(InvalidProjectionEvent.class);
            handler.setAckAfterHandle(true);
            handler.setRetryListeners((record,error,attempt) -> {
                metrics.counter("projection.consumer.delivery.failures").increment();
                log.warn("Projection delivery failed: topic={}, partition={}, offset={}, attempt={}, error={}",
                        record.topic(),record.partition(),record.offset(),attempt,error.getClass().getSimpleName());
            });
            return handler;
        }
        @Bean
        ConcurrentKafkaListenerContainerFactory<String,String> projectionKafkaListenerContainerFactory(
                ConsumerFactory<String,String> consumers,DefaultErrorHandler projectionErrorHandler,ProjectionConsumerProperties properties) {
            var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
            factory.setConsumerFactory(consumers);
            factory.setConcurrency(properties.concurrency());
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            factory.setCommonErrorHandler(projectionErrorHandler);
            return factory;
        }
        @Bean
        @ConditionalOnProperty(prefix="query.projections",name="create-topics",havingValue="true")
        KafkaAdmin.NewTopics projectionTopics(ProjectionConsumerProperties properties) {
            return new KafkaAdmin.NewTopics(
                    new NewTopic(properties.topic(),properties.partitions(),(short)properties.replicas()),
                    new NewTopic(properties.dltTopic(),properties.partitions(),(short)properties.replicas()));
        }
    }
}
