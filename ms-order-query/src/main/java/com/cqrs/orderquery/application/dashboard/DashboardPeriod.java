package com.cqrs.orderquery.application.dashboard;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Stable wire identities, also seeded by V5. Changing these IDs creates different projections. */
public enum DashboardPeriod {
    TODAY("8b99f328-cb8c-4e22-8aa6-c04fe81cb001"),
    LAST_7_DAYS("8b99f328-cb8c-4e22-8aa6-c04fe81cb002"),
    LAST_30_DAYS("8b99f328-cb8c-4e22-8aa6-c04fe81cb003"),
    MONTH_TO_DATE("8b99f328-cb8c-4e22-8aa6-c04fe81cb004"),
    PREVIOUS_MONTH("8b99f328-cb8c-4e22-8aa6-c04fe81cb005");

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private final UUID id;
    DashboardPeriod(String id) { this.id = UUID.fromString(id); }
    public UUID id() { return id; }

    public Window window(Instant instant) {
        Instant reference = instant.truncatedTo(ChronoUnit.MICROS);
        LocalDate today = reference.atZone(ZONE).toLocalDate();
        LocalDate startDate = switch (this) {
            case TODAY -> today;
            case LAST_7_DAYS -> today.minusDays(6);
            case LAST_30_DAYS -> today.minusDays(29);
            case MONTH_TO_DATE -> today.withDayOfMonth(1);
            case PREVIOUS_MONTH -> today.withDayOfMonth(1).minusMonths(1);
        };
        Instant start = startDate.atStartOfDay(ZONE).toInstant();
        Instant end = this == PREVIOUS_MONTH ? today.withDayOfMonth(1).atStartOfDay(ZONE).toInstant() : reference;
        Duration duration = Duration.between(start, end);
        int shift = switch (this) { case TODAY -> 1; case LAST_7_DAYS -> 7; case LAST_30_DAYS -> 30; default -> 0; };
        Instant previousStart = shift == 0 ? start.minus(duration) : startDate.minusDays(shift).atStartOfDay(ZONE).toInstant();
        Instant previousEnd = previousStart.plus(duration);
        String status = previousEnd.isAfter(start) ? "CALENDAR_ALIGNMENT_LIMIT" : "AVAILABLE";
        String mode = this == TODAY ? "PREVIOUS_DAY_SAME_ELAPSED" : shift > 0 ? "PREVIOUS_CALENDAR_WINDOW_SAME_ELAPSED" : "PREVIOUS_EQUAL_DURATION";
        return new Window(new DashboardSnapshot.Period(start,end,this != PREVIOUS_MONTH),
                new DashboardSnapshot.Comparison(mode,previousStart,previousEnd,status,duration.toSeconds()));
    }
    public record Window(DashboardSnapshot.Period period, DashboardSnapshot.Comparison comparison) {}
}
