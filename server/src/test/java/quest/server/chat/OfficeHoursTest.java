package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import quest.server.staffattendance.ScheduleResolver;

/**
 * Feature 19: office hours resolved for the cases the spec lists — inside the hours, before the start, after the end,
 * a Thursday evening that waits for Sunday, a holiday, Ramadan hours and a weekend day. All pure: the hours and the
 * holidays are built here, so nothing below needs a school or a database.
 *
 * <p>Riyadh, and the week the pilot school runs (Sunday–Thursday, 07:00–14:00). 2026-10-18 is a Sunday.
 */
class OfficeHoursTest {
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18), THURSDAY = LocalDate.of(2026, 10, 22);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 23), NEXT_SUNDAY = LocalDate.of(2026, 10, 25);

    @Test void inside_her_hours_she_is_open_and_there_is_no_next_start() {
        var status = at(SUNDAY, "09:00");
        assertThat(status.open()).isTrue();
        assertThat(status.nextStart()).isNull();
        assertThat(status.nextStartLocal()).isNull();
        assertThat(status.startedToday()).isTrue();
        assertThat(status.today()).isEqualTo(SUNDAY);
    }

    @Test void before_the_start_the_next_start_is_today() {
        var status = at(SUNDAY, "06:30");
        assertThat(status.open()).isFalse();
        assertThat(status.startedToday()).as("her day has not begun, so no summary yet").isFalse();
        assertThat(status.nextStartLocal()).isEqualTo(SUNDAY.atTime(7, 0));
        assertThat(status.nextStart()).isEqualTo(SUNDAY.atTime(7, 0).atZone(RIYADH).toInstant());
    }

    @Test void in_the_evening_the_next_start_is_tomorrow() {
        var status = at(SUNDAY, "21:00");
        assertThat(status.open()).isFalse();
        assertThat(status.startedToday()).as("today was a working day and it has started").isTrue();
        assertThat(status.nextStartLocal()).isEqualTo(SUNDAY.plusDays(1).atTime(7, 0));
    }

    @Test void a_thursday_evening_waits_for_sunday() {
        assertThat(THURSDAY.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(at(THURSDAY, "21:00").nextStartLocal()).isEqualTo(NEXT_SUNDAY.atTime(7, 0));
    }

    @Test void a_weekend_day_waits_for_the_next_working_day_and_has_not_started() {
        var status = at(FRIDAY, "10:00");
        assertThat(status.open()).isFalse();
        assertThat(status.startedToday()).as("a day off never starts, so a summary waits for Sunday").isFalse();
        assertThat(status.nextStartLocal()).isEqualTo(NEXT_SUNDAY.atTime(7, 0));
    }

    @Test void a_holiday_is_skipped_and_the_next_start_is_the_day_after_it() {
        var monday = SUNDAY.plusDays(1);
        var holidays = List.of(new ScheduleResolver.Holiday("National Day", monday, monday));
        var evening = OfficeHours.status(hours(), holidays, RIYADH, instant(SUNDAY, "21:00"));
        assertThat(evening.nextStartLocal()).isEqualTo(monday.plusDays(1).atTime(7, 0));
        // And on the holiday itself she is closed all day and no summary is due.
        var onIt = OfficeHours.status(hours(), holidays, RIYADH, instant(monday, "09:00"));
        assertThat(onIt.open()).isFalse();
        assertThat(onIt.startedToday()).isFalse();
        assertThat(onIt.nextStartLocal()).isEqualTo(monday.plusDays(1).atTime(7, 0));
    }

    @Test void inside_ramadan_the_ramadan_hours_decide() {
        var ramadan = new ScheduleResolver.Hours(EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), LocalTime.of(7, 0), LocalTime.of(14, 0),
                SUNDAY.minusDays(3), SUNDAY.plusDays(20), LocalTime.of(9, 0), LocalTime.of(13, 0));
        assertThat(OfficeHours.status(ramadan, List.of(), RIYADH, instant(SUNDAY, "08:00")))
                .satisfies(s -> { assertThat(s.open()).as("08:00 is before the Ramadan start").isFalse();
                                  assertThat(s.nextStartLocal()).isEqualTo(SUNDAY.atTime(9, 0)); });
        assertThat(OfficeHours.status(ramadan, List.of(), RIYADH, instant(SUNDAY, "10:00")).open()).isTrue();
        assertThat(OfficeHours.status(ramadan, List.of(), RIYADH, instant(SUNDAY, "13:30")))
                .satisfies(s -> { assertThat(s.open()).as("the Ramadan end, not 14:00").isFalse();
                                  assertThat(s.nextStartLocal()).isEqualTo(SUNDAY.plusDays(1).atTime(9, 0)); });
    }

    @Test void a_school_on_holiday_for_longer_than_the_lookahead_has_no_next_start() {
        var forever = List.of(new ScheduleResolver.Holiday("Summer", SUNDAY, SUNDAY.plusDays(200)));
        var status = OfficeHours.status(hours(), forever, RIYADH, instant(SUNDAY, "09:00"));
        assertThat(status.open()).isFalse();
        assertThat(status.nextStart()).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private static OfficeHours.Status at(LocalDate day, String riyadhTime) {
        return OfficeHours.status(hours(), List.of(), RIYADH, instant(day, riyadhTime));
    }

    private static ScheduleResolver.Hours hours() {
        return new ScheduleResolver.Hours(EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), LocalTime.of(7, 0), LocalTime.of(14, 0), null, null, null, null);
    }

    private static Instant instant(LocalDate day, String riyadhTime) {
        return LocalDateTime.of(day, LocalTime.parse(riyadhTime)).atZone(RIYADH).toInstant();
    }
}
