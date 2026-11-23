package com.cqrs.ordercommand.infrastructure.config;

import com.cqrs.ordercommand.application.relay.OutboxRelayProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxRelayConfiguration {
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(prefix="outbox.relay",name="enabled",havingValue="true")
    @EnableScheduling
    static class Scheduling {
        @Bean
        @ConditionalOnProperty(prefix="outbox.relay",name="create-topic",havingValue="true")
        NewTopic projectionTopic(OutboxRelayProperties properties) {
            return new NewTopic(properties.topic(),properties.partitions(),(short)properties.replicas());
        }
    }
}
