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
 */
@Service
public class ScheduleResolver {
    public enum Kind { WORK, DAY_OFF, HOLIDAY }

    /** The day's expectation. {@code start} and {@code end} are null unless {@code kind} is WORK. */
    public record Expected(Kind kind, LocalTime start, LocalTime end, boolean ramadan, String holidayName) {
        static Expected holiday(String name) { return new Expected(Kind.HOLIDAY, null, null, false, name); }
        static Expected dayOff() { return new Expected(Kind.DAY_OFF, null, null, false, null); }
    }

    /** The parts of a schedule {@link #resolve} reads, so a test can build one without an entity. */
    public record Hours(Set<DayOfWeek> weekdays, LocalTime start, LocalTime end,
                        LocalDate ramadanFrom, LocalDate ramadanTo, LocalTime ramadanStart, LocalTime ramadanEnd) {}

    public record Holiday(String name, LocalDate from, LocalDate to) {}

    private final AttendanceSettingsService settings;

    public ScheduleResolver(AttendanceSettingsService settings) { this.settings = settings; }

    /** The expectation for one school, category and date, from the school's saved settings or the defaults. */
    public Expected expected(String schoolId, String category, LocalDate date) {
        return resolve(settings.hours(schoolId, category), settings.holidays(schoolId), date);
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
