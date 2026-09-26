package quest.server.management;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.classes.SectionService;
import quest.server.config.ApiException;
import quest.server.management.Entities.StaffAttendanceEntity;
import quest.server.platform.SchoolCalendar;
import quest.server.tenancy.ManagerScope;
import quest.server.tenancy.TenantContext;

/**
 * RM5 (DR7): <strong>the staff register</strong> — the one thing a department manager writes about her people rather
 * than reads about them. Her teachers ({@link ManagerScope#teachersOf}) and her coordinators
 * ({@link ManagerScope#coordinatorsOf}) for a day, the four statuses, and the month behind them.
 *
 * <p><strong>Who is on the roster is never a request parameter.</strong> The list comes from her own department, so
 * marking somebody is refused by the same rule that hides her: a `userId` the department does not hold is a 403
 * (403 rather than 404 because the person exists and belongs to the other manager, and the register is per person, not
 * per section — a teacher of two grades is present once, not once per class).
 *
 * <p><strong>The day rule, in one place.</strong> A day may be marked only when it is a teaching day of the school
 * ({@link SchoolCalendar.Week#isSchoolDay}) and is not after today in the school's own zone — today included, so a
 * register is taken on the morning it belongs to. Both refusals are 400: the day is part of the request, and the
 * client is expected to offer only days the roster already returned with `editable` true. Reading is not restricted
 * that way; a weekend answers an empty register with `schoolDay` false, because a screen that cannot show what it
 * asked for is worse than one that shows nothing was expected.
 *
 * <p><strong>One statement per screen.</strong> The roster and the upsert read the day for the whole department at
 * once and the summary counts in the database, grouped by person and status — never a query per teacher.
 */
@Service
public class StaffAttendanceService {
    /** The four statuses V22's `CHECK` constraint allows, as the wire spells them. */
    static final List<String> STATUSES = List.of("present", "absent", "late", "leave");
    /** The longest note a manager may leave beside a status. */
    static final int MAX_NOTE = 500;
    /** The longest history window, the cap `/management/classes/{id}/attendance` already uses. */
    static final int MAX_WINDOW_DAYS = ManagementService.MAX_WINDOW_DAYS;

    private final ManagerScope scope; private final StaffAttendanceRepository rows;
    private final SchoolCalendar calendar; private final TenantContext tenant;

    public StaffAttendanceService(ManagerScope scope, StaffAttendanceRepository rows, SchoolCalendar calendar,
                                  TenantContext tenant) {
        this.scope = scope; this.rows = rows; this.calendar = calendar; this.tenant = tenant;
    }

    /** A member of the department, and which of the two roles put her on the register. */
    private record Person(UserEntity user, String role) {}

    // ---------------------------------------------------------------- GET /management/staff-attendance

    /** Her teachers and coordinators for one day, with that day's status — absent from the row while unmarked. */
    @Transactional(readOnly = true)
    public ManagementDto.StaffAttendanceDay roster(Principals.User caller, String day) {
        var people = department(caller);
        LocalDate today = today();
        LocalDate date = date(day, "day", today);
        return day(date, today, people, marks(people, date));
    }

    // ---------------------------------------------------------------- PUT /management/staff-attendance

