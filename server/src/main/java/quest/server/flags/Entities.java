package quest.server.flags;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** §4: the flag definitions, the per-school overrides and the audit trail of who flipped what. */
public final class Entities {
    private Entities() {}

    /** One row per flag key. `defaultOn` is what a school with no override of its own gets. */
    @Entity(name = "FeatureFlagEntity") @Table(name = "feature_flags")
    public static class FeatureFlagEntity {
        @Id @Column(name = "flag_key") private String key;
        @Column(nullable = false) private String description = "";
        @Column(name = "default_on", nullable = false) private boolean defaultOn;
        @Column(name = "rollout_stage", nullable = false) private String rolloutStage = "internal";
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getKey() { return key; } public void setKey(String v) { key = v; }
        public String getDescription() { return description; } public void setDescription(String v) { description = v; }
        public boolean isDefaultOn() { return defaultOn; } public void setDefaultOn(boolean v) { defaultOn = v; }
        public String getRolloutStage() { return rolloutStage; } public void setRolloutStage(String v) { rolloutStage = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /**
     * A school's override of one flag. It carries `school_id` and is deliberately <em>not</em> `@Filter("school")`:
     * almost every read of it runs outside a school scope on purpose — the public `GET /schools/{id}/flags` the app
     * calls before anyone signs in, the parent's child-school lookup in {@link FeatureFlagInterceptor}, and the
     * Admin's matrix across every school. Each of those reads names its `school_id` explicitly, and
     * {@link FlagService} scopes the matrix for a MANAGERIAL caller to her own school. `TenantArchitectureTest`
     * lists this entity (and {@link FlagAuditEntity}) beside `invites` and `audit_log` for the same reason.
     */
    @Entity(name = "SchoolFeatureFlagEntity") @Table(name = "school_feature_flags")
    @IdClass(SchoolFlagId.class)
    public static class SchoolFeatureFlagEntity {
        @Id @Column(name = "school_id") private String schoolId;
        @Id @Column(name = "flag_key") private String flagKey;
        @Column(nullable = false) private boolean enabled;
        @Column(name = "updated_by") private String updatedBy;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getFlagKey() { return flagKey; } public void setFlagKey(String v) { flagKey = v; }
        public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
        public String getUpdatedBy() { return updatedBy; } public void setUpdatedBy(String v) { updatedBy = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    /** The composite key of {@link SchoolFeatureFlagEntity}. */
    public static class SchoolFlagId implements Serializable {
        private String schoolId; private String flagKey;
        public SchoolFlagId() {}
        public SchoolFlagId(String schoolId, String flagKey) { this.schoolId = schoolId; this.flagKey = flagKey; }
        @Override public boolean equals(Object o) { return o instanceof SchoolFlagId k && Objects.equals(schoolId, k.schoolId) && Objects.equals(flagKey, k.flagKey); }
        @Override public int hashCode() { return Objects.hash(schoolId, flagKey); }
    }

    /** One row per flip. `schoolId` null means the "enable/disable for all" column action (§4). */
    @Entity(name = "FlagAuditEntity") @Table(name = "flag_audit")
    public static class FlagAuditEntity {
        @Id private String id;
        @Column(name = "flag_key", nullable = false) private String flagKey;
        @Column(name = "school_id") private String schoolId;
        @Column(nullable = false) private boolean enabled;
        @Column(name = "actor_user_id") private String actorUserId;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getFlagKey() { return flagKey; } public void setFlagKey(String v) { flagKey = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
        public String getActorUserId() { return actorUserId; } public void setActorUserId(String v) { actorUserId = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
