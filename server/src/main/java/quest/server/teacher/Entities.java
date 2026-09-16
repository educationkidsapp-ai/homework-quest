package quest.server.teacher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * §6 screens 14 and 16: the questions a teacher sends to her students, the answers children give them, and the
 * announcements she posts to a class. All three are tenant tables — every read through a scoped request is filtered
 * to the caller's school by the `school` filter defined on {@link quest.server.tenancy.Entities.ClassEntity}.
 */
public final class Entities {
    private Entities() {}

    /**
     * A set of §5 stops with a date window and the classes it was sent to. It is a draft until `sentAt` is set;
     * `stopsJson` is a JSON array of `Stop` objects, validated with the shared `SchemaValidator` in lenient mode.
     */
    @Entity(name = "TeacherQuestionEntity") @Table(name = "teacher_questions")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class TeacherQuestionEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "teacher_id", nullable = false) private String teacherId;
        @Column(nullable = false) private String title;
        @Column(name = "stops_json", nullable = false) private String stopsJson = "[]";
        @Column(name = "class_ids_json", nullable = false) private String classIdsJson = "[]";
        @Column(name = "from_date", nullable = false) private LocalDate fromDate;
        @Column(name = "to_date", nullable = false) private LocalDate toDate;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "sent_at") private Instant sentAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public String getTitle() { return title; } public void setTitle(String v) { title = v; }
        public String getStopsJson() { return stopsJson; } public void setStopsJson(String v) { stopsJson = v; }
        public String getClassIdsJson() { return classIdsJson; } public void setClassIdsJson(String v) { classIdsJson = v; }
        public LocalDate getFromDate() { return fromDate; } public void setFromDate(LocalDate v) { fromDate = v; }
        public LocalDate getToDate() { return toDate; } public void setToDate(LocalDate v) { toDate = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getSentAt() { return sentAt; } public void setSentAt(Instant v) { sentAt = v; }
    }

    /** One answered stop of one question by one child; unique on (question, child, stop), so uploads are idempotent. */
    @Entity(name = "TeacherQuestionAnswerEntity") @Table(name = "teacher_question_answers")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class TeacherQuestionAnswerEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "question_id", nullable = false) private String questionId;
        @Column(name = "child_id", nullable = false) private String childId;
        @Column(name = "stop_id", nullable = false) private String stopId;
        @Column(name = "answer_json", nullable = false) private String answerJson = "{}";
        @Column(nullable = false) private boolean correct;
        @Column(nullable = false) private int stars;
        @Column(name = "answered_at", nullable = false) private Instant answeredAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getQuestionId() { return questionId; } public void setQuestionId(String v) { questionId = v; }
        public String getChildId() { return childId; } public void setChildId(String v) { childId = v; }
        public String getStopId() { return stopId; } public void setStopId(String v) { stopId = v; }
        public String getAnswerJson() { return answerJson; } public void setAnswerJson(String v) { answerJson = v; }
        public boolean isCorrect() { return correct; } public void setCorrect(boolean v) { correct = v; }
        public int getStars() { return stars; } public void setStars(int v) { stars = v; }
        public Instant getAnsweredAt() { return answeredAt; } public void setAnsweredAt(Instant v) { answeredAt = v; }
    }

    /** A short bilingual note to the parents of one class, live between `publishedAt` and `expiresAt` (§6 screen 16). */
    @Entity(name = "AnnouncementEntity") @Table(name = "announcements")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AnnouncementEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "teacher_id", nullable = false) private String teacherId;
        @Column(name = "class_id", nullable = false) private String classId;
        @Column(name = "body_en", nullable = false) private String bodyEn;
        @Column(name = "body_ar") private String bodyAr;
        @Column(name = "published_at", nullable = false) private Instant publishedAt;
        @Column(name = "expires_at") private Instant expiresAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public String getClassId() { return classId; } public void setClassId(String v) { classId = v; }
        public String getBodyEn() { return bodyEn; } public void setBodyEn(String v) { bodyEn = v; }
        public String getBodyAr() { return bodyAr; } public void setBodyAr(String v) { bodyAr = v; }
        public Instant getPublishedAt() { return publishedAt; } public void setPublishedAt(Instant v) { publishedAt = v; }
        public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant v) { expiresAt = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