    /** Marks a day for some of her people and answers the whole roster back, so the screen needs no second read. */
    @Transactional
    public ManagementDto.StaffAttendanceDay mark(Principals.User caller, String day,
                                                 List<ManagementDto.MarkStaffAttendance> items) {
        var people = department(caller);
        LocalDate today = today();
        LocalDate date = date(day, "day", today);
        var week = calendar.of(tenant.writeSchoolId());
        if (!week.isSchoolDay(date))
            throw ApiException.badRequest(date + " is not a teaching day at this school — the register is taken on teaching days only.");
        if (date.isAfter(today))
            throw ApiException.badRequest(date + " is still to come — a register is taken on the day or after it, never before.");

        var byId = new LinkedHashMap<String, Person>();
        for (var person : people) byId.put(person.user().getId(), person);
        var wanted = new LinkedHashMap<String, ManagementDto.MarkStaffAttendance>();
        for (var item : items == null ? List.<ManagementDto.MarkStaffAttendance>of() : items) {
            var person = byId.get(item.userId());
            if (person == null) throw ApiException.forbidden("That person is not in the department you manage.");
            if (wanted.put(item.userId(), item) != null)
                throw ApiException.badRequest(SectionService.displayName(person.user()) + " is named twice in one register.");
        }

        var existing = marks(people, date);
        var saved = new ArrayList<StaffAttendanceEntity>(wanted.size());
        var now = java.time.Instant.now();
        wanted.forEach((userId, item) -> {
            var row = existing.get(userId);
            if (row == null) {
                row = new StaffAttendanceEntity();
                row.setId(java.util.UUID.randomUUID().toString());
                row.setSchoolId(tenant.writeSchoolId()); row.setUserId(userId); row.setDate(date);
            }
            row.setStatus(status(item.status()));
            row.setNote(note(item.note()));
            row.setMarkedBy(caller.userId()); row.setMarkedAt(now);
            saved.add(row);
        });
        for (var row : rows.saveAll(saved)) existing.put(row.getUserId(), row);
        return day(date, today, people, existing);
    }

    // ---------------------------------------------------------------- GET /management/staff-attendance/summary

    /**
     * A month per person: the four counts, the teaching days nobody marked, and a rate. The window ends at today when
     * the month is the one running, so a month half over is not scored as though its remaining days were missed, and
     * `rate` is null where nothing was marked at all — RM1's rule, so an empty register reads as "no answer".
     */
    @Transactional(readOnly = true)
    public ManagementDto.StaffAttendanceSummary summary(Principals.User caller, String month) {
        var people = department(caller);
        LocalDate today = today();
        YearMonth ym = month(month, today);
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
        if (to.isAfter(today)) to = today;
        var week = calendar.of(tenant.writeSchoolId());
        int schoolDays = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) if (week.isSchoolDay(d)) schoolDays++;

        var counts = new LinkedHashMap<String, Map<String, Integer>>();
        if (!people.isEmpty() && !to.isBefore(from))
            for (var row : rows.countByStatusInWindow(ids(people), from, to))
                counts.computeIfAbsent((String) row[0], k -> new LinkedHashMap<>())
                        .merge((String) row[1], ((Number) row[2]).intValue(), Integer::sum);

