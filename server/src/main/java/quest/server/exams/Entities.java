package quest.server.exams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/** V14: the two rows an exam needs beyond the lesson it is (`docs/teacher-flow.md` step 10, teacher prompt §8). */
public final class Entities {
    private Entities() {}

    /**
     * §8's `ExamSettings(lessonId, opensAt, closesAt, level, hintsOff, releaseMode)` — one row per exam lesson.
     *
     * <p>Tenant table: the `school` filter scopes every read to the caller's school, and `schoolId` is the lesson's,
     * written by {@link ExamService} and never taken from a request.
     *
     * <p>Release is <strong>not</strong> here. V13 put `lessons.released_at` and `lessons.release_withdrawn` on the
     * lesson, an exam is a lesson, and the parent's report reads one rule rather than two. What this row says about
     * release is only <em>when it should happen by itself</em> ({@link #releaseMode}).
     */
    @Entity(name = "ExamSettingsEntity") @Table(name = "exam_settings")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ExamSettingsEntity {
        @Id @Column(name = "lesson_id") private String lessonId;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "opens_at", nullable = false) private Instant opensAt;
        @Column(name = "closes_at", nullable = false) private Instant closesAt;
        @Column(nullable = false) private String level = ExamLevels.MIXED;
        @Column(name = "duration_minutes") private Integer durationMinutes;
        @Column(name = "single_attempt", nullable = false) private boolean singleAttempt = true;
        @Column(name = "hints_off", nullable = false) private boolean hintsOff = true;
        @Column(name = "numbers_off", nullable = false) private boolean numbersOff = true;
        @Column(name = "release_mode", nullable = false) private String releaseMode = ExamLevels.AUTO_ON_CLOSE;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public Instant getOpensAt() { return opensAt; } public void setOpensAt(Instant v) { opensAt = v; }
        public Instant getClosesAt() { return closesAt; } public void setClosesAt(Instant v) { closesAt = v; }
        public String getLevel() { return level; } public void setLevel(String v) { level = v; }
        public Integer getDurationMinutes() { return durationMinutes; } public void setDurationMinutes(Integer v) { durationMinutes = v; }
        public boolean isSingleAttempt() { return singleAttempt; } public void setSingleAttempt(boolean v) { singleAttempt = v; }
        public boolean isHintsOff() { return hintsOff; } public void setHintsOff(boolean v) { hintsOff = v; }
        public boolean isNumbersOff() { return numbersOff; } public void setNumbersOff(boolean v) { numbersOff = v; }
        public String getReleaseMode() { return releaseMode; } public void setReleaseMode(String v) { releaseMode = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
        /** §8: the window, as the clock reads it right now. */
        public boolean isOpenAt(Instant now) { return !now.isBefore(opensAt) && now.isBefore(closesAt); }
    }

    /**
     * §8's one attempt per child per exam: when she started it, when she was last seen in it, when it was submitted,
     * and the teacher's one re-opening for a child who was absent or was cut off.
     *
     * <p>The row is created by the child's <em>first</em> upload of answers rather than by a separate "start" call —
     * the app has one write path for a played stop and §8's rule is about the sitting, not about a button. The
     * unique index on (child, exam) is what makes a second sitting a 409 instead of a race between two devices.
     */
    @Entity(name = "ExamAttemptEntity") @Table(name = "exam_attempts")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ExamAttemptEntity {
        public static final String STARTED = "started", SUBMITTED = "submitted";

        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(name = "child_id", nullable = false) private String childId;
        @Column(nullable = false) private String state = STARTED;
        @Column(name = "started_at", nullable = false) private Instant startedAt;
        @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
        @Column(name = "submitted_at") private Instant submittedAt;
        @Column(name = "seconds_taken") private Integer secondsTaken;
        @Column(name = "reopened_at") private Instant reopenedAt;
        @Column(name = "reopened_by") private String reopenedBy;
        @Column(name = "reopen_closes_at") private Instant reopenClosesAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getState() { return state; } public void setState(String v) { state = v; }
        public Instant getStartedAt() { return startedAt; } public void setStartedAt(Instant v) { startedAt = v; }
        public Instant getLastSeenAt() { return lastSeenAt; } public void setLastSeenAt(Instant v) { lastSeenAt = v; }
        public Instant getSubmittedAt() { return submittedAt; } public void setSubmittedAt(Instant v) { submittedAt = v; }
        public Integer getSecondsTaken() { return secondsTaken; } public void setSecondsTaken(Integer v) { secondsTaken = v; }
        public Instant getReopenedAt() { return reopenedAt; } public void setReopenedAt(Instant v) { reopenedAt = v; }
        public String getReopenedBy() { return reopenedBy; } public void setReopenedBy(String v) { reopenedBy = v; }
        public Instant getReopenClosesAt() { return reopenClosesAt; } public void setReopenClosesAt(Instant v) { reopenClosesAt = v; }
        public boolean isSubmitted() { return SUBMITTED.equals(state); }
        /** A child who has already been given her second sitting: §8 gives her one, not a supply of them. */
        public boolean isReopened() { return reopenedAt != null; }
    }
}
