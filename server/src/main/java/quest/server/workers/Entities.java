package quest.server.workers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

public final class Entities {
    private Entities() {}

    /**
     * A member of staff who does not teach and does not sign in (MA1, V25): the caretaker, the driver, the nurse, the
     * secretary. Tenant table — every read through a scoped request is filtered to the caller's school
     * (`quest.server.tenancy`) — and there is deliberately no `users` row behind it: the owner's item 4 asks for a
     * name, a job and a mobile number, and an account nobody uses is an account nobody rotates.
     *
     * <p>{@code active} retires a row the way `children.active` does. A person who has left is part of the school's
     * record, so `DELETE /admin/workers/{id}` clears the flag and deletes nothing.
     */
    @Entity(name = "WorkerEntity") @Table(name = "workers")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class WorkerEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "full_name", nullable = false) private String fullName;
        @Column(nullable = false) private String job;
        @Column(length = 20) private String phone;
        @Column(nullable = false) private boolean active = true;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getFullName() { return fullName; } public void setFullName(String v) { fullName = v; }
        public String getJob() { return job; } public void setJob(String v) { job = v; }
        public String getPhone() { return phone; } public void setPhone(String v) { phone = v; }
        public boolean isActive() { return active; } public void setActive(boolean v) { active = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
