package quest.server.hints;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/** Feature 30 (V52): the hints of a stop and the practice plays of a lesson, both unapproved until a teacher says so. */
public final class Entities {
    private Entities() {}

    /**
     * The two graded hints of one stop, written by the lesson's `HINTS` step.
     *
     * <p>Keyed by the stop, so the row goes when the play that holds it is rewritten (`ON DELETE CASCADE`): hints
     * about a question that no longer exists are worse than none, and an edited stop's hints are not its successor's.
     * {@link #approvedBy} and {@link #approvedAt} are the whole of "the teacher has read this" — editing the hints
     * clears both, so an edit can never publish itself.
     */
    @Entity(name = "StopHintsEntity") @Table(name = "stop_hints")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StopHintsEntity {
        @Id @Column(name = "stop_id") private String stopId;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "hints_json", nullable = false) private String hintsJson;
        @Column(name = "approved_by") private String approvedBy;
        @Column(name = "approved_at") private Instant approvedAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getStopId() { return stopId; } public void setStopId(String v) { stopId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getHintsJson() { return hintsJson; } public void setHintsJson(String v) { hintsJson = v; }
        public String getApprovedBy() { return approvedBy; } public void setApprovedBy(String v) { approvedBy = v; }
        public Instant getApprovedAt() { return approvedAt; } public void setApprovedAt(Instant v) { approvedAt = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        /** Only an approved row is ever published (`LessonStore.assemble`). */
        public boolean isApproved() { return approvedAt != null; }
    }

    /**
     * Which of a lesson's plays is a targeted practice set, and which confirmed skill it practises.
     *
     * <p>The play itself is an ordinary `plays` row — level 1, variant {@code Play.PRACTICE_VARIANT} and up — so this
     * table stores no content at all. Rejecting a set deletes that play and the cascade takes this row with it.
     */
    @Entity(name = "PracticePlayEntity") @Table(name = "practice_plays")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class PracticePlayEntity {
        @Id @Column(name = "play_id") private String playId;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "skill_id", nullable = false) private String skillId;
        @Column(name = "approved_by") private String approvedBy;
        @Column(name = "approved_at") private Instant approvedAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getPlayId() { return playId; } public void setPlayId(String v) { playId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getSkillId() { return skillId; } public void setSkillId(String v) { skillId = v; }
        public String getApprovedBy() { return approvedBy; } public void setApprovedBy(String v) { approvedBy = v; }
        public Instant getApprovedAt() { return approvedAt; } public void setApprovedAt(Instant v) { approvedAt = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public boolean isApproved() { return approvedAt != null; }
    }
}
