package quest.server.staffattendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.staff.CheckInMode;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.management.StaffAttendanceRepository;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.Entities.StaffCheckInEntity;
import quest.server.staffattendance.StaffAttendanceDto.AttendanceBoard;
import quest.server.staffattendance.StaffAttendanceDto.AttendanceReport;
import quest.server.staffattendance.StaffAttendanceDto.BoardAlert;
import quest.server.staffattendance.StaffAttendanceDto.BoardAttempt;
import quest.server.staffattendance.StaffAttendanceDto.BoardCounts;
import quest.server.staffattendance.StaffAttendanceDto.BoardRow;
import quest.server.staffattendance.StaffAttendanceDto.AttendanceCalendarDay;
import quest.server.staffattendance.StaffAttendanceDto.EmployeeDay;
import quest.server.staffattendance.StaffAttendanceDto.ReportRow;
import quest.server.tenancy.TenantContext;

/**
 * Feature 17: the leaders' page — today's board, one employee's day, and the monthly report.
 *
 * <p><strong>One rule.</strong> Every status here comes from {@link AttendanceRules#evaluate} on the check-in records,
 * the rule feature 16's job writes into `staff_attendance_days`. The board evaluates it on read rather than reading the
 * job's rows, so a check-in shows the moment it is recorded instead of at the job's next five-minute run, and the
 * report evaluates each day of the month the same way, so its totals are the daily statuses added up.
 *
 * <p><strong>Who sees whom</strong> is {@link BoardScope}'s. The school is the request's ({@link TenantContext}), and
 * every query names it.
 */
@Service
public class AttendanceBoardService {
    static final String MANUAL = "manual";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter YYYY_MM = DateTimeFormatter.ofPattern("yyyy-MM");

    private final BoardScope scope; private final StaffCheckInRepository checkIns;
    private final StaffCheckInAttemptRepository attempts; private final StaffAttendanceAlertRepository alerts;
    private final StaffAttendanceProfileRepository profiles; private final ScheduleResolver schedules;
    private final AttendanceSettingsService settings; private final DirectManagerResolver managers;
    private final CheckInService records; private final StaffAttendanceRepository manualMarks;
    private final TenantContext tenant; private final SchoolCalendar calendar; private final Clock clock;

