package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import quest.server.staffattendance.ScheduleResolver.Holiday;
import quest.server.staffattendance.ScheduleResolver.Hours;
import quest.server.staffattendance.ScheduleResolver.Kind;

/** Feature 14's schedule resolution: a normal day, a Ramadan day, a holiday and a weekend. */
class ScheduleResolverTest {
    private static final Hours HOURS = new Hours(
            EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            LocalTime.of(7, 0), LocalTime.of(14, 0),
            LocalDate.of(2027, 2, 8), LocalDate.of(2027, 3, 9), LocalTime.of(9, 0), LocalTime.of(13, 0));
    private static final List<Holiday> HOLIDAYS = List.of(new Holiday("Mid-term break", LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 19)));

    @Test void a_normal_working_day_uses_the_normal_hours() {
        var day = ScheduleResolver.resolve(HOURS, HOLIDAYS, LocalDate.of(2026, 10, 13));   // a Tuesday
        assertThat(day.kind()).isEqualTo(Kind.WORK);
        assertThat(day.start()).isEqualTo(LocalTime.of(7, 0));
        assertThat(day.end()).isEqualTo(LocalTime.of(14, 0));
        assertThat(day.ramadan()).isFalse();
    }

    @Test void a_day_inside_the_ramadan_range_uses_the_ramadan_hours_including_both_ends() {
        for (var date : List.of(LocalDate.of(2027, 2, 8), LocalDate.of(2027, 2, 17), LocalDate.of(2027, 3, 9))) {
            var day = ScheduleResolver.resolve(HOURS, HOLIDAYS, date);
            assertThat(day.kind()).as("%s", date).isEqualTo(Kind.WORK);
            assertThat(day.ramadan()).as("%s", date).isTrue();
            assertThat(day.start()).isEqualTo(LocalTime.of(9, 0));
            assertThat(day.end()).isEqualTo(LocalTime.of(13, 0));
        }
        assertThat(ScheduleResolver.resolve(HOURS, HOLIDAYS, LocalDate.of(2027, 3, 10)).ramadan()).as("the day after").isFalse();
    }

    @Test void a_holiday_expects_nobody_even_on_a_working_day() {
        var day = ScheduleResolver.resolve(HOURS, HOLIDAYS, LocalDate.of(2026, 11, 17));   // a Tuesday
        assertThat(day.kind()).isEqualTo(Kind.HOLIDAY);
        assertThat(day.holidayName()).isEqualTo("Mid-term break");
        assertThat(day.start()).isNull();
    }

    @Test void a_weekend_day_is_a_day_off_even_inside_ramadan() {
        assertThat(ScheduleResolver.resolve(HOURS, HOLIDAYS, LocalDate.of(2026, 10, 16)).kind()).isEqualTo(Kind.DAY_OFF);   // Friday
        assertThat(ScheduleResolver.resolve(HOURS, HOLIDAYS, LocalDate.of(2027, 2, 13)).kind()).isEqualTo(Kind.DAY_OFF);    // Saturday in Ramadan
    }

    @Test void without_a_ramadan_range_the_normal_hours_apply_all_year() {
        var plain = new Hours(HOURS.weekdays(), HOURS.start(), HOURS.end(), null, null, null, null);
        assertThat(ScheduleResolver.resolve(plain, List.of(), LocalDate.of(2027, 2, 17)).start()).isEqualTo(LocalTime.of(7, 0));
    }
}
