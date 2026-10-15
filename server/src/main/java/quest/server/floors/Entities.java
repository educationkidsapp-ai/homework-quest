package quest.server.floors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Feature 20 (V42): a <strong>floor</strong> — a named group of sections inside one academy — the sections on it, and
 * the supervisors who hold it.
 *
 * <p>All three carry `school_id` and the `school` filter (defined once on
 * {@link quest.server.tenancy.Entities.ClassEntity}), so a floor of another school is invisible to every repository
 * query here rather than merely absent from a `where`. `tenancy/IsolationTest` pins it.
 */
public final class Entities {
    private Entities() {}

    /** One floor. `academy` is a curriculum word (`british`, `american`), as `staff_scopes.curriculum` is. */
    @Entity(name = "FloorEntity") @Table(name = "floors")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class FloorEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(nullable = false) private String name;
        @Column(nullable = false) private String academy;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getAcademy() { return academy; } public void setAcademy(String v) { academy = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /** One section on one floor. Unique on `(school_id, section_id)`: a class is on at most one floor. */
    @Entity(name = "FloorClassEntity") @Table(name = "floor_classes")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class FloorClassEntity {
        @Id private String id;
        @Column(name = "floor_id", nullable = false) private String floorId;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "section_id", nullable = false) private String sectionId;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getFloorId() { return floorId; } public void setFloorId(String v) { floorId = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getSectionId() { return sectionId; } public void setSectionId(String v) { sectionId = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /** One supervisor of one floor; a floor may have several and a supervisor several floors. */
    @Entity(name = "FloorSupervisorEntity") @Table(name = "floor_supervisors")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class FloorSupervisorEntity {
        @Id private String id;
        @Column(name = "floor_id", nullable = false) private String floorId;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "user_id", nullable = false) private String userId;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getFloorId() { return floorId; } public void setFloorId(String v) { floorId = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
