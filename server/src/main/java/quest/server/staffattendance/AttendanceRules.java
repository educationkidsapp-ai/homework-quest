package quest.server.staffattendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
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
 * A day can carry more than one (late and early leave). A holiday, a day off or — since feature 34 — a day of
 * approved leave ({@link ScheduleResolver.Kind#LEAVE}) has no statuses and nothing is due. Minutes are whole minutes,
 * truncated: 07:10:59 is ten minutes after 07:00, which is still inside a ten-minute grace.
 *
 * <p><strong>Feature 34's permissions.</strong> {@link ScheduleResolver.Expected#permissions()} carries the approved
 * windows of the day, and the minutes of her absence that fall inside one are <em>excused</em>: they are subtracted
 * before the day is called late or an early leave, and they are reported as {@link Day#excusedMinutes()}. A
 * permission from 07:00 to 09:00 on a 07:00–14:00 day turns an 08:30 check-in from 90 minutes late into on time with
 * 90 excused minutes; a check-in at 09:40 is late by the 40 minutes the window did not cover. The same subtraction
 * moves the not-arrived threshold, so a manager who approved the permission is not told she has not arrived during
 * it; the end of the day is not moved, because a window of a few hours does not excuse a whole day's absence.
 */
public final class AttendanceRules {
    private AttendanceRules() {}

    /** Everything that can fall due on a working day, in the order the day reaches them. */
    public enum Event { START_REMINDER, LATE, NOT_ARRIVED, END_REMINDER, EARLY_LEAVE, MISSING_CHECKOUT, ABSENT }

    public record Thresholds(int graceMinutes, int notArrivedMinutes, int missingCheckOutMinutes) {}

    /** The day's statuses so far, and the events that are due by now. Null minutes mean "no". */
    public record Day(Integer lateMinutes, boolean notArrived, boolean absent, Integer earlyLeaveMinutes,
                      boolean missingCheckout, int excusedMinutes, Set<Event> due) {
        /** Feature 16's shape, with no excused minutes: what every caller built before feature 34. */
        public Day(Integer lateMinutes, boolean notArrived, boolean absent, Integer earlyLeaveMinutes,
                   boolean missingCheckout, Set<Event> due) {
            this(lateMinutes, notArrived, absent, earlyLeaveMinutes, missingCheckout, 0, due);
        }

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
        var windows = expected.permissions();
        var due = EnumSet.noneOf(Event.class);
        Integer late = null, early = null;
        boolean notArrived = false, absent = false, missingCheckout = false;
        int excused;

        if (checkIn == null) {
            // Nothing recorded: what a window has excused so far is the part of the day up to now that it covers.
            LocalTime until = earlier(now, end.toInstant()).atZone(zone).toLocalTime();
            excused = overlapMinutes(windows, expected.start(), until);
            if (!now.isBefore(start.plusMinutes(excused).toInstant())) due.add(Event.START_REMINDER);
            if (!now.isBefore(start.plusMinutes(excused + t.notArrivedMinutes()).toInstant())) { notArrived = true; due.add(Event.NOT_ARRIVED); }
            if (!now.isBefore(end.toInstant())) { absent = true; due.add(Event.ABSENT); }
        } else {
            int excusedLate = overlapMinutes(windows, expected.start(), checkIn.atZone(zone).toLocalTime());
            excused = excusedLate;
            late = lateMinutes(expected, t.graceMinutes(), checkIn, zone, excusedLate);
            if (late != null) due.add(Event.LATE);
            if (checkOut != null) {
                long before = Duration.between(checkOut, end.toInstant()).toMinutes();
                if (before > 0) {
                    int excusedEarly = overlapMinutes(windows, checkOut.atZone(zone).toLocalTime(), expected.end());
                    excused += excusedEarly;
                    long net = before - excusedEarly;
                    if (net > 0) { early = (int) net; due.add(Event.EARLY_LEAVE); }
                }
            } else {
                if (!now.isBefore(end.toInstant())) due.add(Event.END_REMINDER);
                if (!now.isBefore(end.plusMinutes(t.missingCheckOutMinutes()).toInstant())) { missingCheckout = true; due.add(Event.MISSING_CHECKOUT); }
            }
        }
        return new Day(late, notArrived, absent, early, missingCheckout, excused, Set.copyOf(due));
    }

    /**
     * Minutes after the expected start when she checked in after start + grace; null when she was on time, and on a
     * day off, a holiday or a day of leave, which is never late. Feature 15's success message and "My record" use the
     * same rule, and feature 34's permission windows are subtracted here too.
     */
    public static Integer lateMinutes(ScheduleResolver.Expected expected, int graceMinutes, Instant checkIn, ZoneId zone) {
        if (expected.kind() != ScheduleResolver.Kind.WORK || expected.start() == null) return null;
        return lateMinutes(expected, graceMinutes, checkIn, zone,
                overlapMinutes(expected.permissions(), expected.start(), checkIn.atZone(zone).toLocalTime()));
    }

    /** The same with the excused minutes already measured, so {@link #evaluate} counts the overlap once. */
    private static Integer lateMinutes(ScheduleResolver.Expected expected, int graceMinutes, Instant checkIn, ZoneId zone,
                                       int excusedMinutes) {
        if (expected.kind() != ScheduleResolver.Kind.WORK || expected.start() == null) return null;
        long after = Duration.between(expected.start(), checkIn.atZone(zone).toLocalTime()).toMinutes() - excusedMinutes;
        return after > graceMinutes ? (int) after : null;
    }

    /**
     * Feature 34: how many whole minutes of {@code [from, to)} the windows cover, counting an overlap between two
     * windows once. Zero when there is no window, which is every day before this feature and most days after it.
     */
    static int overlapMinutes(List<Excuses.Window> windows, LocalTime from, LocalTime to) {
        if (windows == null || windows.isEmpty() || from == null || to == null || !from.isBefore(to)) return 0;
        var sorted = new java.util.ArrayList<>(windows);
        sorted.sort(java.util.Comparator.comparing(Excuses.Window::from));
        int total = 0;
        LocalTime cursor = from;
        for (var window : sorted) {
            LocalTime begin = max(window.from(), cursor), finish = min(window.to(), to);
            if (!begin.isBefore(finish)) continue;                               // nothing left of the cursor to count
            total += (int) Duration.between(begin, finish).toMinutes();
            cursor = finish;
            if (!cursor.isBefore(to)) break;
        }
        return total;
    }

    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static LocalTime max(LocalTime a, LocalTime b) { return a.isAfter(b) ? a : b; }
    private static LocalTime min(LocalTime a, LocalTime b) { return a.isBefore(b) ? a : b; }
}