        var out = new ArrayList<ManagementDto.StaffAttendanceSummaryRow>(people.size());
        for (var person : people) {
            var mine = counts.getOrDefault(person.user().getId(), Map.of());
            int present = mine.getOrDefault("present", 0), absent = mine.getOrDefault("absent", 0);
            int late = mine.getOrDefault("late", 0), leave = mine.getOrDefault("leave", 0);
            int marked = present + absent + late + leave;
            out.add(new ManagementDto.StaffAttendanceSummaryRow(person.user().getId(), person.user().getEmail(),
                    SectionService.displayName(person.user()), person.role(), present, absent, late, leave,
                    Math.max(0, schoolDays - marked), marked == 0 ? null : rate(present + late, marked)));
        }
        return new ManagementDto.StaffAttendanceSummary(ym.toString(), from, to, schoolDays, List.copyOf(out));
    }

    // ---------------------------------------------------------------- GET /management/staff-attendance/{userId}

    /** One person's marked days, newest first. A person of the other department is refused before anything is read. */
    @Transactional(readOnly = true)
    public ManagementDto.StaffAttendanceHistory history(Principals.User caller, String userId, String from, String to) {
        Person person = null;
        for (var candidate : department(caller)) if (candidate.user().getId().equals(userId)) person = candidate;
        if (person == null) throw ApiException.forbidden("That person is not in the department you manage.");
        LocalDate end = date(to, "to", today()), start = date(from, "from", end.minusDays(MAX_WINDOW_DAYS - 1L));
        if (end.isBefore(start)) throw ApiException.badRequest("`to` is before `from`.");
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end))
            throw ApiException.badRequest("That window is longer than " + MAX_WINDOW_DAYS + " days — ask for a shorter one.");
        var marks = rows.findByUserIdAndDateBetweenOrderByDateDesc(userId, start, end).stream()
                .map(r -> new ManagementDto.StaffAttendanceMark(r.getDate(), r.getStatus(), r.getNote(),
                        r.getMarkedBy(), r.getMarkedAt().toEpochMilli())).toList();
        return new ManagementDto.StaffAttendanceHistory(userId, SectionService.displayName(person.user()),
                person.role(), start, end, marks);
    }

    // ---------------------------------------------------------------- the department, and the day

    /**
     * Everybody on her register: her teachers first, then her coordinators, each already in display-name order. Both
     * lists come from {@link ManagerScope}, so the roster is the department's and nothing about it is a parameter.
     */
    private List<Person> department(Principals.User caller) {
        var out = new ArrayList<Person>();
        for (var teacher : scope.teachersOf(caller)) out.add(new Person(teacher, "TEACHER"));
        for (var coordinator : scope.coordinatorsOf(caller).keySet()) out.add(new Person(coordinator, "COORDINATOR"));
        return List.copyOf(out);
    }

    private static List<String> ids(List<Person> people) { return people.stream().map(p -> p.user().getId()).toList(); }

    /** One day's rows for the whole department, by user id — one statement, whatever the size of the department. */
    private Map<String, StaffAttendanceEntity> marks(List<Person> people, LocalDate date) {
        var byUser = new LinkedHashMap<String, StaffAttendanceEntity>();
        if (people.isEmpty()) return byUser;
        for (var row : rows.findByDateAndUserIdIn(date, ids(people))) byUser.put(row.getUserId(), row);
        return byUser;
    }

    /** The response both the roster and the upsert answer, counted off rows already in hand. */
    private ManagementDto.StaffAttendanceDay day(LocalDate date, LocalDate today, List<Person> people,
                                                 Map<String, StaffAttendanceEntity> marks) {
        var week = calendar.of(tenant.writeSchoolId());
        var out = new ArrayList<ManagementDto.StaffAttendanceRow>(people.size());
        var counts = new LinkedHashMap<String, Integer>();
        for (var person : people) {
            var row = marks.get(person.user().getId());
            if (row != null) counts.merge(row.getStatus(), 1, Integer::sum);
            out.add(new ManagementDto.StaffAttendanceRow(person.user().getId(), person.user().getEmail(),
                    SectionService.displayName(person.user()), person.user().getPhotoUrl(), person.role(),
                    row == null ? null : row.getStatus(), row == null ? null : row.getNote(),
                    row == null ? null : row.getMarkedBy(), row == null ? null : row.getMarkedAt().toEpochMilli()));
        }
        boolean schoolDay = week.isSchoolDay(date);
        return new ManagementDto.StaffAttendanceDay(date, schoolDay, schoolDay && !date.isAfter(today), List.copyOf(out),
                counts.getOrDefault("present", 0), counts.getOrDefault("absent", 0), counts.getOrDefault("late", 0),
                counts.getOrDefault("leave", 0), people.size() - marks.size());
    }

    private LocalDate today() { return calendar.today(tenant.writeSchoolId()); }

    /** `(present + late) / marked`, to one decimal — the formula the student register and RM1's statistics use. */
    private static double rate(int good, int marked) { return Math.round(good * 1000.0 / marked) / 10.0; }

    /** A trimmed note, or null. Longer than {@link #MAX_NOTE} is refused rather than silently cut. */
    private static String note(String value) {
        if (value == null || value.isBlank()) return null;
        String note = value.trim();
        if (note.length() > MAX_NOTE) throw ApiException.badRequest("A note is at most " + MAX_NOTE + " characters.");
        return note;
    }

    private static String status(String value) {
        String status = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(status))
            throw ApiException.badRequest("`" + value + "` is not a status — use " + String.join(", ", STATUSES) + ".");
        return status;
    }

    private static YearMonth month(String value, LocalDate today) {
        if (value == null || value.isBlank()) return YearMonth.from(today);
        try { return YearMonth.parse(value.trim()); }
        catch (java.time.format.DateTimeParseException e) { throw ApiException.badRequest("`month` is not a month — use yyyy-MM."); }
    }

    private static LocalDate date(String value, String field, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value.trim()); }
        catch (java.time.format.DateTimeParseException e) { throw ApiException.badRequest("`" + field + "` is not a date — use yyyy-MM-dd."); }
    }
}
