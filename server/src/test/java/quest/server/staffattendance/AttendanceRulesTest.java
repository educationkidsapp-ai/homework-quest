package quest.server.staffattendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import quest.server.staffattendance.AttendanceRules.Event;
import quest.server.staffattendance.AttendanceRules.Thresholds;

/**
 * Feature 16: the status table, rule by rule, with the grace boundary to the second, and the days on which nothing
 * happens — holidays, weekends — and the Ramadan hours that move every threshold. Asia/Riyadh is UTC+3 all year.
 */
class AttendanceRulesTest {
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);
    static final Thresholds T = new Thresholds(10, 60, 60);
    static final ScheduleResolver.Hours HOURS = new ScheduleResolver.Hours(
            EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            LocalTime.of(7, 0), LocalTime.of(14, 0),
            LocalDate.of(2027, 2, 8), LocalDate.of(2027, 3, 9), LocalTime.of(9, 0), LocalTime.of(13, 0));

    /** A local Riyadh time on {@link #SUNDAY}, as an instant. */
    static Instant at(String hhmmss) { return SUNDAY.atTime(LocalTime.parse(hhmmss)).atZone(RIYADH).toInstant(); }

    static AttendanceRules.Day day(LocalDate date, Instant in, Instant out, Instant now) {
        return AttendanceRules.evaluate(ScheduleResolver.resolve(HOURS, List.of(), date), T, date, RIYADH, in, out, now);
    }

    static AttendanceRules.Day sunday(Instant in, Instant out, String now) { return day(SUNDAY, in, out, at(now)); }

    @Test void a_check_in_inside_the_grace_is_on_time_and_one_minute_after_is_late_with_the_minutes_after_start() {
        assertThat(sunday(at("06:52:00"), null, "09:00").lateMinutes()).isNull();
        assertThat(sunday(at("07:10:59"), null, "09:00").lateMinutes()).as("ten minutes and 59 s is still ten").isNull();
        var late = sunday(at("07:11:00"), null, "09:00");
        assertThat(late.lateMinutes()).isEqualTo(11);
        assertThat(late.due()).contains(Event.LATE).doesNotContain(Event.START_REMINDER, Event.NOT_ARRIVED);
    }

    @Test void with_no_check_in_she_is_reminded_at_start_not_arrived_at_the_threshold_and_absent_at_the_end() {
        assertThat(sunday(null, null, "06:59:59").due()).isEmpty();
        assertThat(sunday(null, null, "07:00").due()).containsExactly(Event.START_REMINDER);
        var before = sunday(null, null, "07:59:59");
        assertThat(before.notArrived()).isFalse();
        var notArrived = sunday(null, null, "08:00");
        assertThat(notArrived.notArrived()).isTrue();
        assertThat(notArrived.due()).containsExactlyInAnyOrder(Event.START_REMINDER, Event.NOT_ARRIVED);
        var absent = sunday(null, null, "14:00");
        assertThat(absent.absent()).isTrue();
        assertThat(absent.due()).contains(Event.ABSENT);
        assertThat(sunday(null, null, "13:59").absent()).isFalse();
    }

    @Test void an_early_check_out_is_early_leave_with_the_minutes_before_the_end() {
        var early = sunday(at("06:55"), at("13:20"), "13:21");
        assertThat(early.earlyLeaveMinutes()).isEqualTo(40);
        assertThat(early.due()).containsExactly(Event.EARLY_LEAVE);
        assertThat(sunday(at("06:55"), at("14:00"), "14:01").earlyLeaveMinutes()).as("at the end is not early").isNull();
        assertThat(sunday(at("06:55"), at("13:59:30"), "14:01").earlyLeaveMinutes()).as("half a minute is not a minute").isNull();
    }

    @Test void a_day_can_be_late_and_early_leave_at_once() {
        var both = sunday(at("07:30"), at("12:00"), "12:01");
        assertThat(both.lateMinutes()).isEqualTo(30);
        assertThat(both.earlyLeaveMinutes()).isEqualTo(120);
        assertThat(both.due()).containsExactlyInAnyOrder(Event.LATE, Event.EARLY_LEAVE);
    }

    @Test void a_missing_check_out_reminds_her_at_the_end_and_is_missing_at_end_plus_the_threshold() {
        assertThat(sunday(at("06:55"), null, "13:59").due()).isEmpty();
        assertThat(sunday(at("06:55"), null, "14:00").due()).containsExactly(Event.END_REMINDER);
        var missing = sunday(at("06:55"), null, "15:00");
        assertThat(missing.missingCheckout()).isTrue();
        assertThat(missing.due()).containsExactlyInAnyOrder(Event.END_REMINDER, Event.MISSING_CHECKOUT);
        assertThat(sunday(at("06:55"), null, "14:59:59").missingCheckout()).isFalse();
    }

    @Test void holidays_and_weekends_have_no_statuses_and_nothing_is_due() {
        var friday = LocalDate.of(2026, 10, 16);
        var none = day(friday, null, null, friday.atTime(15, 0).atZone(RIYADH).toInstant());
        assertThat(none.due()).isEmpty();
        assertThat(none.absent()).isFalse();
        var holiday = AttendanceRules.evaluate(
                ScheduleResolver.resolve(HOURS, List.of(new ScheduleResolver.Holiday("National Day", SUNDAY, SUNDAY)), SUNDAY),
                T, SUNDAY, RIYADH, null, null, at("15:00"));
        assertThat(holiday.due()).isEmpty();
    }

    @Test void ramadan_hours_are_used_inside_the_ramadan_range() {
        var ramadan = LocalDate.of(2027, 2, 14);                                         // a Sunday in the range
        Instant nineFive = ramadan.atTime(9, 5).atZone(RIYADH).toInstant();
        var day = day(ramadan, nineFive, null, ramadan.atTime(10, 0).atZone(RIYADH).toInstant());
        assertThat(day.lateMinutes()).as("09:05 is inside a 09:00 start's grace").isNull();
        var notYet = day(ramadan, null, null, ramadan.atTime(8, 30).atZone(RIYADH).toInstant());
        assertThat(notYet.due()).as("07:00 means nothing in Ramadan").isEmpty();
        var absent = day(ramadan, null, null, ramadan.atTime(13, 0).atZone(RIYADH).toInstant());
        assertThat(absent.absent()).as("the Ramadan day ends at 13:00").isTrue();
    }

    // ---------------------------------------------------------------- feature 34: leave and permission windows

    @Test void a_day_of_approved_leave_has_no_status_and_nothing_due() {
        var leave = AttendanceRules.evaluate(ScheduleResolver.excused(ScheduleResolver.resolve(HOURS, List.of(), SUNDAY),
                new Excuses.DayExcuse(true, "ANNUAL", List.of())), T, SUNDAY, RIYADH, null, null, at("15:00"));
        assertThat(leave.absent()).as("never absent on a day of leave").isFalse();
        assertThat(leave.notArrived()).isFalse();
        assertThat(leave.due()).isEmpty();
        assertThat(leave.excusedMinutes()).isZero();
    }

    @Test void a_permission_window_excuses_the_lateness_inside_it_and_leaves_the_rest_late() {
        // The spec's case: 07:00–09:00 approved, in at 08:30 — ninety minutes, all of them excused.
        var onTime = withWindow("07:00", "09:00", at("08:30"), null, "09:00");
        assertThat(onTime.lateMinutes()).isNull();
        assertThat(onTime.excusedMinutes()).isEqualTo(90);
        assertThat(onTime.due()).doesNotContain(Event.LATE);

        // In at 09:40: 160 minutes after the start, 120 of which the window covered.
        var late = withWindow("07:00", "09:00", at("09:40"), null, "10:00");
        assertThat(late.lateMinutes()).isEqualTo(40);
        assertThat(late.excusedMinutes()).isEqualTo(120);

        // A window that does not reach the start excuses only what it covers of the lateness.
        var afternoon = withWindow("12:00", "13:00", at("07:30"), null, "09:00");
        assertThat(afternoon.lateMinutes()).as("nothing of the morning was excused").isEqualTo(30);
        assertThat(afternoon.excusedMinutes()).isZero();
    }

    @Test void a_permission_window_excuses_an_early_leave_inside_it_and_not_one_outside_it() {
        var excused = withWindow("13:00", "14:00", at("06:55"), at("13:00"), "15:00");
        assertThat(excused.earlyLeaveMinutes()).as("the whole hour was hers").isNull();
        assertThat(excused.excusedMinutes()).isEqualTo(60);

        var partly = withWindow("13:30", "14:00", at("06:55"), at("13:00"), "15:00");
        assertThat(partly.earlyLeaveMinutes()).isEqualTo(30);
        assertThat(partly.excusedMinutes()).isEqualTo(30);

        var untouched = withWindow("07:00", "08:00", at("06:55"), at("13:00"), "15:00");
        assertThat(untouched.earlyLeaveMinutes()).as("a morning window says nothing about the afternoon").isEqualTo(60);
        assertThat(untouched.excusedMinutes()).isZero();
    }

    @Test void a_permission_window_moves_the_not_arrived_threshold_and_never_the_end_of_the_day() {
        // 07:00–09:00 approved and nothing recorded: at 09:30 she is half an hour into her own time, not an hour late.
        var inside = withWindow("07:00", "09:00", null, null, "09:30");
        assertThat(inside.notArrived()).as("the hour counts from the end of the window").isFalse();
        assertThat(inside.excusedMinutes()).isEqualTo(120);
        assertThat(withWindow("07:00", "09:00", null, null, "10:01").notArrived()).isTrue();
        // A window of two hours does not excuse a whole day: the end of the day is still absent.
        assertThat(withWindow("07:00", "09:00", null, null, "14:00").absent()).isTrue();
    }

    @Test void two_overlapping_windows_are_counted_once() {
        assertThat(AttendanceRules.overlapMinutes(List.of(window("07:00", "09:00"), window("08:00", "10:00")),
                LocalTime.of(7, 0), LocalTime.of(10, 0))).isEqualTo(180);
        assertThat(AttendanceRules.overlapMinutes(List.of(window("07:00", "08:00"), window("09:00", "10:00")),
                LocalTime.of(7, 0), LocalTime.of(10, 0))).as("a gap between them counts for nothing").isEqualTo(120);
        assertThat(AttendanceRules.overlapMinutes(List.of(), LocalTime.of(7, 0), LocalTime.of(10, 0))).isZero();
        assertThat(AttendanceRules.overlapMinutes(List.of(window("07:00", "09:00")),
                LocalTime.of(9, 0), LocalTime.of(9, 0))).as("an empty span").isZero();
    }

    private static Excuses.Window window(String from, String to) {
        return new Excuses.Window(LocalTime.parse(from), LocalTime.parse(to));
    }

    private static AttendanceRules.Day withWindow(String from, String to, Instant in, Instant out, String now) {
        var expected = ScheduleResolver.excused(ScheduleResolver.resolve(HOURS, List.of(), SUNDAY),
                new Excuses.DayExcuse(false, null, List.of(window(from, to))));
        return AttendanceRules.evaluate(expected, T, SUNDAY, RIYADH, in, out, at(now));
    }

    @Test void the_rules_read_the_schools_clock_not_utc() {
        // 04:00Z is 07:00 in Riyadh: the start reminder is due although it is four in the morning in UTC.
        assertThat(day(SUNDAY, null, null, Instant.parse("2026-10-18T04:00:00Z")).due()).containsExactly(Event.START_REMINDER);
        // 03:59Z on the same date is still 06:59 in Riyadh: nothing yet.
        assertThat(day(SUNDAY, null, null, Instant.parse("2026-10-18T03:59:00Z")).due()).isEmpty();
        // 11:00Z is 14:00: the end of the working day, so no check-in means absent.
        assertThat(day(SUNDAY, null, null, Instant.parse("2026-10-18T11:00:00Z")).absent()).isTrue();
    }
}
