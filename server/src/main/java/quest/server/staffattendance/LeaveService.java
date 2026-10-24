package quest.server.staffattendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.staff.AttendanceRequestStatus;
import quest.api.staff.LeavePreview;
import quest.api.staff.LeaveTypeBalance;
import quest.api.staff.MyLeaveBalance;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;
import quest.server.platform.SchoolCalendar;
import quest.server.staffattendance.StaffAttendanceDto.LeaveBalanceCell;
import quest.server.staffattendance.StaffAttendanceDto.LeaveBalanceReport;
import quest.server.staffattendance.StaffAttendanceDto.LeaveBalanceRow;
import quest.server.staffattendance.StaffAttendanceDto.LeaveRequestReport;
import quest.server.staffattendance.StaffAttendanceDto.LeaveRequestRow;
import quest.server.tenancy.TenantContext;

/**
 * Feature 34's arithmetic, in one place: how many <strong>school days of her own schedule</strong> a date range uses,
 * how much of a yearly balance that leaves, and the two reports the leaders export.
 *
 * <p><strong>The day count is the schedule's.</strong> A range of a week with a weekend and a holiday in it uses the
 * working days only ({@link ScheduleResolver#expected(String, String, LocalDate)} — the form <em>without</em>
 * feature 34's own excuses, so a day she is already on leave for is still one of her schedule's working days and it
 * is the overlap rule, not a shrinking count, that refuses a second request for it).
 *
 * <p><strong>The year is the school's.</strong> The calendar year of the school's time zone
 * ({@link SchoolCalendar}), and a range counts in the year it <em>starts</em>, so no request is split between two
 * years or counted in both.
 *
 * <p><strong>No balance is not a zero balance.</strong> A (type, category) with no row in `leave_balances` has no
 * allowance at all — unpaid and other, until the Admin sets one — and nothing is ever refused on it.
 */
@Service
public class LeaveService {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter YYYY_MM = DateTimeFormatter.ofPattern("yyyy-MM");
    /** A leave of more than a term is a data-entry slip, not a request. */
    static final int MAX_LEAVE_RANGE_DAYS = 180;

    private final StaffAttendanceRequestRepository requests; private final AttendanceSettingsService settings;
    private final ScheduleResolver schedules; private final AttendanceDays days; private final AttendanceStaff staff;
    private final BoardScope scope; private final DirectManagerResolver managers; private final UserRepository users;
    private final FeatureFlags flags; private final TenantContext tenant; private final SchoolCalendar calendar; private final Clock clock;

    public LeaveService(StaffAttendanceRequestRepository requests, AttendanceSettingsService settings,
                        ScheduleResolver schedules, AttendanceDays days, AttendanceStaff staff, BoardScope scope,
                        DirectManagerResolver managers, UserRepository users, FeatureFlags flags,
                        TenantContext tenant, SchoolCalendar calendar, Clock clock) {
        this.requests = requests; this.settings = settings; this.schedules = schedules; this.days = days;
        this.staff = staff; this.scope = scope; this.managers = managers; this.users = users; this.flags = flags;
        this.tenant = tenant; this.calendar = calendar; this.clock = clock;
    }

    // ---------------------------------------------------------------- the school days a range uses

    /** The dates of {@code [from, to]} that are working days of her category's schedule, in order. */
    List<LocalDate> schoolDays(String schoolId, String category, LocalDate from, LocalDate to) {
        var out = new ArrayList<LocalDate>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
            if (schedules.expected(schoolId, category, d).kind() == ScheduleResolver.Kind.WORK) out.add(d);
        return out;
    }

    /** How many days of approved leave of one type she has already used in the year the date falls in. */
    int usedDays(String schoolId, AttendanceStaff.Employee employee, String leaveType, LocalDate inYear) {
        LocalDate first = inYear.withDayOfYear(1), last = first.plusYears(1).minusDays(1);
        int used = 0;
        for (var row : requests.approvedLeaveOfYear(schoolId, employee.kind(), employee.id(), first, last))
            if (leaveType.equals(row.getLeaveType()) && row.getDays() != null) used += row.getDays();
        return used;
    }

    /** Her category: the one an Admin set on her profile (feature 14), or her role's default. */
    String category(String schoolId, AttendanceStaff.Employee employee) { return days.category(schoolId, employee); }

    // ---------------------------------------------------------------- GET /staff/attendance/leave/balance

    @Transactional(readOnly = true)
    public MyLeaveBalance myBalance(Principals.User caller) {
        String schoolId = requireStaffApp();
        var employee = me(schoolId, caller);
        return balance(schoolId, employee, today(schoolId));
    }

    MyLeaveBalance balance(String schoolId, AttendanceStaff.Employee employee, LocalDate today) {
        var rules = settings.leave(schoolId);
        String category = category(schoolId, employee);
        var rows = new ArrayList<LeaveTypeBalance>();
        for (String type : StaffAttendanceDto.LEAVE_TYPES) {
            Integer allowance = rules.allowance(type, category);
            int used = usedDays(schoolId, employee, type, today);
            rows.add(new LeaveTypeBalance(type, rules.enabled(type), rules.documentAfterDays(type), allowance, used,
                    allowance == null ? null : allowance - used));
        }
        return new MyLeaveBalance(today.getYear(), category, rules.chain(), rules.permissionMaxMinutes(), rows);
    }

    // ---------------------------------------------------------------- GET /staff/attendance/leave/preview

