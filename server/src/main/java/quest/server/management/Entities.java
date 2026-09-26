package quest.server.management;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

public final class Entities {
    private Entities() {}

    /**
     * V21 (RM5): one day of one staff member's attendance, as her department manager marked it. The sibling of
     * {@code AttendanceEntity}, with a user in place of a child and no section: a teacher of two grades is present
     * once, not once per class.
     *
     * <p>Tenant table, so every read below is reduced to the caller's school by the `school` filter; the person's
     * department is checked separately, by {@link quest.server.tenancy.ManagerScope}, because a school holds two.
     */
    @Entity(name = "StaffAttendanceEntity") @Table(name = "staff_attendance")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffAttendanceEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "user_id", nullable = false) private String userId;
        /** The day marked. Named `date` for V16's reason: DAY is a reserved word in H2 2.x. */
        @Column(name = "date", nullable = false) private LocalDate date;
        @Column(nullable = false) private String status;
        @Column private String note;
        @Column(name = "marked_by") private String markedBy;
        @Column(name = "marked_at", nullable = false) private Instant markedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
        public LocalDate getDate() { return date; } public void setDate(LocalDate v) { date = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public String getNote() { return note; } public void setNote(String v) { note = v; }
        public String getMarkedBy() { return markedBy; } public void setMarkedBy(String v) { markedBy = v; }
        public Instant getMarkedAt() { return markedAt; } public void setMarkedAt(Instant v) { markedAt = v; }
    }
}
