package quest.server.structure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Feature 36's five tables (V58): the academy a curriculum key now has a row for, the subjects it offers under its
 * own names, its terms, and its grading scheme with the bands of that scheme. Every one carries `school_id` and the
 * `school` filter, like every other tenant table (`TenantArchitectureTest`), and `IsolationTest` has a case for
 * each.
 *
 * <p>The academy <strong>is</strong> milestone 06's department: `academyKey` is the very `curriculum` value already
 * on `courses`, `classes`, `children`, `teachers` and the managers' `staff_scopes` rows, and nothing here introduces
 * a second concept or a new key — `quest.api.dto.Curriculum` and `quest.api.dto.Subject` are closed enums on the
 * wire and in the released app builds.
 */
public final class Entities {
    private Entities() {}

    /** One academy: a curriculum key with its names, its descriptive type, its grade naming and its director. */
    @Entity(name = "AcademyEntity") @Table(name = "academies")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AcademyEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "academy_key", nullable = false) private String academyKey;
        @Column(name = "name_en", nullable = false) private String nameEn;
        @Column(name = "name_ar", nullable = false) private String nameAr;
        @Column(name = "curriculum_type", nullable = false) private String curriculumType;
        @Column(name = "grade_style", nullable = false) private String gradeStyle;
        @Column(name = "grade_offset", nullable = false) private int gradeOffset;
        @Column(name = "grade_word_en", nullable = false) private String gradeWordEn;
        @Column(name = "grade_word_ar", nullable = false) private String gradeWordAr;
        @Column(name = "director_id") private String directorId;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getAcademyKey() { return academyKey; } public void setAcademyKey(String v) { academyKey = v; }
        public String getNameEn() { return nameEn; } public void setNameEn(String v) { nameEn = v; }
        public String getNameAr() { return nameAr; } public void setNameAr(String v) { nameAr = v; }
        public String getCurriculumType() { return curriculumType; } public void setCurriculumType(String v) { curriculumType = v; }
        public String getGradeStyle() { return gradeStyle; } public void setGradeStyle(String v) { gradeStyle = v; }
        public int getGradeOffset() { return gradeOffset; } public void setGradeOffset(int v) { gradeOffset = v; }
        public String getGradeWordEn() { return gradeWordEn; } public void setGradeWordEn(String v) { gradeWordEn = v; }
        public String getGradeWordAr() { return gradeWordAr; } public void setGradeWordAr(String v) { gradeWordAr = v; }
        public String getDirectorId() { return directorId; } public void setDirectorId(String v) { directorId = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    /** One of the existing `Subject` keys, offered by one academy under its own two names. */
    @Entity(name = "AcademySubjectEntity") @Table(name = "academy_subjects")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AcademySubjectEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "academy_key", nullable = false) private String academyKey;
        @Column(name = "subject_key", nullable = false) private String subjectKey;
        @Column(name = "name_en", nullable = false) private String nameEn;
        @Column(name = "name_ar", nullable = false) private String nameAr;
        @Column(nullable = false) private boolean ministry;
        @Column(nullable = false) private int position;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getAcademyKey() { return academyKey; } public void setAcademyKey(String v) { academyKey = v; }
        public String getSubjectKey() { return subjectKey; } public void setSubjectKey(String v) { subjectKey = v; }
        public String getNameEn() { return nameEn; } public void setNameEn(String v) { nameEn = v; }
        public String getNameAr() { return nameAr; } public void setNameAr(String v) { nameAr = v; }
        public boolean isMinistry() { return ministry; } public void setMinistry(boolean v) { ministry = v; }
        public int getPosition() { return position; } public void setPosition(int v) { position = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /** A term of one academy inside one school year; no two of them overlap (`AcademyService.saveTerms`). */
    @Entity(name = "AcademyTermEntity") @Table(name = "academy_terms")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class AcademyTermEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "academy_key", nullable = false) private String academyKey;
        @Column(name = "school_year", nullable = false) private String schoolYear;
        @Column(name = "name_en", nullable = false) private String nameEn;
        @Column(name = "name_ar", nullable = false) private String nameAr;
        @Column(name = "starts_on", nullable = false) private LocalDate startsOn;
        @Column(name = "ends_on", nullable = false) private LocalDate endsOn;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getAcademyKey() { return academyKey; } public void setAcademyKey(String v) { academyKey = v; }
        public String getSchoolYear() { return schoolYear; } public void setSchoolYear(String v) { schoolYear = v; }
        public String getNameEn() { return nameEn; } public void setNameEn(String v) { nameEn = v; }
        public String getNameAr() { return nameAr; } public void setNameAr(String v) { nameAr = v; }
        public LocalDate getStartsOn() { return startsOn; } public void setStartsOn(LocalDate v) { startsOn = v; }
        public LocalDate getEndsOn() { return endsOn; } public void setEndsOn(LocalDate v) { endsOn = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }

    /** One academy's grading scheme; an academy without a row reads {@link GradingSchemes#DEFAULT}. */
    @Entity(name = "GradingSchemeEntity") @Table(name = "grading_schemes")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class GradingSchemeEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "academy_key", nullable = false) private String academyKey;
        @Column(nullable = false) private String kind;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getAcademyKey() { return academyKey; } public void setAcademyKey(String v) { academyKey = v; }
        public String getKind() { return kind; } public void setKind(String v) { kind = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
        public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    }

    /** One band of a scheme: its key, its lower threshold over 0–100 and its name in both languages. */
    @Entity(name = "GradingBandEntity") @Table(name = "grading_bands")
    @Filter(name = "school", condition = "school_id = :schoolId")
    public static class GradingBandEntity {
        @Id private String id;
        @Column(name = "school_id", nullable = false) private String schoolId;
        @Column(name = "scheme_id", nullable = false) private String schemeId;
        @Column(name = "band_key", nullable = false) private String bandKey;
        @Column(name = "lower_bound", nullable = false) private int lowerBound;
        @Column(name = "name_en", nullable = false) private String nameEn;
        @Column(name = "name_ar", nullable = false) private String nameAr;
        @Column(nullable = false) private int position;
        @Column(name = "created_at", nullable = false) private Instant createdAt;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getSchoolId() { return schoolId; } public void setSchoolId(String v) { schoolId = v; }
        public String getSchemeId() { return schemeId; } public void setSchemeId(String v) { schemeId = v; }
        public String getBandKey() { return bandKey; } public void setBandKey(String v) { bandKey = v; }
        public int getLowerBound() { return lowerBound; } public void setLowerBound(int v) { lowerBound = v; }
        public String getNameEn() { return nameEn; } public void setNameEn(String v) { nameEn = v; }
        public String getNameAr() { return nameAr; } public void setNameAr(String v) { nameAr = v; }
        public int getPosition() { return position; } public void setPosition(int v) { position = v; }
        public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    }
}
