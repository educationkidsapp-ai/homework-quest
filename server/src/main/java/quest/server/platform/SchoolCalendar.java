package quest.server.platform;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import quest.server.config.ApiException;
import quest.server.tenancy.SchoolRepository;

/**
 * Which days a school teaches on and where its day turns over (V7's `school_week_json` / `timezone`, N2.1).
 *
 * <p>Until now "the school week is Sunday–Thursday" was a constant in {@link quest.server.teacher.TeacherCalendarService}
 * with a note saying to lift it when the first Monday–Friday school arrived. The teacher's week grid is that moment:
 * §4's columns <em>are</em> the school week, so a grid that assumed five Gulf days would be wrong for the school
 * rather than merely un-configurable. The days come from the platform row and, when a school overrides them, from
 * the school's own — school → platform → {@link #DEFAULT_WEEK}, each step used only when the one before it is
 * absent or empty.
 *
 * <p><strong>The zone decides one thing:</strong> what "today" is. A teacher in Riyadh opening her week at 00:30
 * must see the new day, not the server's UTC yesterday, so every "today" in the teacher screens comes from
 * {@link #today(String)} rather than from {@code LocalDate.now()}.
 *
 * <p>At most one query per call — the school row; `schools` is not a tenant table and has no filter, and the
 * platform row comes from {@link PlatformSettingsService}'s cache.
 */
@Service
public class SchoolCalendar {
    /** The Gulf week `V7__sections.sql` seeds and every school runs until one says otherwise. */
    public static final List<DayOfWeek> DEFAULT_WEEK =
            List.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Pattern QUOTED = Pattern.compile("\"([A-Za-z]+)\"");

    private final PlatformSettingsService platform; private final SchoolRepository schools;

    public SchoolCalendar(PlatformSettingsService platform, SchoolRepository schools) {
        this.platform = platform; this.schools = schools;
    }

    /** The week one school runs, with its zone. A null `schoolId` is the platform's own (an Admin with no scope). */
    public Week of(String schoolId) {
        var school = schoolId == null ? null : schools.findById(schoolId).orElse(null);
        var days = school == null ? List.<DayOfWeek>of() : parseWeek(school.getSchoolWeekJson());
        if (days.isEmpty()) days = platform.schoolWeekDays();
        if (days.isEmpty()) days = DEFAULT_WEEK;
        String zone = school != null && school.getTimezone() != null && !school.getTimezone().isBlank()
                ? school.getTimezone() : platform.timezoneId();
        return new Week(days, zoneOrUtc(zone));
    }

    /** Today in the school's zone — never the server's. */
    public LocalDate today(String schoolId) { return LocalDate.now(of(schoolId).zone()); }

    /**
     * One school's teaching days, in the order they run, and the zone its dates are read in.
     *
     * <p>{@link #startOf} is what makes `GET /teacher/week?start=` forgiving: any day of a week — a Wednesday, or a
     * weekend day between two weeks — answers that week's first teaching day, so the dashboard's prev/next may add
     * or subtract seven days without knowing where the week begins.
     */
    public record Week(List<DayOfWeek> days, ZoneId zone) {
        public Week { days = List.copyOf(days); }

        public boolean isSchoolDay(LocalDate date) { return days.contains(date.getDayOfWeek()); }

        /** The first teaching day of the week `date` falls in: walk back at most seven days to the week's head. */
        public LocalDate startOf(LocalDate date) {
            var first = days.getFirst();
            LocalDate d = date;
            for (int i = 0; i < 7 && d.getDayOfWeek() != first; i++) d = d.minusDays(1);
            return d;
        }

        /** The teaching days of the week beginning at `start`, in order — never more than seven dates. */
        public List<LocalDate> datesFrom(LocalDate start) {
            var dates = new ArrayList<LocalDate>(days.size());
            for (int i = 0; i < 7; i++) { var d = start.plusDays(i); if (isSchoolDay(d)) dates.add(d); }
            return List.copyOf(dates);
        }
    }

    // ---------------------------------------------------------------- parsing and validation

    /**
     * `["SUN","MON"]` → the days, in that order, duplicates dropped. A blank, broken or unknown value is an empty
     * list rather than an exception: this reads what is already in the database, and a row nobody can fix must not
     * be able to take the week grid down. {@link #weekJson} is where a bad value is refused, on the way in.
     */
    public static List<DayOfWeek> parseWeek(String storedJson) {
        if (storedJson == null || storedJson.isBlank()) return List.of();
        var names = new ArrayList<String>();
        Matcher m = QUOTED.matcher(storedJson);
        while (m.find()) names.add(m.group(1));
        try { return days(names); } catch (ApiException e) { return List.of(); }
    }

    /** The stored form of a week the Admin submitted, validated: an unknown day name is a 400, not a silent drop. */
    public static String weekJson(List<String> names) {
        var days = days(names);
        if (days.isEmpty()) throw ApiException.badRequest("The school week needs at least one teaching day.");
        return days.stream().map(d -> "\"" + d.name().substring(0, 3) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** An IANA zone id, or 400 — `ZoneId` is the only authority on what exists. */
    public static ZoneId zoneId(String id) {
        try { return ZoneId.of(id.trim()); }
        catch (java.time.DateTimeException e) { throw ApiException.badRequest("`" + id + "` is not an IANA timezone, such as Asia/Riyadh."); }
    }

    private static ZoneId zoneOrUtc(String id) {
        if (id == null || id.isBlank()) return UTC;
        try { return ZoneId.of(id.trim()); } catch (java.time.DateTimeException e) { return UTC; }
    }

    /** `"sun"`, `"SUN"` and `"SUNDAY"` all mean Sunday; anything shorter or unknown names no day and is refused. */
    private static List<DayOfWeek> days(List<String> names) {
        if (names == null) return List.of();
        var days = new LinkedHashSet<DayOfWeek>();
        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim().toUpperCase(Locale.ROOT);
            DayOfWeek match = null;
            if (name.length() >= 3) for (DayOfWeek d : DayOfWeek.values()) if (d.name().startsWith(name)) { match = d; break; }
            if (match == null) throw ApiException.badRequest("`" + raw + "` is not a day of the week (use SUN … SAT).");
            days.add(match);
        }
        return List.copyOf(days);
    }
}
