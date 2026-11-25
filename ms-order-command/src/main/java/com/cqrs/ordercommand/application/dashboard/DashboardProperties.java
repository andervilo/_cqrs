package com.cqrs.ordercommand.application.dashboard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dashboard")
public record DashboardProperties(boolean enabled, @Min(1) @Max(1000) int dispatchBatchSize,
        @Min(0) @Max(3000) int debounceMs, @Min(1) @Max(10) int maxAttempts,
        @Min(100) int retryInitialDelayMs, @Min(100) int retryMaxDelayMs,
        @Min(1000) @Max(120000) int statementTimeoutMs, @NotNull @Valid DashboardSnapshot.Rules rules) {
    public DashboardProperties {
        if (retryMaxDelayMs < retryInitialDelayMs) throw new IllegalArgumentException("Invalid dashboard backoff range");
        if (rules != null) {
            if (rules.minimumOrderSample()<1 || rules.minimumProductSample()<1 || rules.minimumBuyerSample()<1
                    || rules.agingHours()!=48) throw new IllegalArgumentException("Invalid dashboard sample or age rule");
            for (var threshold : java.util.List.of(rules.cancellationRateThreshold(),rules.cancellationIncreasePoints(),
                    rules.demandDropPercent(),rules.retainedValueGrowthPercent(),rules.productDropPercent(),rules.customerConcentrationPercent()))
                if (threshold.signum()<0 || threshold.compareTo(java.math.BigDecimal.valueOf(100))>0)
                    throw new IllegalArgumentException("Invalid dashboard rule threshold");
        }
    }
    public long retryDelay(int attempt) {
        return Math.min(retryMaxDelayMs,(long)retryInitialDelayMs * (1L << Math.min(attempt-1,20)));
    }
}
