package quest.server.staffattendance;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import quest.api.staff.AttendanceRequestType;
import quest.server.staffattendance.Entities.StaffAttendanceRequestEntity;

/**
 * Feature 34, the <strong>one</strong> place attendance learns that a day or a few minutes of it are excused: an
 * approved leave covering the date, and the approved permission windows on it.
 *
 * <p><strong>Why one component.</strong> A staff day's status is computed in five places — feature 16's
 * {@link StaffAttendanceJob}, {@link AttendanceDays#recompute}, and feature 17's board, employee day and monthly
 * report — and all five go through {@link ScheduleResolver#expected} and {@link AttendanceRules#evaluate}. Leave and
 * permission are therefore fed in here, once, and carried on {@link ScheduleResolver.Expected}: no caller has to
 * remember to ask, and the five paths cannot disagree about who was on leave.
 *
 * <p><strong>No tenant filter is relied on.</strong> Every query names the school, because the job runs on the
 * scheduler's thread where {@link quest.server.tenancy.TenantContext} is null.
 */
@Component
public class Excuses {
    /** A permission's window on one day, wall-clock in the school's zone. {@code from} is before {@code to}. */
    public record Window(LocalTime from, LocalTime to) {}

    /**
     * What one employee's one date is excused by: approved leave of {@code leaveType} (then {@code onLeave}), and
     * every approved permission window on it. The two can be refused together ({@link AttendanceRequestService}'s
     * overlap rule), so in practice a day has one or the other.
     */
    public record DayExcuse(boolean onLeave, String leaveType, List<Window> permissions) {
        public static final DayExcuse NONE = new DayExcuse(false, null, List.of());
    }

    private final StaffAttendanceRequestRepository requests;

    public Excuses(StaffAttendanceRequestRepository requests) { this.requests = requests; }

    /** One employee, one date. */
    public DayExcuse of(String schoolId, String staffKind, String staffId, LocalDate date) {
        return from(requests.approvedExcuses(schoolId, staffKind, staffId, date, date), date);
    }

    /**
     * One employee, every date of a range — what the monthly report and "My record" read, so a month costs one query
     * rather than thirty. A date with nothing on it is absent from the map; read it with
     * {@code map.getOrDefault(date, DayExcuse.NONE)}.
     */
    public java.util.Map<LocalDate, DayExcuse> between(String schoolId, String staffKind, String staffId,
                                                        LocalDate from, LocalDate to) {
        var rows = requests.approvedExcuses(schoolId, staffKind, staffId, from, to);
        var out = new java.util.HashMap<LocalDate, DayExcuse>();
        if (rows.isEmpty()) return out;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            var excuse = from(rows, date);
            if (excuse != DayExcuse.NONE) out.put(date, excuse);
        }
        return out;
    }

    /** The approved leave and permission rows of a whole school in a range, keyed `<kind>:<id>` — the board's read. */
    public java.util.Map<String, List<StaffAttendanceRequestEntity>> ofSchool(String schoolId, LocalDate from, LocalDate to) {
        var out = new java.util.HashMap<String, List<StaffAttendanceRequestEntity>>();
        for (var row : requests.approvedExcusesOfSchool(schoolId, from, to))
            out.computeIfAbsent(BoardScope.key(row.getStaffKind(), row.getStaffId()), k -> new ArrayList<>()).add(row);
        return out;
    }

    /** The excuse one date takes from rows already loaded: pure, so the board filters a month's rows in memory. */
    public static DayExcuse from(List<StaffAttendanceRequestEntity> rows, LocalDate date) {
        boolean onLeave = false;
        String leaveType = null;
        var windows = new ArrayList<Window>();
        for (var row : rows) {
            if (!covers(row, date)) continue;
            if (AttendanceRequestType.LEAVE.equals(row.getType())) {
                onLeave = true;
                if (leaveType == null) leaveType = row.getLeaveType();
            } else if (AttendanceRequestType.PERMISSION.equals(row.getType())
                    && row.getFromTime() != null && row.getToTime() != null) {
                windows.add(new Window(row.getFromTime(), row.getToTime()));
            }
        }
        return !onLeave && windows.isEmpty() ? DayExcuse.NONE : new DayExcuse(onLeave, leaveType, List.copyOf(windows));
    }

    /** Inclusive on both ends; a null {@code to_day} is the one day the request names. */
    static boolean covers(StaffAttendanceRequestEntity row, LocalDate date) {
        LocalDate to = row.getToDay() == null ? row.getDay() : row.getToDay();
        return !date.isBefore(row.getDay()) && !date.isAfter(to);
    }
}