    public AttendanceBoardService(BoardScope scope, StaffCheckInRepository checkIns, StaffCheckInAttemptRepository attempts,
                                  StaffAttendanceAlertRepository alerts, StaffAttendanceProfileRepository profiles,
                                  ScheduleResolver schedules, AttendanceSettingsService settings,
                                  DirectManagerResolver managers, CheckInService records,
                                  StaffAttendanceRepository manualMarks, TenantContext tenant, SchoolCalendar calendar,
                                  Clock clock) {
        this.scope = scope; this.checkIns = checkIns; this.attempts = attempts; this.alerts = alerts;
        this.profiles = profiles; this.schedules = schedules; this.settings = settings; this.managers = managers;
        this.records = records; this.manualMarks = manualMarks; this.tenant = tenant; this.calendar = calendar;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- GET /staff-attendance/board

    @Transactional(readOnly = true)
    public AttendanceBoard board(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        var view = scope.of(schoolId, caller);
        ZoneId zone = calendar.of(schoolId).zone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        var thresholds = settings.alerts(schoolId).thresholds();
        var categories = categories(schoolId);
        var byStaff = new HashMap<String, StaffCheckInEntity>();
        for (var row : checkIns.findBySchoolIdAndDay(schoolId, today)) byStaff.put(BoardScope.key(row.getStaffKind(), row.getStaffId()), row);

        var rows = new ArrayList<BoardRow>();
        for (var e : view.employees()) {
            String category = category(categories, e);
            var expected = schedules.expected(schoolId, category, today);
            var row = byStaff.get(BoardScope.key(e.kind(), e.id()));
            Instant in = row == null ? null : row.getCheckInAt(), out = row == null ? null : row.getCheckOutAt();
            var day = AttendanceRules.evaluate(expected, thresholds, today, zone, in, out, now);
            rows.add(new BoardRow(e.kind(), e.id(), e.fullName(), e.role(), category, academies(schoolId, e),
                    time(in, zone), time(out, zone), status(expected, row, day, today, zone, now),
                    day.lateMinutes(), day.missingCheckout(), row == null ? null : row.getMode()));
        }
        return new AttendanceBoard(today.toString(), zone.getId(), view.scope(), counts(rows), rows);
    }

    /** The cards. Each is the number of rows its filter shows; `noCheckOut` is a flag, so it overlaps the others. */
    static BoardCounts counts(List<BoardRow> rows) {
        int present = 0, late = 0, notArrived = 0, remote = 0, noCheckOut = 0;
        for (var r : rows) {
            switch (r.status()) {
                case StaffAttendanceDto.PRESENT -> present++;
                case StaffAttendanceDto.LATE -> late++;
                case StaffAttendanceDto.NOT_ARRIVED -> notArrived++;
                case StaffAttendanceDto.REMOTE -> remote++;
                default -> { }
            }
            if (r.noCheckOut()) noCheckOut++;
        }
        return new BoardCounts(present, late, notArrived, remote, noCheckOut);
    }

    /**
     * A row's one status today. Checked in: remote when the record says so, else late or present. Not checked in on a
     * working day: upcoming before the start, not arrived from the start, absent from the end. A day off or a holiday
     * with no check-in says so; one with a check-in counts as present, because she was there.
     */
    static String status(ScheduleResolver.Expected expected, StaffCheckInEntity row, AttendanceRules.Day day,
                         LocalDate date, ZoneId zone, Instant now) {
        if (row != null) {
            if (CheckInMode.REMOTE.equals(row.getMode())) return StaffAttendanceDto.REMOTE;
            return day.lateMinutes() != null ? StaffAttendanceDto.LATE : StaffAttendanceDto.PRESENT;
        }
        return switch (expected.kind()) {
            case HOLIDAY -> StaffAttendanceDto.HOLIDAY;
            case DAY_OFF -> StaffAttendanceDto.OFF;
            case WORK -> {
                if (now.isBefore(date.atTime(expected.start()).atZone(zone).toInstant())) yield StaffAttendanceDto.UPCOMING;
                yield day.absent() ? StaffAttendanceDto.ABSENT : StaffAttendanceDto.NOT_ARRIVED;
            }
        };
    }

    // ---------------------------------------------------------------- GET /staff-attendance/employees/{kind}/{id}

    /**
     * One employee's day — every attempt with its refusal reason, every notification feature 16 sent about it — and
     * her month around it. Days the department manager marked by hand before the switch (V21) fill the calendar where
     * there is no check-in, with mode {@value #MANUAL}: the history stays, and nothing new is marked that way.
     */
    @Transactional(readOnly = true)
    public EmployeeDay employeeDay(Principals.User caller, String kind, String id, String dayParam) {
        String schoolId = tenant.writeSchoolId();
        var employee = scope.one(schoolId, caller, kind, id);
        ZoneId zone = calendar.of(schoolId).zone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate day = date(dayParam, today);

        var dayAttempts = attempts.findBySchoolIdAndStaffKindAndStaffIdAndServerAtBetweenOrderByServerAtAsc(
                schoolId, kind, id, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1))
                .stream().map(a -> new BoardAttempt(time(a.getServerAt(), zone), a.getAction(), a.getResult(), a.getReason(), a.getDistanceM()))
                .toList();
        var sent = alerts.findBySchoolIdAndStaffKindAndStaffIdAndDayOrderBySentAtAsc(schoolId, kind, id, day)
                .stream().map(a -> new BoardAlert(a.getType(), time(a.getSentAt(), zone))).toList();

        YearMonth ym = YearMonth.from(day);
        var manual = new HashMap<LocalDate, String>();
        if (StaffAttendanceDto.KIND_USER.equals(kind))
            for (var mark : manualMarks.findByUserIdAndDateBetweenOrderByDateDesc(id, ym.atDay(1), ym.atEndOfMonth()))
                if (schoolId.equals(mark.getSchoolId())) manual.put(mark.getDate(), mark.getStatus());
        var days = new ArrayList<AttendanceCalendarDay>();
        for (var d : records.calendar(schoolId, employee, ym, today, zone)) {
            String mark = d.getMode() == null ? manual.get(LocalDate.parse(d.getDate())) : null;
            days.add(mark == null
                    ? new AttendanceCalendarDay(d.getDate(), d.getCheckIn(), d.getCheckOut(), d.getStatus(), d.getLateMinutes(), d.getMode())
                    : new AttendanceCalendarDay(d.getDate(), null, null, manualStatus(mark), null, MANUAL));
        }
        return new EmployeeDay(employee.kind(), employee.id(), employee.fullName(), category(categories(schoolId), employee),
                day.toString(), dayAttempts, sent, ym.format(YYYY_MM), days);
    }

    /** V21's statuses in the calendar's words; `leave` has no counterpart there and keeps its own. */
    private static String manualStatus(String mark) {
        return switch (mark) {
            case "present" -> CheckInService.ON_TIME;
            case "late" -> CheckInService.LATE;
            case "absent" -> CheckInService.ABSENT;
            default -> mark.toUpperCase(Locale.ROOT);
        };
    }

    // ---------------------------------------------------------------- GET /staff-attendance/report

