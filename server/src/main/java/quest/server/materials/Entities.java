package quest.server.materials;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.hibernate.annotations.Filter;

/**
 * Feature 31's three tenant tables (`V53__teaching_materials.sql`). Every one carries the `school` filter: what a
 * teacher prepared is her school's business and nobody else's.
 *
 * <p><strong>No status column.</strong> A submission's status is computed from {@code submittedAt} against the
 * week's deadline and from {@code reviewedAt} ({@link TeachingMaterialService}), so a school that moves its deadline
 * re-reads every row rather than leaving old ones asserting a rule nobody holds any more.
 */
public final class Entities {
    private Entities() {}

    /** One row per school: the deadline's weekday and time, and the reminder's lead. No row is the defaults. */
    @Entity(name = "MaterialSettingsEntity") @Table(name = "material_settings")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class MaterialSettingsEntity {
        @Id @Column(name = "school_id") private String schoolId;
        @Column(name = "due_weekday", nullable = false) private String dueWeekday;
        @Column(name = "due_time", nullable = false) private LocalTime dueTime;
        @Column(name = "remind_hours_before", nullable = false) private int remindHoursBefore;
        @Column(name = "updated_by") private String updatedBy;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getDueWeekday() { return dueWeekday; } public void setDueWeekday(String v) { dueWeekday = v; }
        public LocalTime getDueTime() { return dueTime; } public void setDueTime(LocalTime v) { dueTime = v; }
        public int getRemindHoursBefore() { return remindHoursBefore; } public void setRemindHoursBefore(int v) { remindHoursBefore = v; }
        public String getUpdatedBy() { return updatedBy; } public void setUpdatedBy(String v) { updatedBy = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    /**
     * What one teacher handed in for one (class, subject) and teaching week — unique on those five, so replacing it
     * is an update rather than a second row. {@code weekStart} is the first teaching day of the week the material is
     * <em>for</em> (`SchoolCalendar.Week.startOf`), and {@code attachmentId} names an `attachments` row of purpose
     * `material`; a row may instead carry only a note.
     */
    @Entity(name = "MaterialSubmissionEntity") @Table(name = "material_submissions")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class MaterialSubmissionEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "teacher_id", nullable = false) private String teacherId;
        @Column(name = "class_id", nullable = false) private String classId;
        @Column(nullable = false) private String subject;
        @Column(name = "week_start", nullable = false) private LocalDate weekStart;
        @Column(name = "attachment_id") private String attachmentId;
        @Column private String note;
        @Column(name = "submitted_at", nullable = false) private Instant submittedAt;
        @Column(name = "reviewed_by") private String reviewedBy;
        @Column(name = "reviewed_at") private Instant reviewedAt;
        @Column(name = "review_comment") private String reviewComment;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public String getClassId() { return classId; } public void setClassId(String v) { classId = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public LocalDate getWeekStart() { return weekStart; } public void setWeekStart(LocalDate v) { weekStart = v; }
        public String getAttachmentId() { return attachmentId; } public void setAttachmentId(String v) { attachmentId = v; }
        public String getNote() { return note; } public void setNote(String v) { note = v; }
        public Instant getSubmittedAt() { return submittedAt; } public void setSubmittedAt(Instant v) { submittedAt = v; }
        public String getReviewedBy() { return reviewedBy; } public void setReviewedBy(String v) { reviewedBy = v; }
        public Instant getReviewedAt() { return reviewedAt; } public void setReviewedAt(Instant v) { reviewedAt = v; }
        public String getReviewComment() { return reviewComment; } public void setReviewComment(String v) { reviewComment = v; }
    }

    /** One reminder per (school, teacher, week), claimed before it is sent — V39's `staff_attendance_alerts` exactly. */
    @Entity(name = "MaterialReminderEntity") @Table(name = "material_reminders")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class MaterialReminderEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "teacher_id", nullable = false) private String teacherId;
        @Column(name = "week_start", nullable = false) private LocalDate weekStart;
        @Column(name = "sent_at", nullable = false) private Instant sentAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public LocalDate getWeekStart() { return weekStart; } public void setWeekStart(LocalDate v) { weekStart = v; }
        public Instant getSentAt() { return sentAt; } public void setSentAt(Instant v) { sentAt = v; }
    }
}
