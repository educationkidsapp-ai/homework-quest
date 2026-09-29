package quest.server.broadcasts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/** V22, widened by V23 with `broadcasts.grade` (MG1): the weekly plan, announcements and events a manager or a coordinator sends out, and who has read them (RM2, DR6). */
public final class Entities {
    private Entities() {}

    /**
     * Tenant table: the `school` filter scopes every dashboard read, and `schoolId` is the author's own, written by
     * {@link BroadcastService} and never taken from a request. A parent's feed runs unfiltered — she carries no tenant
     * scope — and names her child's school in the query instead, exactly as `AnnouncementService.forChild` does.
     *
     * <p>`audienceRoles` and `sectionIds` are comma-separated lists in one column each: nothing joins on them and
     * every read wants all of them. `sectionIds` null means "every section of `curriculum`", which is the manager's
     * department-wide broadcast; a coordinator's row names its sections and carries no curriculum.
     *
     * <p>V23 (MG1): `grade` narrows a department-wide row to one grade of it, and null means every grade — which is
     * what every row written before V23 already meant, so nothing was backfilled. It is never set beside
     * `sectionIds`: those already say which sections are meant.
     */
    @Entity(name = "BroadcastEntity") @Table(name = "broadcasts")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class BroadcastEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "author_user_id", nullable = false) private String authorUserId;
        @Column(name = "author_role", nullable = false) private String authorRole;
        @Column(nullable = false) private String kind;
        @Column(name = "week_start") private LocalDate weekStart;
        @Column private String title;
        @Column(name = "body_en", nullable = false) private String bodyEn;
        @Column(name = "body_ar") private String bodyAr;
        @Column(name = "attachment_url") private String attachmentUrl;
        @Column(name = "attachment_name") private String attachmentName;
        @Column(name = "audience_roles", nullable = false) private String audienceRoles;
        @Column private String curriculum;
        @Column private Integer grade;
        @Column private String subject;
        @Column(name = "section_ids") private String sectionIds;
        @Column(name = "expires_at") private Instant expiresAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getAuthorUserId() { return authorUserId; } public void setAuthorUserId(String v) { authorUserId = v; }
        public String getAuthorRole() { return authorRole; } public void setAuthorRole(String v) { authorRole = v; }
        public String getKind() { return kind; } public void setKind(String v) { kind = v; }
        public LocalDate getWeekStart() { return weekStart; } public void setWeekStart(LocalDate v) { weekStart = v; }
        public String getTitle() { return title; } public void setTitle(String v) { title = v; }
        public String getBodyEn() { return bodyEn; } public void setBodyEn(String v) { bodyEn = v; }
        public String getBodyAr() { return bodyAr; } public void setBodyAr(String v) { bodyAr = v; }
        public String getAttachmentUrl() { return attachmentUrl; } public void setAttachmentUrl(String v) { attachmentUrl = v; }
        public String getAttachmentName() { return attachmentName; } public void setAttachmentName(String v) { attachmentName = v; }
        public String getAudienceRoles() { return audienceRoles; } public void setAudienceRoles(String v) { audienceRoles = v; }
        public String getCurriculum() { return curriculum; } public void setCurriculum(String v) { curriculum = v; }
        public Integer getGrade() { return grade; } public void setGrade(Integer v) { grade = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public String getSectionIds() { return sectionIds; } public void setSectionIds(String v) { sectionIds = v; }
        public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant v) { expiresAt = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /**
     * One row per (broadcast, reader) — the unread rule. `readerId` is a `users` id for a dashboard recipient and a
     * `parents` id for a parent: the two never collide and neither side reads the other's rows, so one table serves
     * both feeds and `unread` is "what I can see, minus these".
     */
    @Entity(name = "BroadcastReadEntity") @Table(name = "broadcast_reads")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class BroadcastReadEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "broadcast_id", nullable = false) private String broadcastId;
        @Column(name = "reader_id", nullable = false) private String readerId;
        @Column(name = "read_at", nullable = false) private Instant readAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getBroadcastId() { return broadcastId; } public void setBroadcastId(String v) { broadcastId = v; }
        public String getReaderId() { return readerId; } public void setReaderId(String v) { readerId = v; }
        public Instant getReadAt() { return readAt; } public void setReadAt(Instant v) { readAt = v; }
    }
}
