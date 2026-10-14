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
}
