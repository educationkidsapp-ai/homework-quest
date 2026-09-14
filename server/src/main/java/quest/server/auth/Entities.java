package quest.server.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

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

    @Entity @Table(name = "admin_users")
    public static class AdminUserEntity {
        @Id private String id;
        @Column(nullable = false, unique = true) private String email;
        @Column(name = "password_hash", nullable = false) private String passwordHash;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getEmail() { return email; } public void setEmail(String v) { email = v; }
        public String getPasswordHash() { return passwordHash; } public void setPasswordHash(String v) { passwordHash = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