    @Transactional(readOnly = true)
    public AttendanceReport report(Principals.User caller, String monthParam) {
        String schoolId = tenant.writeSchoolId();
        var view = scope.of(schoolId, caller);
        ZoneId zone = calendar.of(schoolId).zone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        YearMonth ym = month(monthParam, today);
        var thresholds = settings.alerts(schoolId).thresholds();
        var categories = categories(schoolId);

        var byStaff = new HashMap<String, Map<LocalDate, StaffCheckInEntity>>();
        for (var row : checkIns.findBySchoolIdAndDayBetween(schoolId, ym.atDay(1), ym.atEndOfMonth()))
            byStaff.computeIfAbsent(BoardScope.key(row.getStaffKind(), row.getStaffId()), k -> new HashMap<>()).put(row.getDay(), row);

        var rows = new ArrayList<ReportRow>();
        for (var e : view.employees()) {
            String category = category(categories, e);
            var mine = byStaff.getOrDefault(BoardScope.key(e.kind(), e.id()), Map.of());
            var days = new ArrayList<DayInput>();
            for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()) && !d.isAfter(today); d = d.plusDays(1)) {
                var row = mine.get(d);
                days.add(new DayInput(d, schedules.expected(schoolId, category, d),
                        row == null ? null : row.getCheckInAt(), row == null ? null : row.getCheckOutAt(),
                        row == null ? null : row.getMode()));
            }
            var t = totals(days, thresholds, zone, now);
            rows.add(new ReportRow(e.kind(), e.id(), e.fullName(), category, academies(schoolId, e), t.workingDays(),
                    t.presentOnsite(), t.presentRemote(), t.lateCount(), t.lateMinutes(), t.absentDays(),
                    t.earlyLeaveMinutes(), t.missingCheckOuts(), t.corrections()));
        }
        return new AttendanceReport(ym.format(YYYY_MM), view.scope(), rows);
    }

    /** One day of one employee's month, as the report reads it. */
    record DayInput(LocalDate date, ScheduleResolver.Expected expected, Instant checkIn, Instant checkOut, String mode) {}

    record Totals(int workingDays, int presentOnsite, int presentRemote, int lateCount, int lateMinutes, int absentDays,
                  int earlyLeaveMinutes, int missingCheckOuts, int corrections) {}

    /**
     * The month's totals: each working day evaluated by {@link AttendanceRules#evaluate} at {@code now}, then added
     * up. Holidays and days off are not working days and count for nothing. A day still in progress counts what it has
     * reached so far — she is not absent at 09:00, and her missing check-out is not missing before the threshold.
     * A record of mode `correction` (feature 18) counts as present onsite and as a correction.
     */
    static Totals totals(List<DayInput> days, AttendanceRules.Thresholds thresholds, ZoneId zone, Instant now) {
        int working = 0, onsite = 0, remote = 0, lateCount = 0, lateMinutes = 0, absent = 0, early = 0, missing = 0, corrections = 0;
        for (var d : days) {
            if (d.expected().kind() != ScheduleResolver.Kind.WORK) continue;
            working++;
            var day = AttendanceRules.evaluate(d.expected(), thresholds, d.date(), zone, d.checkIn(), d.checkOut(), now);
            if (d.checkIn() != null) {
                if (CheckInMode.REMOTE.equals(d.mode())) remote++; else onsite++;
                if (CheckInMode.CORRECTION.equals(d.mode())) corrections++;
            } else if (day.absent()) absent++;
            if (day.lateMinutes() != null) { lateCount++; lateMinutes += day.lateMinutes(); }
            if (day.earlyLeaveMinutes() != null) early += day.earlyLeaveMinutes();
            if (day.missingCheckout()) missing++;
        }
        return new Totals(working, onsite, remote, lateCount, lateMinutes, absent, early, missing, corrections);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, String> categories(String schoolId) {
        var out = new HashMap<String, String>();
        for (var p : profiles.findBySchoolId(schoolId))
            if (p.getCategory() != null) out.put(BoardScope.key(p.getStaffKind(), p.getStaffId()), p.getCategory());
        return out;
    }

    /** The category an Admin set on her profile (feature 14), or her role's default. */
    private static String category(Map<String, String> categories, AttendanceStaff.Employee e) {
        String set = categories.get(BoardScope.key(e.kind(), e.id()));
        return set != null ? set : StaffAttendanceProfileService.defaultCategory(e.role());
    }

    private List<String> academies(String schoolId, AttendanceStaff.Employee e) {
        return e.user() == null ? List.of() : List.copyOf(managers.academies(schoolId, e.user()));
    }

    private static String time(Instant at, ZoneId zone) { return at == null ? null : HH_MM.format(at.atZone(zone)); }

    private static LocalDate date(String value, LocalDate today) {
        if (value == null || value.isBlank()) return today;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("day is a date such as 2026-10-18."); }
    }

    static YearMonth month(String value, LocalDate today) {
        if (value == null || value.isBlank()) return YearMonth.from(today);
        try { return YearMonth.parse(value.trim(), YYYY_MM); }
        catch (DateTimeParseException e) { throw ApiException.badRequest("month is a year and a month such as 2026-10."); }
    }
}
