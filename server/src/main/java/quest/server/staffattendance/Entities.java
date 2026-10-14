package quest.server.staffattendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.hibernate.annotations.Filter;

/** Feature 14's four tenant tables (`V37__attendance_settings.sql`). Every one carries the school filter. */
public final class Entities {
    private Entities() {}

    /** One row per school: the pin, the rule thresholds and the notification policy. No row is the defaults. */
    @Entity(name = "AttendanceSettingsEntity") @Table(name = "attendance_settings")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AttendanceSettingsEntity {
        @Id @Column(name = "school_id") private String schoolId;
        private Double latitude;
        private Double longitude;
        @Column(name = "radius_m", nullable = false) private int radiusMeters;
        @Column(name = "grace_minutes", nullable = false) private int graceMinutes;
        @Column(name = "not_arrived_minutes", nullable = false) private int notArrivedMinutes;
        @Column(name = "missing_check_out_minutes", nullable = false) private int missingCheckOutMinutes;
        @Column(name = "director_notify", nullable = false) private String directorNotify;
        @Column(name = "admin_notify", nullable = false) private String adminNotify;
        @Column(name = "digest_time", nullable = false) private LocalTime digestTime;
        @Column(name = "updated_by") private String updatedBy;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public Double getLatitude() { return latitude; } public void setLatitude(Double v) { latitude = v; }
        public Double getLongitude() { return longitude; } public void setLongitude(Double v) { longitude = v; }
        public int getRadiusMeters() { return radiusMeters; } public void setRadiusMeters(int v) { radiusMeters = v; }
        public int getGraceMinutes() { return graceMinutes; } public void setGraceMinutes(int v) { graceMinutes = v; }
        public int getNotArrivedMinutes() { return notArrivedMinutes; } public void setNotArrivedMinutes(int v) { notArrivedMinutes = v; }
        public int getMissingCheckOutMinutes() { return missingCheckOutMinutes; } public void setMissingCheckOutMinutes(int v) { missingCheckOutMinutes = v; }
        public String getDirectorNotify() { return directorNotify; } public void setDirectorNotify(String v) { directorNotify = v; }
        public String getAdminNotify() { return adminNotify; } public void setAdminNotify(String v) { adminNotify = v; }
        public LocalTime getDigestTime() { return digestTime; } public void setDigestTime(LocalTime v) { digestTime = v; }
        public String getUpdatedBy() { return updatedBy; } public void setUpdatedBy(String v) { updatedBy = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    /** One category's hours, with an optional Ramadan range whose hours replace them inside it. */
    @Entity(name = "WorkScheduleEntity") @Table(name = "work_schedules")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class WorkScheduleEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(nullable = false) private String category;
        /** `DayOfWeek` names, comma separated. */
        @Column(nullable = false) private String weekdays;
        @Column(name = "start_time", nullable = false) private LocalTime startTime;
        @Column(name = "end_time", nullable = false) private LocalTime endTime;
        @Column(name = "ramadan_from") private LocalDate ramadanFrom;
        @Column(name = "ramadan_to") private LocalDate ramadanTo;
        @Column(name = "ramadan_start") private LocalTime ramadanStart;
        @Column(name = "ramadan_end") private LocalTime ramadanEnd;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getCategory() { return category; } public void setCategory(String v) { category = v; }
        public String getWeekdays() { return weekdays; } public void setWeekdays(String v) { weekdays = v; }
        public LocalTime getStartTime() { return startTime; } public void setStartTime(LocalTime v) { startTime = v; }
        public LocalTime getEndTime() { return endTime; } public void setEndTime(LocalTime v) { endTime = v; }
        public LocalDate getRamadanFrom() { return ramadanFrom; } public void setRamadanFrom(LocalDate v) { ramadanFrom = v; }
        public LocalDate getRamadanTo() { return ramadanTo; } public void setRamadanTo(LocalDate v) { ramadanTo = v; }
        public LocalTime getRamadanStart() { return ramadanStart; } public void setRamadanStart(LocalTime v) { ramadanStart = v; }
        public LocalTime getRamadanEnd() { return ramadanEnd; } public void setRamadanEnd(LocalTime v) { ramadanEnd = v; }
    }

    /** A named, inclusive date range on which no attendance is expected. */
    @Entity(name = "AttendanceHolidayEntity") @Table(name = "attendance_holidays")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AttendanceHolidayEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(nullable = false, length = 80) private String name;
        @Column(name = "from_date", nullable = false) private LocalDate fromDate;
        @Column(name = "to_date", nullable = false) private LocalDate toDate;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public LocalDate getFromDate() { return fromDate; } public void setFromDate(LocalDate v) { fromDate = v; }
        public LocalDate getToDate() { return toDate; } public void setToDate(LocalDate v) { toDate = v; }
    }

    /**
     * An employee's category and direct manager. `staffKind` is `USER` (`users.id`) or `WORKER` (`workers.id`); a null
     * category is the role's default and a null manager falls back to the academy director, then the Admin.
     */
    @Entity(name = "StaffAttendanceProfileEntity") @Table(name = "staff_attendance_profiles")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffAttendanceProfileEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "staff_kind", nullable = false) private String staffKind;
        @Column(name = "staff_id", nullable = false) private String staffId;
        private String category;
        @Column(name = "direct_manager_id") private String directManagerId;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getStaffKind() { return staffKind; } public void setStaffKind(String v) { staffKind = v; }
        public String getStaffId() { return staffId; } public void setStaffId(String v) { staffId = v; }
        public String getCategory() { return category; } public void setCategory(String v) { category = v; }
        public String getDirectManagerId() { return directManagerId; } public void setDirectManagerId(String v) { directManagerId = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }
}
