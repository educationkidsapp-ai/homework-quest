package quest.server.staffattendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Feature 16: what one employee's working day has become, at one moment — the spec's status table, in one place and
 * pure, so every rule and every boundary is tested without a database or a clock.
 *
 * <ul>
 *   <li><strong>On time</strong> at or before start + grace; <strong>late</strong> after it, by the minutes after the
 *       start (not after the grace — "late 12 min" means the day began twelve minutes before she arrived).</li>
 *   <li><strong>Not arrived</strong>: no check-in by start + the not-arrived threshold.</li>
 *   <li><strong>Absent</strong>: no check-in by the end of the working day.</li>
 *   <li><strong>Early leave</strong>: a check-out before the end, by the minutes before it.</li>
 *   <li><strong>Missing check-out</strong>: none by end + the missing-check-out threshold.</li>
 * </ul>
 *
 * A day can carry more than one (late and early leave). A holiday or a day off — {@link ScheduleResolver} has already
 * applied the Ramadan hours — has no statuses and nothing is due. Minutes are whole minutes, truncated: 07:10:59 is ten
 * minutes after 07:00, which is still inside a ten-minute grace.
 */
public final class AttendanceRules {
    private AttendanceRules() {}

    /** Everything that can fall due on a working day, in the order the day reaches them. */
    public enum Event { START_REMINDER, LATE, NOT_ARRIVED, END_REMINDER, EARLY_LEAVE, MISSING_CHECKOUT, ABSENT }

    public record Thresholds(int graceMinutes, int notArrivedMinutes, int missingCheckOutMinutes) {}

    /** The day's statuses so far, and the events that are due by now. Null minutes mean "no". */
    public record Day(Integer lateMinutes, boolean notArrived, boolean absent, Integer earlyLeaveMinutes,
                      boolean missingCheckout, Set<Event> due) {
        static final Day NONE = new Day(null, false, false, null, false, Set.of());
    }

    /**
     * @param date      the working day, in the school's zone
     * @param checkIn   her check-in that day, or null
     * @param checkOut  her latest check-out that day, or null
     * @param now       the server's now
     */
    public static Day evaluate(ScheduleResolver.Expected expected, Thresholds t, LocalDate date, ZoneId zone,
                               Instant checkIn, Instant checkOut, Instant now) {
        if (expected.kind() != ScheduleResolver.Kind.WORK || expected.start() == null || expected.end() == null) return Day.NONE;
        ZonedDateTime start = date.atTime(expected.start()).atZone(zone), end = date.atTime(expected.end()).atZone(zone);
        var due = EnumSet.noneOf(Event.class);
        Integer late = null, early = null;
        boolean notArrived = false, absent = false, missingCheckout = false;

        if (checkIn == null) {
            if (!now.isBefore(start.toInstant())) due.add(Event.START_REMINDER);
            if (!now.isBefore(start.plusMinutes(t.notArrivedMinutes()).toInstant())) { notArrived = true; due.add(Event.NOT_ARRIVED); }
            if (!now.isBefore(end.toInstant())) { absent = true; due.add(Event.ABSENT); }
        } else {
            late = lateMinutes(expected, t.graceMinutes(), checkIn, zone);
            if (late != null) due.add(Event.LATE);
            if (checkOut != null) {
                long before = Duration.between(checkOut, end.toInstant()).toMinutes();
                if (before > 0) { early = (int) before; due.add(Event.EARLY_LEAVE); }
            } else {
                if (!now.isBefore(end.toInstant())) due.add(Event.END_REMINDER);
                if (!now.isBefore(end.plusMinutes(t.missingCheckOutMinutes()).toInstant())) { missingCheckout = true; due.add(Event.MISSING_CHECKOUT); }
            }
        }
        return new Day(late, notArrived, absent, early, missingCheckout, Set.copyOf(due));
    }

    /**
     * Minutes after the expected start when she checked in after start + grace; null when she was on time, and on a
     * day off or a holiday, which is never late. Feature 15's success message and "My record" use the same rule.
     */
    public static Integer lateMinutes(ScheduleResolver.Expected expected, int graceMinutes, Instant checkIn, ZoneId zone) {
        if (expected.kind() != ScheduleResolver.Kind.WORK || expected.start() == null) return null;
        long after = Duration.between(expected.start(), checkIn.atZone(zone).toLocalTime()).toMinutes();
        return after > graceMinutes ? (int) after : null;
    }
}
