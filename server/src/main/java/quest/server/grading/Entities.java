package quest.server.grading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

public final class Entities {
    private Entities() {}

    /**
     * §7's `TeacherMark(childId, lessonId, stopId?, stars?, score?, comment, markedAt)` — the 1–3 stars and one-line
     * comment a teacher gives an open stop, and the lesson-level score override and comment to the parent.
     *
     * <p>Tenant table: the `school` filter scopes every read to the caller's school, and `schoolId` is the lesson's,
     * written by {@link GradingService} and never taken from a request.
     *
     * <p>{@link #LESSON} rather than a null `stopId` for the lesson-level row: the unique index on
     * (child, lesson, stop) is what makes a save idempotent, and in PostgreSQL a NULL is never equal to a NULL — a
     * nullable column would let one child collect a new lesson-level row on every save.
     */
    @Entity(name = "TeacherMarkEntity") @Table(name = "teacher_marks")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class TeacherMarkEntity {
        /** The `stopId` of a mark about the whole lesson rather than one stop. */
        public static final String LESSON = "";

        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "child_id", nullable = false) private String childId;
        @Column(name = "lesson_id", nullable = false) private String lessonId;
        @Column(name = "stop_id", nullable = false) private String stopId = LESSON;
        private Integer stars; private Integer score; private String comment;
        @Column(name = "marked_by") private String markedBy;
        @Column(name = "marked_at", nullable = false) private Instant markedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public String getStopId() { return stopId; } public void setStopId(String v) { stopId = v; }
        public Integer getStars() { return stars; } public void setStars(Integer v) { stars = v; }
        public Integer getScore() { return score; } public void setScore(Integer v) { score = v; }
        public String getComment() { return comment; } public void setComment(String v) { comment = v; }
        public String getMarkedBy() { return markedBy; } public void setMarkedBy(String v) { markedBy = v; }
        public Instant getMarkedAt() { return markedAt; } public void setMarkedAt(Instant v) { markedAt = v; }
        public boolean isLessonLevel() { return LESSON.equals(stopId); }
    }
}
