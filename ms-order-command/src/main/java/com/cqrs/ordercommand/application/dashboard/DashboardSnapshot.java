package com.cqrs.ordercommand.application.dashboard;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Transport contract v1. Mirrored independently in query; no business entities or PII. */
public record DashboardSnapshot(UUID dashboardId, DashboardPeriod periodCode, String timeZone, String currency,
        Instant sourceObservedAt, Instant computedAt, int definitionsVersion, int rulesVersion,
        Period period, Comparison comparison, Map<String,Metric> summary,
        OrderStats orders, OrderStats previousOrders, BuyerStats customers, BuyerStats previousCustomers,
        ValueDrivers valueChangeDrivers, Products products, List<Day> daily, List<Day> comparisonDaily,
        Portfolio openPortfolio, List<Insight> insights, int additionalInsightCount,
        List<UnavailableMetric> unavailableMetrics, Rules appliedRules, Coverage dataCoverage) {
    public record Period(Instant from, Instant to, boolean inProgress) {}
    public record Comparison(String mode, Instant from, Instant to, String status, long durationSeconds) {}
    public record Metric(BigDecimal value, BigDecimal previousValue, BigDecimal changeAbsolute,
            BigDecimal changePercent, BigDecimal changePercentagePoints, String status, String previousStatus,
            String comparisonStatus, String unit, String favorableDirection) {}
    public record OrderStats(long orderCount, long activeOrderCount, long cancelledOrderCount,
            BigDecimal requestedValue, BigDecimal retainedOrderValue, BigDecimal cancelledOrderValue,
            BigDecimal cancellationRate, BigDecimal cancellationValueRate, BigDecimal averageActiveOrderValue,
            long requestedUnits, long retainedUnits, long cancelledUnits) {}
    public record BuyerStats(long activeBuyerCount, long newActiveBuyerCount, long returningActiveBuyerCount,
            BigDecimal returningBuyerShare, BigDecimal ordersPerActiveBuyer, BigDecimal top5CustomerValueShare,
            BigDecimal top5CustomerRetainedValue) {}
    public record ValueDrivers(String status, BigDecimal totalChange, BigDecimal volumeEffect, BigDecimal averageValueEffect) {}
    public record Product(UUID productId, String productName, long retainedUnits, long activeOrderCount,
            BigDecimal retainedOrderValue, BigDecimal cancelledOrderValue, Long previousRetainedUnits,
            Long previousActiveOrderCount, BigDecimal previousRetainedOrderValue, BigDecimal previousCancelledOrderValue,
            BigDecimal changeAbsolute, BigDecimal changePercent, String comparisonStatus,
            BigDecimal retainedValueShare, BigDecimal cancellationValueRate) {}
    public record Products(BigDecimal top5ProductValueShare, BigDecimal previousTop5ProductValueShare,
            BigDecimal otherProductsRetainedValue, List<Product> byRetainedValue,
            List<Product> byRetainedUnits, List<Product> declining) {}
    public record Day(LocalDate date, Instant from, Instant to, long orderCount, long activeOrderCount,
            long cancelledOrderCount, BigDecimal requestedValue, BigDecimal retainedOrderValue,
            BigDecimal cancelledOrderValue, long requestedUnits, long retainedUnits,
            long cancelledUnits, long activeBuyerCount) {}
    public record AgeBucket(String code, long orderCount, BigDecimal value, DrillDown drillDown) {}
    public record Portfolio(String scope, List<AgeBucket> ageBuckets, long orderCount, BigDecimal value,
            AgeBucket olderThanOrEqual48h, Instant oldestCreatedAt) {}
    public record DrillDown(String method, String path, Map<String,String> query, String requiredScope) {}
    public record Evidence(String metric, BigDecimal currentValue, BigDecimal previousValue,
            BigDecimal change, long currentSampleSize, long previousSampleSize, String rule) {}
    public record Exposure(long orderCount, BigDecimal value) {}
    public record Insight(String code, String priority, String title, String description, Evidence evidence,
            Exposure exposure, String suggestedAction, DrillDown drillDown, List<String> limitations) {}
    public record UnavailableMetric(String code, BigDecimal value, String status, String reason) {}
    public record Rules(long minimumOrderSample, BigDecimal cancellationRateThreshold,
            BigDecimal cancellationIncreasePoints, BigDecimal demandDropPercent, BigDecimal retainedValueGrowthPercent,
            long minimumProductSample, BigDecimal productDropPercent, long minimumBuyerSample,
            BigDecimal customerConcentrationPercent, int agingHours) {}
    public record Coverage(String source, String generation, Instant earliestOrderAt, String completeness) {}
}
