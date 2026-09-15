package quest.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

public final class Entities {
    private Entities() {}

    @Entity @Table(name = "parents")
    public static class ParentEntity {
        @Id private String id;
        @Column(name = "firebase_uid", nullable = false, unique = true) private String firebaseUid;
        @Column(nullable = false) private String email;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getFirebaseUid() { return firebaseUid; } public void setFirebaseUid(String v) { firebaseUid = v; }
        public String getEmail() { return email; } public void setEmail(String v) { email = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /**
     * Every dashboard user: ADMIN (platform owner, no school), TEACHER and MANAGERIAL (one school each). Tenant table:
     * a query from a scoped request only sees that school's users, which is the second lock under `UserService.list`'s
     * own scope. `school_id` is null on the ADMIN row, so the platform owner is invisible to a school's queries — and
     * `findById` (which Hibernate filters never touch) is what the flows that must reach any row use, sign-in included.
     */
    @Entity @Table(name = "users")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class UserEntity {
        @Id private String id;
        @Column(name = "school_id") private String schoolId;
        @Column(nullable = false, unique = true) private String email;
        @Column(name = "password_hash", nullable = false) private String passwordHash;
        @Column(nullable = false) private String role;
        @Column(nullable = false) private String status = "active";
        @Column(name = "must_change_password", nullable = false) private boolean mustChangePassword;
        @Column(name = "display_name") private String displayName;
        @Column(name = "photo_url") private String photoUrl;
        @Column(nullable = false) private String language = "en";
        @Column(name = "last_login_at") private Instant lastLoginAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getEmail() { return email; } public void setEmail(String v) { email = v; }
        public String getPasswordHash() { return passwordHash; } public void setPasswordHash(String v) { passwordHash = v; }
        public String getRole() { return role; } public void setRole(String v) { role = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public boolean isMustChangePassword() { return mustChangePassword; } public void setMustChangePassword(boolean v) { mustChangePassword = v; }
        public String getDisplayName() { return displayName; } public void setDisplayName(String v) { displayName = v; }
        public String getPhotoUrl() { return photoUrl; } public void setPhotoUrl(String v) { photoUrl = v; }
        public String getLanguage() { return language; } public void setLanguage(String v) { language = v; }
        public Instant getLastLoginAt() { return lastLoginAt; } public void setLastLoginAt(Instant v) { lastLoginAt = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    /** The public half of a TEACHER account (§5): what parents and children see on the teacher island. */
    @Entity @Table(name = "teachers")
    public static class TeacherEntity {
        @Id @Column(name = "user_id") private String userId;
        @Column(name = "subjects_json", nullable = false) private String subjectsJson = "[]";
        private String curriculum;
        @Column(name = "grades_json", nullable = false) private String gradesJson = "[]";
        @Column(name = "bio_en") private String bioEn; @Column(name = "bio_ar") private String bioAr;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
        public String getSubjectsJson() { return subjectsJson; } public void setSubjectsJson(String v) { subjectsJson = v; }
        public String getCurriculum() { return curriculum; } public void setCurriculum(String v) { curriculum = v; }
        public String getGradesJson() { return gradesJson; } public void setGradesJson(String v) { gradesJson = v; }
        public String getBioEn() { return bioEn; } public void setBioEn(String v) { bioEn = v; }
        public String getBioAr() { return bioAr; } public void setBioAr(String v) { bioAr = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    @Entity @Table(name = "refresh_tokens")
    public static class RefreshTokenEntity {
        @Id private String id;
        @Column(name = "user_id", nullable = false) private String userId;
        @Column(name = "token_hash", nullable = false, unique = true) private String tokenHash;
        @Column(name = "expires_at", nullable = false) private Instant expiresAt;
        @Column(name = "revoked_at") private Instant revokedAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
        public String getTokenHash() { return tokenHash; } public void setTokenHash(String v) { tokenHash = v; }
        public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant v) { expiresAt = v; }
        public Instant getRevokedAt() { return revokedAt; } public void setRevokedAt(Instant v) { revokedAt = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    @Entity @Table(name = "invites")
    public static class InviteEntity {
        @Id private String id;
        @Column(name = "school_id") private String schoolId;
        @Column(nullable = false) private String email;
        @Column(nullable = false) private String role;
        @Column(name = "token_hash", nullable = false, unique = true) private String tokenHash;
        @Column(name = "invited_by", nullable = false) private String invitedBy;
        @Column(name = "expires_at", nullable = false) private Instant expiresAt;
        @Column(name = "accepted_at") private Instant acceptedAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getEmail() { return email; } public void setEmail(String v) { email = v; }
        public String getRole() { return role; } public void setRole(String v) { role = v; }
        public String getTokenHash() { return tokenHash; } public void setTokenHash(String v) { tokenHash = v; }
        public String getInvitedBy() { return invitedBy; } public void setInvitedBy(String v) { invitedBy = v; }
        public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant v) { expiresAt = v; }
        public Instant getAcceptedAt() { return acceptedAt; } public void setAcceptedAt(Instant v) { acceptedAt = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    @Entity @Table(name = "audit_log")
    public static class AuditLogEntity {
        @Id private String id;
        @Column(name = "actor_user_id") private String actorUserId;
        @Column(nullable = false) private String action;
        @Column(name = "target_type") private String targetType; @Column(name = "target_id") private String targetId;
        @Column(name = "school_id") private String schoolId;
        @Column(name = "details_json", nullable = false) private String detailsJson = "{}";
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getActorUserId() { return actorUserId; } public void setActorUserId(String v) { actorUserId = v; }
        public String getAction() { return action; } public void setAction(String v) { action = v; }
        public String getTargetType() { return targetType; } public void setTargetType(String v) { targetType = v; }
        public String getTargetId() { return targetId; } public void setTargetId(String v) { targetId = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getDetailsJson() { return detailsJson; } public void setDetailsJson(String v) { detailsJson = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
