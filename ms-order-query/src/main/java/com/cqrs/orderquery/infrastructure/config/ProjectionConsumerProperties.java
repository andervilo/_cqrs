package com.cqrs.orderquery.infrastructure.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("query.projections")
public record ProjectionConsumerProperties(boolean enabled,
        @NotBlank @Size(max=249) @Pattern(regexp="[A-Za-z0-9._-]+") String topic,
        @NotBlank @Size(max=249) @Pattern(regexp="[A-Za-z0-9._-]+") String dltTopic,
        @NotBlank @Size(max=64) @Pattern(regexp="[A-Za-z0-9._-]+") String consumerName,
        @Min(1) @Max(64) int concurrency,@Min(1) @Max(10) int maxAttempts,
        @Min(1) @Max(60000) long retryInitialDelayMs,@Min(1) @Max(60000) long retryMaxDelayMs,
        @Min(1) @Max(60000) long dltSendTimeoutMs,boolean createTopics,
        @Min(1) @Max(1000) int partitions,@Min(1) @Max(32767) int replicas) {
    @AssertTrue(message="Invalid retry range or duplicate source/DLT topic")
    public boolean isConfigurationValid() { return retryMaxDelayMs>=retryInitialDelayMs && !topic.equals(dltTopic); }
}
