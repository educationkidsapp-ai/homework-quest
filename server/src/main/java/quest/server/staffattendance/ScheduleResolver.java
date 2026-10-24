package quest.server.staffattendance;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * What one day asks of one staff category (feature 14): a holiday, a day off, or work between a start and an end —
 * the Ramadan hours when the day is inside the category's Ramadan range. Features 15 and 16 ask this before they
 * call anyone late or absent.
 *
 * <p>The order is the spec's: a holiday wins over everything, then the working days, then Ramadan over the normal
 * hours. {@link #resolve} is pure so it can be tested without a database; {@link #expected} loads one school's rows.
 *
 * <p><strong>Feature 34.</strong> A working day on which the employee holds approved leave becomes
 * {@link Kind#LEAVE}: nothing is expected of her, no status is written and no alert is sent, exactly as on a holiday,
 * and the board and the report say "on leave" rather than "day off". Her approved permission windows ride along on
 * {@link Expected#permissions()} — {@link AttendanceRules#evaluate} is their only reader — so the five places that
 * compute a day's status all see the same excuses without any of them asking for them. That needs the employee, which
 * is why {@link #expected(String, String, LocalDate, String, String)} exists beside the three-argument form the pure
 * paths still use.
 */
@Service
public class ScheduleResolver {
    public enum Kind { WORK, DAY_OFF, HOLIDAY, LEAVE }

    /**
     * The day's expectation. {@code start} and {@code end} are null unless {@code kind} is WORK; {@code leaveType} is
     * set only on {@link Kind#LEAVE}, and {@code permissions} is feature 34's approved windows on the day (empty when
     * there are none, and never read on a day that expects nothing).
     */
    public record Expected(Kind kind, LocalTime start, LocalTime end, boolean ramadan, String holidayName,
                           String leaveType, List<Excuses.Window> permissions) {
        /** The shape features 14–18 built: no leave, no permission. Keeps every existing caller and test compiling. */
        public Expected(Kind kind, LocalTime start, LocalTime end, boolean ramadan, String holidayName) {
            this(kind, start, end, ramadan, holidayName, null, List.of());
        }

        static Expected holiday(String name) { return new Expected(Kind.HOLIDAY, null, null, false, name); }
        static Expected dayOff() { return new Expected(Kind.DAY_OFF, null, null, false, null); }
        static Expected leave(String leaveType) { return new Expected(Kind.LEAVE, null, null, false, null, leaveType, List.of()); }

        Expected with(List<Excuses.Window> windows) {
            return windows.isEmpty() ? this : new Expected(kind, start, end, ramadan, holidayName, leaveType, List.copyOf(windows));
        }
    }

    /** The parts of a schedule {@link #resolve} reads, so a test can build one without an entity. */
    public record Hours(Set<DayOfWeek> weekdays, LocalTime start, LocalTime end,
                        LocalDate ramadanFrom, LocalDate ramadanTo, LocalTime ramadanStart, LocalTime ramadanEnd) {}

    public record Holiday(String name, LocalDate from, LocalDate to) {}

    private final AttendanceSettingsService settings; private final Excuses excuses;

    public ScheduleResolver(AttendanceSettingsService settings, Excuses excuses) {
        this.settings = settings; this.excuses = excuses;
    }

    /**
     * The expectation for one school, category and date, from the school's saved settings or the defaults —
     * <strong>without</strong> feature 34's excuses, which need the employee. A leave request counts its school days
     * with this form, so days she is already on leave for are still her schedule's working days and the overlap rule,
     * not a shrinking day count, is what refuses a second request.
     */
    public Expected expected(String schoolId, String category, LocalDate date) {
        return resolve(settings.hours(schoolId, category), settings.holidays(schoolId), date);
    }

    /**
     * Feature 34: the same, narrowed by what this employee is excused from on that date — approved leave turns a
     * working day into {@link Kind#LEAVE}, and her approved permission windows are carried for
     * {@link AttendanceRules#evaluate}.
     */
    public Expected expected(String schoolId, String category, LocalDate date, String staffKind, String staffId) {
        return excused(expected(schoolId, category, date), excuses.of(schoolId, staffKind, staffId, date));
    }

    /** The pure half of the above, so the board excuses a month's days from rows it has already loaded. */
    public static Expected excused(Expected expected, Excuses.DayExcuse excuse) {
        if (expected.kind() != Kind.WORK) return expected;
        if (excuse.onLeave()) return Expected.leave(excuse.leaveType());
        return expected.with(excuse.permissions());
    }

    public static Expected resolve(Hours hours, List<Holiday> holidays, LocalDate date) {
        for (var h : holidays) if (!date.isBefore(h.from()) && !date.isAfter(h.to())) return Expected.holiday(h.name());
        if (!hours.weekdays().contains(date.getDayOfWeek())) return Expected.dayOff();
        boolean ramadan = hours.ramadanFrom() != null && hours.ramadanTo() != null
                && !date.isBefore(hours.ramadanFrom()) && !date.isAfter(hours.ramadanTo());
        return ramadan
                ? new Expected(Kind.WORK, hours.ramadanStart(), hours.ramadanEnd(), true, null)
                : new Expected(Kind.WORK, hours.start(), hours.end(), false, null);
    }
}
