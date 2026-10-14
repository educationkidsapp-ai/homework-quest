package quest.server.staffattendance;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.AttendanceSettings.kt` (feature 14). Times are `HH:mm`, dates `yyyy-MM-dd`,
 * weekdays `DayOfWeek` names, all read in the school's time zone.
 */
public final class StaffAttendanceDto {
    private StaffAttendanceDto() {}

    public static final String TEACHER = "TEACHER", ADMINISTRATIVE = "ADMINISTRATIVE", WORKER = "WORKER";
    public static final List<String> CATEGORIES = List.of(TEACHER, ADMINISTRATIVE, WORKER);
    public static final List<String> NOTIFY_POLICIES = List.of("IMMEDIATE", "DIGEST", "OFF");
    public static final String KIND_USER = "USER", KIND_WORKER = "WORKER";

    public record WorkSchedule(String category, List<String> weekdays, String start, String end,
                               String ramadanFrom, String ramadanTo, String ramadanStart, String ramadanEnd) {}

    public record AttendanceHoliday(String name, String from, String to) {}

    public record AttendanceSettings(Double latitude, Double longitude, int radiusMeters, int graceMinutes,
                                     int notArrivedMinutes, int missingCheckOutMinutes, String directorNotify,
                                     String adminNotify, String digestTime, String timeZone,
                                     List<WorkSchedule> schedules, List<AttendanceHoliday> holidays) {}

    /** `PUT /admin/attendance-settings`: replaces the page; an absent number or policy is its default. */
    public record UpdateAttendanceSettingsRequest(Double latitude, Double longitude, Integer radiusMeters,
                                                  Integer graceMinutes, Integer notArrivedMinutes,
                                                  Integer missingCheckOutMinutes, String directorNotify,
                                                  String adminNotify, String digestTime,
                                                  List<WorkSchedule> schedules, List<AttendanceHoliday> holidays) {}

    public record StaffRef(String kind, String id) {}

    public record StaffAttendanceProfile(String kind, String id, String fullName, String role, String category,
                                         boolean categorySet, String directManagerId, String directManagerName) {}

    public record UpdateStaffAttendanceProfilesRequest(@NotNull List<StaffRef> staff, String directManagerId,
                                                       boolean clearDirectManager, String category,
                                                       boolean clearCategory) {}

    // ---------------------------------------------------------------- feature 17: the board and the monthly report

    /** A board row's one status. `NO_CHECK_OUT` is not among them: it is a flag a present row can also carry. */
    public static final String PRESENT = "PRESENT", LATE = "LATE", NOT_ARRIVED = "NOT_ARRIVED", REMOTE = "REMOTE",
            ABSENT = "ABSENT", UPCOMING = "UPCOMING", OFF = "OFF", HOLIDAY = "HOLIDAY";
    /** Who the caller sees: the whole school, her academy (and her reports), or only the people who report to her. */
    public static final String SCOPE_SCHOOL = "SCHOOL", SCOPE_ACADEMY = "ACADEMY", SCOPE_REPORTS = "REPORTS";

    public record BoardRow(String kind, String id, String fullName, String role, String category, List<String> academies,
                           String checkIn, String checkOut, String status, Integer lateMinutes, boolean noCheckOut,
                           String mode) {}

    /** The five cards. Each counts the rows the matching filter shows, so a card and the table never disagree. */
    public record BoardCounts(int present, int late, int notArrived, int remote, int noCheckOut) {}

    /** `GET /staff-attendance/board`: today in the school's zone, every employee the caller may see. */
    public record AttendanceBoard(String day, String timeZone, String scope, BoardCounts counts, List<BoardRow> rows) {}

    /** One check-in or check-out call: the time is the server's, `HH:mm` in the school's zone. No coordinate. */
    public record BoardAttempt(String at, String action, String result, String reason, Integer distanceMeters) {}

    /** A notification feature 16 sent about that day (`staff_attendance_alerts.type`). */
    public record BoardAlert(String type, String sentAt) {}

    /**
     * One day of the month calendar. `mode` is the check-in's (`onsite`, `remote`…), or `manual` for a day the
     * department manager marked by hand before the feature was switched on; `status` is then that mark's.
     */
    public record AttendanceCalendarDay(String date, String checkIn, String checkOut, String status, Integer lateMinutes, String mode) {}

    /** `GET /staff-attendance/employees/{kind}/{id}?day=`: one employee's day and her month around it. */
    public record EmployeeDay(String kind, String id, String fullName, String category, String day,
                              List<BoardAttempt> attempts, List<BoardAlert> alerts, String month,
                              List<AttendanceCalendarDay> calendar) {}

    /** One employee's month, ready for payroll. Minutes are whole minutes; corrections arrive with feature 18. */
    public record ReportRow(String kind, String id, String fullName, String category, List<String> academies,
                            int workingDays, int presentOnsite, int presentRemote, int lateCount, int lateMinutes,
                            int absentDays, int earlyLeaveMinutes, int missingCheckOuts, int corrections) {}

    /** `GET /staff-attendance/report?month=yyyy-MM`. */
    public record AttendanceReport(String month, String scope, List<ReportRow> rows) {}
}
