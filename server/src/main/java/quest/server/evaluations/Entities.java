package quest.server.evaluations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Feature 33's three tenant tables (`V56__child_evaluations.sql`). Every one carries the `school` filter: what a
 * teacher wrote about a child is her school's business and nobody else's.
 *
 * <p><strong>Two different words.</strong> {@code state} is the workflow — DRAFT, SUBMITTED, RETURNED, APPROVED —
 * and is a column because the teacher and the leader move it. The compliance <em>status</em> (on time, late,
 * missing) is <strong>not</strong> a column: it is computed from {@code submittedAt} against the round's deadline
 * ({@link ChildEvaluationService}), so an Admin who moves the deadline re-reads every row rather than leaving old
 * ones asserting a rule nobody holds any more — feature 31's rule exactly.
 */
public final class Entities {
    private Entities() {}

    /**
     * One round the Admin opens: a name in both languages, the day it opens, its deadline as an instant (she enters
     * it in the school's own zone) and the reminder's lead. {@code releasedAt} is the one-way door to the parents.
     */
    @Entity(name = "EvaluationRoundEntity") @Table(name = "evaluation_rounds")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class EvaluationRoundEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "name_en", nullable = false) private String nameEn;
        @Column(name = "name_ar", nullable = false) private String nameAr;
        @Column(name = "opens_on", nullable = false) private LocalDate opensOn;
        @Column(name = "due_at", nullable = false) private Instant dueAt;
        @Column(name = "remind_hours_before", nullable = false) private int remindHoursBefore;
        @Column(name = "released_at") private Instant releasedAt;
        @Column(name = "released_by") private String releasedBy;
        @Column(name = "created_by") private String createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getNameEn() { return nameEn; } public void setNameEn(String v) { nameEn = v; }
        public String getNameAr() { return nameAr; } public void setNameAr(String v) { nameAr = v; }
        public LocalDate getOpensOn() { return opensOn; } public void setOpensOn(LocalDate v) { opensOn = v; }
        public Instant getDueAt() { return dueAt; } public void setDueAt(Instant v) { dueAt = v; }
        public int getRemindHoursBefore() { return remindHoursBefore; } public void setRemindHoursBefore(int v) { remindHoursBefore = v; }
        public Instant getReleasedAt() { return releasedAt; } public void setReleasedAt(Instant v) { releasedAt = v; }
        public String getReleasedBy() { return releasedBy; } public void setReleasedBy(String v) { releasedBy = v; }
        public String getCreatedBy() { return createdBy; } public void setCreatedBy(String v) { createdBy = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
        public boolean isReleased() { return releasedAt != null; }
    }

    /**
     * What one teacher wrote about one child for one (class, subject) in one round — unique on those six, so saving
     * a draft again is an update rather than a second row. The four criteria are 1–4 and null while unanswered; the
     * parent is never told the number, only the word the server maps it to.
     */
    @Entity(name = "ChildEvaluationEntity") @Table(name = "child_evaluations")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ChildEvaluationEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "round_id", nullable = false) private String roundId;
        @Column(name = "teacher_id", nullable = false) private String teacherId;
        @Column(name = "class_id", nullable = false) private String classId;
        @Column(nullable = false) private String subject;
        @Column(name = "child_id", nullable = false) private String childId;
        @Column private Short participation;
        @Column private Short effort;
        @Column private Short behaviour;
        @Column private Short understanding;
        @Column private String comment;
        @Column(name = "going_well") private String goingWell;
        @Column(name = "next_step") private String nextStep;
        @Column(nullable = false) private String state;
        @Column(name = "submitted_at") private Instant submittedAt;
        @Column(name = "reviewed_by") private String reviewedBy;
        @Column(name = "reviewed_at") private Instant reviewedAt;
        @Column(name = "review_comment") private String reviewComment;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getRoundId() { return roundId; } public void setRoundId(String v) { roundId = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public String getClassId() { return classId; } public void setClassId(String v) { classId = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public Short getParticipation() { return participation; } public void setParticipation(Short v) { participation = v; }
        public Short getEffort() { return effort; } public void setEffort(Short v) { effort = v; }
        public Short getBehaviour() { return behaviour; } public void setBehaviour(Short v) { behaviour = v; }
        public Short getUnderstanding() { return understanding; } public void setUnderstanding(Short v) { understanding = v; }
        public String getComment() { return comment; } public void setComment(String v) { comment = v; }
        public String getGoingWell() { return goingWell; } public void setGoingWell(String v) { goingWell = v; }
        public String getNextStep() { return nextStep; } public void setNextStep(String v) { nextStep = v; }
        public String getState() { return state; } public void setState(String v) { state = v; }
        public Instant getSubmittedAt() { return submittedAt; } public void setSubmittedAt(Instant v) { submittedAt = v; }
        public String getReviewedBy() { return reviewedBy; } public void setReviewedBy(String v) { reviewedBy = v; }
        public Instant getReviewedAt() { return reviewedAt; } public void setReviewedAt(Instant v) { reviewedAt = v; }
        public String getReviewComment() { return reviewComment; } public void setReviewComment(String v) { reviewComment = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    /** One reminder per (school, round, teacher), claimed before it is sent — V53's `material_reminders` exactly. */
    @Entity(name = "EvaluationReminderEntity") @Table(name = "evaluation_reminders")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class EvaluationReminderEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "round_id", nullable = false) private String roundId;
        @Column(name = "teacher_id", nullable = false) private String teacherId;
        @Column(name = "sent_at", nullable = false) private Instant sentAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getRoundId() { return roundId; } public void setRoundId(String v) { roundId = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public Instant getSentAt() { return sentAt; } public void setSentAt(Instant v) { sentAt = v; }
    }
}
