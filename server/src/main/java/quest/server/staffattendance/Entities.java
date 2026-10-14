package quest.server.staffattendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.hibernate.annotations.Filter;

/**
 * Feature 14's four tenant tables (`V37__attendance_settings.sql`) and feature 15's three
 * (`V38__staff_check_in.sql`), feature 16's two (`V39`) and feature 18's two (`V40`). Every one carries the school filter.
 *
 * <p><strong>No latitude or longitude column anywhere below.</strong> Feature 15's privacy rule is that the server
 * computes the distance to the pin and then discards the fix, so the furthest a coordinate travels is the body of a
 * POST and a local variable in {@link CheckInService}; what is stored is a distance in metres.
 */
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

    /**
     * Feature 15's attendance record: one row per (school, employee, school day). {@code day} is the date in the
     * school's time zone, the two instants are the server's clock, and the two distances are how far the accepted fix
     * was from the pin. A second check-out replaces the first, which is why there is one row and not two.
     */
    @Entity(name = "StaffCheckInEntity") @Table(name = "staff_check_ins")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffCheckInEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "staff_kind", nullable = false) private String staffKind;
        @Column(name = "staff_id", nullable = false) private String staffId;
        @Column(name = "work_day", nullable = false) private LocalDate day;   // `day` is a keyword in H2
        @Column(name = "check_in_at", nullable = false) private Instant checkInAt;
        @Column(name = "check_in_distance_m", nullable = false) private int checkInDistanceM;
        @Column(name = "check_out_at") private Instant checkOutAt;
        @Column(name = "check_out_distance_m") private Integer checkOutDistanceM;
        @Column(nullable = false) private String mode;
        // Feature 18: the audit of an approved correction, and the times it replaced (V40).
        @Column(name = "corrected_by") private String correctedBy;
        @Column(name = "corrected_at") private Instant correctedAt;
        @Column(name = "correction_request_id") private String correctionRequestId;
        @Column(name = "original_check_in_at") private Instant originalCheckInAt;
        @Column(name = "original_check_out_at") private Instant originalCheckOutAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getStaffKind() { return staffKind; } public void setStaffKind(String v) { staffKind = v; }
        public String getStaffId() { return staffId; } public void setStaffId(String v) { staffId = v; }
        public LocalDate getDay() { return day; } public void setDay(LocalDate v) { day = v; }
        public Instant getCheckInAt() { return checkInAt; } public void setCheckInAt(Instant v) { checkInAt = v; }
        public int getCheckInDistanceM() { return checkInDistanceM; } public void setCheckInDistanceM(int v) { checkInDistanceM = v; }
        public Instant getCheckOutAt() { return checkOutAt; } public void setCheckOutAt(Instant v) { checkOutAt = v; }
        public Integer getCheckOutDistanceM() { return checkOutDistanceM; } public void setCheckOutDistanceM(Integer v) { checkOutDistanceM = v; }
        public String getMode() { return mode; } public void setMode(String v) { mode = v; }
        public String getCorrectedBy() { return correctedBy; } public void setCorrectedBy(String v) { correctedBy = v; }
        public Instant getCorrectedAt() { return correctedAt; } public void setCorrectedAt(Instant v) { correctedAt = v; }
        public String getCorrectionRequestId() { return correctionRequestId; } public void setCorrectionRequestId(String v) { correctionRequestId = v; }
        public Instant getOriginalCheckInAt() { return originalCheckInAt; } public void setOriginalCheckInAt(Instant v) { originalCheckInAt = v; }
        public Instant getOriginalCheckOutAt() { return originalCheckOutAt; } public void setOriginalCheckOutAt(Instant v) { originalCheckOutAt = v; }
    }

    /**
     * Every call to check in or out, accepted or refused: this is where a mocked fix, an unapproved phone and a tap
     * from the car park are found afterwards. {@code phoneAt} is what the phone claimed the time was, kept as text
     * because it is audit material and not something the server computes with.
     */
    @Entity(name = "StaffCheckInAttemptEntity") @Table(name = "staff_check_in_attempts")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffCheckInAttemptEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "staff_kind", nullable = false) private String staffKind;
        @Column(name = "staff_id", nullable = false) private String staffId;
        @Column(name = "server_at", nullable = false) private Instant serverAt;
        @Column(name = "phone_at") private String phoneAt;
        @Column(nullable = false) private String action;
        @Column(name = "distance_m") private Integer distanceM;
        @Column(name = "accuracy_m") private Double accuracyM;
        @Column(nullable = false) private boolean mocked;
        @Column(name = "install_id") private String installId;
        @Column(nullable = false) private String result;
        private String reason;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getStaffKind() { return staffKind; } public void setStaffKind(String v) { staffKind = v; }
        public String getStaffId() { return staffId; } public void setStaffId(String v) { staffId = v; }
        public Instant getServerAt() { return serverAt; } public void setServerAt(Instant v) { serverAt = v; }
        public String getPhoneAt() { return phoneAt; } public void setPhoneAt(String v) { phoneAt = v; }
        public String getAction() { return action; } public void setAction(String v) { action = v; }
        public Integer getDistanceM() { return distanceM; } public void setDistanceM(Integer v) { distanceM = v; }
        public Double getAccuracyM() { return accuracyM; } public void setAccuracyM(Double v) { accuracyM = v; }
        public boolean isMocked() { return mocked; } public void setMocked(boolean v) { mocked = v; }
        public String getInstallId() { return installId; } public void setInstallId(String v) { installId = v; }
        public String getResult() { return result; } public void setResult(String v) { result = v; }
        public String getReason() { return reason; } public void setReason(String v) { reason = v; }
    }

    /**
     * The phone bound to an employee. The first successful check-in writes the row; the Admin's reset stamps
     * {@code resetBy} and {@code resetAt} and the next successful check-in writes a new one, so the history of which
     * phones a person used is kept. <strong>The live binding is the row whose {@code resetAt} is null.</strong>
     */
    @Entity(name = "StaffAttendanceDeviceEntity") @Table(name = "staff_attendance_devices")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffAttendanceDeviceEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "staff_kind", nullable = false) private String staffKind;
        @Column(name = "staff_id", nullable = false) private String staffId;
        @Column(name = "install_id", nullable = false) private String installId;
        @Column(name = "first_seen_at", nullable = false) private Instant firstSeenAt;
        @Column(name = "reset_by") private String resetBy;
        @Column(name = "reset_at") private Instant resetAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getStaffKind() { return staffKind; } public void setStaffKind(String v) { staffKind = v; }
        public String getStaffId() { return staffId; } public void setStaffId(String v) { staffId = v; }
        public String getInstallId() { return installId; } public void setInstallId(String v) { installId = v; }
        public Instant getFirstSeenAt() { return firstSeenAt; } public void setFirstSeenAt(Instant v) { firstSeenAt = v; }
        public String getResetBy() { return resetBy; } public void setResetBy(String v) { resetBy = v; }
        public Instant getResetAt() { return resetAt; } public void setResetAt(Instant v) { resetAt = v; }
    }

    /**
     * Feature 16: the statuses one employee's working day has reached so far (`V39`). Several can hold at once;
     * {@code lateMinutes} and {@code earlyLeaveMinutes} are null when she was not late or did not leave early.
     */
    @Entity(name = "StaffAttendanceDayEntity") @Table(name = "staff_attendance_days")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffAttendanceDayEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "staff_kind", nullable = false) private String staffKind;
        @Column(name = "staff_id", nullable = false) private String staffId;
        @Column(name = "work_day", nullable = false) private LocalDate day;   // `day` is a keyword in H2
        @Column(name = "late_minutes") private Integer lateMinutes;
        @Column(name = "not_arrived", nullable = false) private boolean notArrived;
        @Column(nullable = false) private boolean absent;
        @Column(name = "early_leave_minutes") private Integer earlyLeaveMinutes;
        @Column(name = "missing_checkout", nullable = false) private boolean missingCheckout;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getStaffKind() { return staffKind; } public void setStaffKind(String v) { staffKind = v; }
        public String getStaffId() { return staffId; } public void setStaffId(String v) { staffId = v; }
        public LocalDate getDay() { return day; } public void setDay(LocalDate v) { day = v; }
        public Integer getLateMinutes() { return lateMinutes; } public void setLateMinutes(Integer v) { lateMinutes = v; }
        public boolean isNotArrived() { return notArrived; } public void setNotArrived(boolean v) { notArrived = v; }
        public boolean isAbsent() { return absent; } public void setAbsent(boolean v) { absent = v; }
        public Integer getEarlyLeaveMinutes() { return earlyLeaveMinutes; } public void setEarlyLeaveMinutes(Integer v) { earlyLeaveMinutes = v; }
        public boolean isMissingCheckout() { return missingCheckout; } public void setMissingCheckout(boolean v) { missingCheckout = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    /** Feature 16: one notification that has been sent, so it is never sent again (`V39`). */
    @Entity(name = "StaffAttendanceAlertEntity") @Table(name = "staff_attendance_alerts")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffAttendanceAlertEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "staff_kind", nullable = false) private String staffKind;
        @Column(name = "staff_id", nullable = false) private String staffId;
        @Column(name = "work_day", nullable = false) private LocalDate day;
        @Column(nullable = false) private String type;
        @Column(name = "sent_at", nullable = false) private Instant sentAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getStaffKind() { return staffKind; } public void setStaffKind(String v) { staffKind = v; }
        public String getStaffId() { return staffId; } public void setStaffId(String v) { staffId = v; }
        public LocalDate getDay() { return day; } public void setDay(LocalDate v) { day = v; }
        public String getType() { return type; } public void setType(String v) { type = v; }
        public Instant getSentAt() { return sentAt; } public void setSentAt(Instant v) { sentAt = v; }
    }

    /** Feature 18: a day the Admin declared remote, for the whole school ({@code academy} null) or one academy (V40). */
    @Entity(name = "AttendanceRemoteDayEntity") @Table(name = "attendance_remote_days")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AttendanceRemoteDayEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "work_day", nullable = false) private LocalDate day;
        @Column private String academy;
        @Column(nullable = false) private String reason;
        @Column(name = "created_by", nullable = false) private String createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public LocalDate getDay() { return day; } public void setDay(LocalDate v) { day = v; }
        public String getAcademy() { return academy; } public void setAcademy(String v) { academy = v; }
        public String getReason() { return reason; } public void setReason(String v) { reason = v; }
        public String getCreatedBy() { return createdBy; } public void setCreatedBy(String v) { createdBy = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /**
     * Feature 18: an employee's request for a remote day or a correction, and its decision (V40). The claimed times are
     * wall-clock times in the school's zone on {@code day}; they become instants only when the correction is applied.
     */
    @Entity(name = "StaffAttendanceRequestEntity") @Table(name = "staff_attendance_requests")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffAttendanceRequestEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "staff_kind", nullable = false) private String staffKind;
        @Column(name = "staff_id", nullable = false) private String staffId;
        @Column(nullable = false) private String type;
        @Column(name = "work_day", nullable = false) private LocalDate day;
        @Column(name = "correction_kind") private String correctionKind;
        @Column(name = "claimed_check_in") private LocalTime claimedCheckIn;
        @Column(name = "claimed_check_out") private LocalTime claimedCheckOut;
        @Column(nullable = false) private String reason;
        @Column(name = "attachment_id") private String attachmentId;
        @Column(nullable = false) private String status;
        @Column(name = "created_by", nullable = false) private String createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "decided_by") private String decidedBy;
        @Column(name = "decided_at") private Instant decidedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getStaffKind() { return staffKind; } public void setStaffKind(String v) { staffKind = v; }
        public String getStaffId() { return staffId; } public void setStaffId(String v) { staffId = v; }
        public String getType() { return type; } public void setType(String v) { type = v; }
        public LocalDate getDay() { return day; } public void setDay(LocalDate v) { day = v; }
        public String getCorrectionKind() { return correctionKind; } public void setCorrectionKind(String v) { correctionKind = v; }
        public LocalTime getClaimedCheckIn() { return claimedCheckIn; } public void setClaimedCheckIn(LocalTime v) { claimedCheckIn = v; }
        public LocalTime getClaimedCheckOut() { return claimedCheckOut; } public void setClaimedCheckOut(LocalTime v) { claimedCheckOut = v; }
        public String getReason() { return reason; } public void setReason(String v) { reason = v; }
        public String getAttachmentId() { return attachmentId; } public void setAttachmentId(String v) { attachmentId = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public String getCreatedBy() { return createdBy; } public void setCreatedBy(String v) { createdBy = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public String getDecidedBy() { return decidedBy; } public void setDecidedBy(String v) { decidedBy = v; }
        public Instant getDecidedAt() { return decidedAt; } public void setDecidedAt(Instant v) { decidedAt = v; }
    }
}
