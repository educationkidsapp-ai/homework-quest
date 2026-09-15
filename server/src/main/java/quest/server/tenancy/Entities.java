package quest.server.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The tenant itself (`schools`) and the unit lessons are published into (`classes`). */
public final class Entities {
    private Entities() {}

    @Entity @Table(name = "schools")
    public static class SchoolEntity {
        @Id private String id;
        @Column(nullable = false) private String name;
        @Column(nullable = false, unique = true) private String code;
        @Column(name = "curriculum_options_json", nullable = false) private String curriculumOptionsJson = "[]";
        @Column(name = "grade_options_json", nullable = false) private String gradeOptionsJson = "[]";
        @Column(name = "theme_json") private String themeJson;
        @Column(name = "feature_flags_json", nullable = false) private String featureFlagsJson = "{}";
        @Column(nullable = false) private String status = "active";
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getCode() { return code; } public void setCode(String v) { code = v; }
        public String getCurriculumOptionsJson() { return curriculumOptionsJson; } public void setCurriculumOptionsJson(String v) { curriculumOptionsJson = v; }
        public String getGradeOptionsJson() { return gradeOptionsJson; } public void setGradeOptionsJson(String v) { gradeOptionsJson = v; }
        public String getThemeJson() { return themeJson; } public void setThemeJson(String v) { themeJson = v; }
        public String getFeatureFlagsJson() { return featureFlagsJson; } public void setFeatureFlagsJson(String v) { featureFlagsJson = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    @Entity @Table(name = "classes")
    public static class ClassEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(nullable = false) private String curriculum;
        @Column(nullable = false) private int grade;
        @Column(nullable = false) private String subject;
        @Column(name = "teacher_id") private String teacherId;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getCurriculum() { return curriculum; } public void setCurriculum(String v) { curriculum = v; }
        public int getGrade() { return grade; } public void setGrade(int v) { grade = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
