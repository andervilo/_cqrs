package com.cqrs.ordercommand.application.relay;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("outbox.relay")
public record OutboxRelayProperties(boolean enabled, @NotBlank String topic,
        @Min(1) @Max(1000) int batchSize, @Min(1) long pollIntervalMs, @Min(0) long initialDelayMs,
        @Min(1) @Max(60000) long sendTimeoutMs,
        @Min(1) @Max(3600000) long retryInitialDelayMs,
        @Min(1) @Max(3600000) long retryMaxDelayMs,
        @Min(1) @Max(100) int maxAttempts, boolean createTopic,
        @Min(1) @Max(1000) int partitions, @Min(1) @Max(32767) int replicas) {
    @AssertTrue(message="retry-max-delay-ms must be >= retry-initial-delay-ms")
    public boolean isRetryRangeValid() { return retryMaxDelayMs>=retryInitialDelayMs; }
    public long backoffMillis(int attempt) {
        long delay=retryInitialDelayMs;
        for(int i=1;i<attempt && delay<retryMaxDelayMs;i++) delay=Math.min(retryMaxDelayMs,delay*2);
        return delay;
    }
}
