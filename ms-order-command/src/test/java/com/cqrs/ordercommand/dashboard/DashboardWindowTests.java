package com.cqrs.ordercommand.dashboard;

import com.cqrs.ordercommand.application.dashboard.DashboardPeriod;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DashboardWindowTests {
    @Test void comparesMorningWithPreviousMorningAndKeepsExclusiveBounds() {
        var w = DashboardPeriod.TODAY.window(Instant.parse("2026-11-23T14:00:00Z"));
        assertThat(w.period().from()).isEqualTo(Instant.parse("2026-11-23T03:00:00Z"));
        assertThat(w.comparison().from()).isEqualTo(Instant.parse("2026-11-22T03:00:00Z"));
        assertThat(w.comparison().to()).isEqualTo(Instant.parse("2026-11-22T14:00:00Z"));
        assertThat(w.comparison().status()).isEqualTo("AVAILABLE");
    }

    @Test void shiftsWeeklyCalendarWindowAndHandlesHistoricalDaylightSaving() {
        var weekly = DashboardPeriod.LAST_7_DAYS.window(Instant.parse("2026-11-23T14:00:00Z"));
        assertThat(Duration.between(weekly.period().from(), weekly.period().to()))
                .isEqualTo(Duration.between(weekly.comparison().from(), weekly.comparison().to()));
        // 2018-11-04 began at 01:00 local time; the previous day had 24 hours.
        var daylight = DashboardPeriod.TODAY.window(Instant.parse("2018-11-04T14:00:00Z"));
        assertThat(daylight.period().from()).isEqualTo(Instant.parse("2018-11-04T03:00:00Z"));
        var limited = DashboardPeriod.TODAY.window(Instant.parse("2018-11-06T01:30:00Z"));
        assertThat(limited.comparison().status()).isEqualTo("CALENDAR_ALIGNMENT_LIMIT");
    }

    @Test void previousMonthIsClosedAndIdsAreStable() {
        var month = DashboardPeriod.PREVIOUS_MONTH.window(Instant.parse("2026-11-23T14:00:00Z"));
        assertThat(month.period().from()).isEqualTo(Instant.parse("2026-10-01T03:00:00Z"));
        assertThat(month.period().to()).isEqualTo(Instant.parse("2026-11-01T03:00:00Z"));
        assertThat(month.period().inProgress()).isFalse();
        assertThat(DashboardPeriod.values()).extracting(DashboardPeriod::id).doesNotHaveDuplicates();
    }
}
