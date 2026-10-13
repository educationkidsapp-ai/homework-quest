package quest.server.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/** V32: the phones a parent is pushed to (B4 `backend/fcm-push`); V36: the phones a member of staff is pushed to. */
public final class Entities {
    private Entities() {}

    /**
     * One FCM registration token. Not a tenant row — a parent belongs to no school, her children do — so it carries no
     * `school_id` and no filter; every read starts from `parentId`, which comes from her Firebase token. `token` is
     * unique, so one phone is one parent's at a time.
     */
    @Entity(name = "ParentDeviceEntity") @Table(name = "parent_devices")
    public static class ParentDeviceEntity {
        @Id private String id;
        @Column(name = "parent_id", nullable = false) private String parentId;
        @Column(nullable = false) private String token;
        @Column(nullable = false) private String platform;
        @Column private String locale;
        @Column(name = "app_version") private String appVersion;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getParentId() { return parentId; } public void setParentId(String v) { parentId = v; }
        public String getToken() { return token; } public void setToken(String v) { token = v; }
        public String getPlatform() { return platform; } public void setPlatform(String v) { platform = v; }
        public String getLocale() { return locale; } public void setLocale(String v) { locale = v; }
        public String getAppVersion() { return appVersion; } public void setAppVersion(String v) { appVersion = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getLastSeenAt() { return lastSeenAt; } public void setLastSeenAt(Instant v) { lastSeenAt = v; }
    }

    /**
     * Feature 13 (V36): one FCM registration token of a member of <strong>staff</strong>. The parent's twin above,
     * with one difference that matters: a member of staff belongs to one school, so this <em>is</em> a tenant row and
     * carries the `school` filter, where a parent's phone carries none (she may have children in two schools).
     *
     * <p>`token` is unique across both tables as far as the server is concerned: {@code DeviceService} moves a token
     * between them, so a phone that signs in as a teacher stops receiving the parent's news and the other way round.
     */
    @Entity(name = "StaffDeviceEntity") @Table(name = "staff_devices")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class StaffDeviceEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "user_id", nullable = false) private String userId;
        @Column(nullable = false) private String token;
        @Column(nullable = false) private String platform;
        @Column private String locale;
        @Column(name = "app_version") private String appVersion;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
        public String getToken() { return token; } public void setToken(String v) { token = v; }
        public String getPlatform() { return platform; } public void setPlatform(String v) { platform = v; }
        public String getLocale() { return locale; } public void setLocale(String v) { locale = v; }
        public String getAppVersion() { return appVersion; } public void setAppVersion(String v) { appVersion = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getLastSeenAt() { return lastSeenAt; } public void setLastSeenAt(Instant v) { lastSeenAt = v; }
    }
}
