package quest.server.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/** V17: one row of the dashboard bell (E2 `backend/notifications`, D26). */
public final class Entities {
    private Entities() {}

    /**
     * Tenant table: the `school` filter scopes every read to the caller's school, and `schoolId` is the lesson's,
     * written by {@link NotificationService} and never taken from a request. `userId` is the recipient — a
     * dashboard user, never a parent — and every query starts from it, so one user never sees another's row even
     * inside her own school.
     */
    @Entity(name = "NotificationEntity") @Table(name = "notifications")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class NotificationEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "user_id", nullable = false) private String userId;
        @Column(nullable = false) private String kind;
        @Column(nullable = false) private String title;
        @Column private String body;
        @Column private String link;
        @Column(name = "lesson_id") private String lessonId;
        @Column(name = "read_at") private Instant readAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
        public String getKind() { return kind; } public void setKind(String v) { kind = v; }
        public String getTitle() { return title; } public void setTitle(String v) { title = v; }
        public String getBody() { return body; } public void setBody(String v) { body = v; }
        public String getLink() { return link; } public void setLink(String v) { link = v; }
        public String getLessonId() { return lessonId; } public void setLessonId(String v) { lessonId = v; }
        public Instant getReadAt() { return readAt; } public void setReadAt(Instant v) { readAt = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
