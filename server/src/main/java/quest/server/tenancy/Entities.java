package quest.server.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

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
        /** V7, N2.1: this school's own teaching days and timezone, or null to follow the platform's (§A). */
        @Column(name = "school_week_json") private String schoolWeekJson;
        @Column private String timezone;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getCode() { return code; } public void setCode(String v) { code = v; }
        public String getCurriculumOptionsJson() { return curriculumOptionsJson; } public void setCurriculumOptionsJson(String v) { curriculumOptionsJson = v; }
        public String getGradeOptionsJson() { return gradeOptionsJson; } public void setGradeOptionsJson(String v) { gradeOptionsJson = v; }
        public String getThemeJson() { return themeJson; } public void setThemeJson(String v) { themeJson = v; }
        public String getFeatureFlagsJson() { return featureFlagsJson; } public void setFeatureFlagsJson(String v) { featureFlagsJson = v; }
        public String getStatus() { return status; } public void setStatus(String v) { status = v; }
        public String getSchoolWeekJson() { return schoolWeekJson; } public void setSchoolWeekJson(String v) { schoolWeekJson = v; }
        public String getTimezone() { return timezone; } public void setTimezone(String v) { timezone = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /**
     * A <strong>section</strong> since V7 (D14): "1A" inside a curriculum and grade, with a join code parents type.
     * Who teaches what moved to {@link TeachingAssignmentEntity}; `subject` and `teacherId` survive only so the V4
     * unique index and the pre-V7 rows still validate, and nothing written from V7 onwards fills them.
     *
     * <p><strong>`name == null` is a legacy row.</strong> V7 turns one old row of every (school, curriculum, grade)
     * into the group's section and leaves the rest untouched but inactive; {@link #isSection()} is the predicate
     * every list, lookup and roster query applies, so a legacy row is never shown, joined or assigned to.
     *
     * <p>Carries the one definition of the `school` filter for the whole session factory (filter definitions are
     * global; every other tenant entity only references it with `@Filter`). See {@link TenantFilter}.
     */
    @Entity(name = "ClassEntity") @Table(name = "classes")
    @FilterDef(name = "school", parameters = @ParamDef(name = "schoolId", type = String.class))
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class ClassEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(nullable = false) private String curriculum;
        @Column(nullable = false) private int grade;
        @Column private String subject;
        @Column(name = "teacher_id") private String teacherId;
        @Column private String name;
        @Column(name = "join_code") private String joinCode;
        @Column(nullable = false) private boolean active = true;
        @Column(name = "join_code_enabled", nullable = false) private boolean joinCodeEnabled = true;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getCurriculum() { return curriculum; } public void setCurriculum(String v) { curriculum = v; }
        public int getGrade() { return grade; } public void setGrade(int v) { grade = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public String getName() { return name; } public void setName(String v) { name = v; }
        public String getJoinCode() { return joinCode; } public void setJoinCode(String v) { joinCode = v; }
        public boolean isActive() { return active; } public void setActive(boolean v) { active = v; }
        public boolean isJoinCodeEnabled() { return joinCodeEnabled; } public void setJoinCodeEnabled(boolean v) { joinCodeEnabled = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        /** True once V7 or the Admin API has named this row; a pre-V7 leftover is never a section. */
        public boolean isSection() { return name != null && !name.isBlank(); }
    }

    /**
     * Who teaches one subject in one section (`docs/teacher-flow.md` §2). The pair is unique in the database, so the
     * Admin API checks it first and answers 409 naming the teacher who holds it rather than leaking a constraint.
     * Tenant table: filtered to the caller's school like every other one.
     */
    @Entity(name = "TeachingAssignmentEntity") @Table(name = "teaching_assignments")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class TeachingAssignmentEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "teacher_id", nullable = false) private String teacherId;
        @Column(name = "class_id", nullable = false) private String classId;
        @Column(nullable = false) private String subject;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getTeacherId() { return teacherId; } public void setTeacherId(String v) { teacherId = v; }
        public String getClassId() { return classId; } public void setClassId(String v) { classId = v; }
        public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