    @Transactional(readOnly = true)
    public LeavePreview preview(Principals.User caller, String leaveType, String from, String to) {
        String schoolId = requireStaffApp();
        var employee = me(schoolId, caller);
        String type = AttendanceSettingsService.leaveType(leaveType);
        LocalDate first = RemoteDayService.date(from), last = to == null || to.isBlank() ? first : RemoteDayService.date(to);
        if (last.isBefore(first)) throw ApiException.badRequest("to is on or after from.");
        if (first.plusDays(MAX_LEAVE_RANGE_DAYS).isBefore(last))
            throw ApiException.badRequest("A leave is at most " + MAX_LEAVE_RANGE_DAYS + " days long.");
        var rules = settings.leave(schoolId);
        String category = category(schoolId, employee);
        var dates = schoolDays(schoolId, category, first, last);
        Integer allowance = rules.allowance(type, category);
        int used = usedDays(schoolId, employee, type, first);
        Integer documentAfter = rules.documentAfterDays(type);
        return new LeavePreview(type, first.toString(), last.toString(), dates.size(),
                dates.stream().map(LocalDate::toString).toList(), allowance, used,
                allowance == null ? null : allowance - used,
                allowance == null ? null : allowance - used - dates.size(),
                documentAfter != null && dates.size() > documentAfter);
    }

    // ---------------------------------------------------------------- GET /staff-attendance/leave/balances

    /** Every employee the caller's board shows, with her balance of each leave type the school offers. */
    @Transactional(readOnly = true)
    public LeaveBalanceReport balances(Principals.User caller, String yearParam) {
        String schoolId = tenant.writeSchoolId();
        var view = scope.of(schoolId, caller);
        LocalDate today = today(schoolId);
        int year = year(yearParam, today.getYear());
        LocalDate inYear = LocalDate.of(year, 1, 1);
        var rules = settings.leave(schoolId);
        var types = StaffAttendanceDto.LEAVE_TYPES.stream().filter(rules::enabled).toList();
        var rows = new ArrayList<LeaveBalanceRow>();
        for (var e : view.employees()) {
            String category = category(schoolId, e);
            var cells = new ArrayList<LeaveBalanceCell>();
            for (String type : types) {
                Integer allowance = rules.allowance(type, category);
                int used = usedDays(schoolId, e, type, inYear);
                cells.add(new LeaveBalanceCell(type, allowance, used, allowance == null ? null : allowance - used));
            }
            rows.add(new LeaveBalanceRow(e.kind(), e.id(), e.fullName(), category, academies(schoolId, e), cells));
        }
        return new LeaveBalanceReport(year, view.scope(), types, rows);
    }

    // ---------------------------------------------------------------- GET /staff-attendance/leave/requests

    /** Every request of the month — leave, permission, remote day and correction — over the caller's own people. */
    @Transactional(readOnly = true)
    public LeaveRequestReport monthlyRequests(Principals.User caller, String monthParam) {
        String schoolId = tenant.writeSchoolId();
        var view = scope.of(schoolId, caller);
        ZoneId zone = calendar.of(schoolId).zone();
        YearMonth ym = AttendanceBoardService.month(monthParam, today(schoolId));
        var mine = new HashMap<String, AttendanceStaff.Employee>();
        for (var e : view.employees()) mine.put(BoardScope.key(e.kind(), e.id()), e);

        var rows = new ArrayList<LeaveRequestRow>();
        for (var row : requests.ofMonth(schoolId, ym.atDay(1), ym.atEndOfMonth())) {
            var employee = mine.get(BoardScope.key(row.getStaffKind(), row.getStaffId()));
            if (employee == null) continue;
            String decidedBy = row.getDecidedBy() == null ? null
                    : users.findById(row.getDecidedBy()).map(AttendanceStaff::name).orElse(null);
            rows.add(new LeaveRequestRow(row.getId(), row.getStaffKind(), row.getStaffId(), employee.fullName(),
                    category(schoolId, employee), row.getType(), row.getLeaveType(), row.getDay().toString(),
                    row.getToDay() == null ? null : row.getToDay().toString(), time(row.getFromTime()), time(row.getToTime()),
                    row.getDays(), row.getMinutes(), row.getStatus(),
                    AttendanceRequestStatus.PENDING.equals(row.getStatus()) ? row.getStage() : null,
                    row.getReason(), row.getRejectReason(), decidedBy,
                    row.getDecidedAt() == null ? null : LocalDate.ofInstant(row.getDecidedAt(), zone).toString()));
        }
        return new LeaveRequestReport(ym.format(YYYY_MM), view.scope(), rows);
    }

    // ---------------------------------------------------------------- helpers

    private List<String> academies(String schoolId, AttendanceStaff.Employee e) {
        return e.user() == null ? List.of() : List.copyOf(managers.academies(schoolId, e.user()));
    }

    static int year(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int year = Integer.parseInt(value.trim());
            if (year < 2000 || year > 2100) throw new NumberFormatException(value);
            return year;
        } catch (NumberFormatException e) { throw ApiException.badRequest("year is a calendar year such as 2026."); }
    }

    private static String time(LocalTime at) { return at == null ? null : HH_MM.format(at); }

    private LocalDate today(String schoolId) { return LocalDate.ofInstant(Instant.now(clock), calendar.of(schoolId).zone()); }

    /** As feature 15's routes: the `/staff` area exists only when the school has staff mode on. */
    private String requireStaffApp() {
        String schoolId = tenant.writeSchoolId();
        if (!flags.isOn(schoolId, FlagKeys.STAFF_MOBILE_APP))
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
        return schoolId;
    }

    private AttendanceStaff.Employee me(String schoolId, Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return staff.forPrincipal(schoolId, caller)
                .orElseThrow(() -> ApiException.forbidden("Only an active employee of this school reads her own balance."));
    }
}
