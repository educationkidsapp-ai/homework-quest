package quest.server.structure;

import java.util.List;

/**
 * The Java mirror of `quest.api.dashboard.Structure.kt` (feature 36) — the Admin's academies, the one visual tree,
 * and the three bulk tools. Encoded by Jackson like every other dashboard DTO; the shared Kotlin file is what the
 * Angular client is generated against.
 *
 * <p>Dates are `yyyy-MM-dd`. A `band` key everywhere in this file is a <em>scheme</em> band key, which is the legacy
 * `Bands` word for the default scheme and whatever the Admin typed for a custom one.
 */
public final class StructureDto {
    private StructureDto() {}

    /** The descriptive "curriculum type" of an academy — a setting, never the closed wire enum. */
    public static final String BRITISH = "BRITISH", AMERICAN = "AMERICAN", MINISTRY = "MINISTRY", OTHER = "OTHER";
    public static final List<String> CURRICULUM_TYPES = List.of(BRITISH, AMERICAN, MINISTRY, OTHER);

    /** How grades are named: "Year 7" or "Grade 6". */
    public static final String YEAR = "YEAR", GRADE = "GRADE";
    public static final List<String> GRADE_STYLES = List.of(YEAR, GRADE);

    /** A grading scheme is letters (A*–U) or numbers; either way a parent reads the band's name, never the score. */
    public static final String LETTER = "LETTER", NUMERIC = "NUMERIC";
    public static final List<String> SCHEME_KINDS = List.of(LETTER, NUMERIC);

    // ---------------------------------------------------------------- academies

    /** How a grade is called in one academy: the word plus `grade + offset` (British Year *n* = grade *n − 1*). */
    public record GradeNaming(String style, int offset, String wordEn, String wordAr) {}

    /**
     * A member of staff on the structure: who she is, not what she may do.
     *
     * <p><strong>The names in this file are the OpenAPI schema names</strong> — springdoc takes a record's simple
     * name — so the generic ones carry the `Structure` prefix and match `quest.api.dashboard.Structure.kt` exactly.
     * Without it this record silently replaced `StaffAttendanceDto.StaffRef(kind, id)` in `server/openapi.json` and
     * broke the generated client of feature 14's Attendance settings page.
     */
    public record StructureStaffRef(String userId, String name, String email) {}

    public record AcademySubject(String subject, String nameEn, String nameAr, boolean ministry) {}

    public record AcademyTerm(String id, String schoolYear, String nameEn, String nameAr, String startsOn, String endsOn) {}

    /** One band of a scheme. `lower` is its lower threshold over 0–100; the lowest band's is 0. */
    public record GradingBand(String band, int lower, String nameEn, String nameAr) {}

    /**
     * An academy's scheme as every reader sees it. `custom` is false for {@link GradingSchemes#DEFAULT}, which is
     * exactly today's four bands, so a client can tell "the school has not chosen" from "the school chose these".
     */
    public record GradingScheme(String academyKey, String kind, boolean custom, List<GradingBand> bands) {

        /** The English name of a band key, for the export columns that print a word rather than a key. */
        public String nameOf(String band) {
            if (band == null) return null;
            for (var b : bands) if (b.band().equals(band)) return b.nameEn();
            return band;
        }

        /** The English name of the band a score falls in — the highest band it reaches, the lowest otherwise. */
        public String nameFor(Double score) {
            if (score == null || bands.isEmpty()) return null;
            var found = bands.getFirst();
            for (var b : bands) if (score >= b.lower()) found = b;
            return found.nameEn();
        }
    }

    public record Academy(String academyKey, String nameEn, String nameAr, String curriculumType, GradeNaming naming,
                          StructureStaffRef director, GradingScheme scheme, List<AcademySubject> subjects,
                          List<AcademyTerm> terms) {}

    public record UpdateAcademyRequest(String nameEn, String nameAr, String curriculumType, String gradeStyle,
                                       Integer gradeOffset, String gradeWordEn, String gradeWordAr, String directorId) {}

    public record SaveSubjectsRequest(List<AcademySubject> subjects) {}

    public record SaveTermsRequest(List<AcademyTerm> terms) {}

    public record SaveSchemeRequest(String kind, List<GradingBand> bands, Boolean confirm) {}

    // ---------------------------------------------------------------- the tree

    /** A (class, subject) slot. `teacher` is null when nobody teaches it — the empty spots are half the point. */
    public record StructureSubjectSlot(String subject, String nameEn, String nameAr, StructureStaffRef teacher,
                                       List<StructureStaffRef> coordinators) {}

    public record StructureClassNode(String classId, String name, int grade, int children,
                                     List<StructureStaffRef> floorSupervisors, List<StructureSubjectSlot> subjects) {}

    public record StructureGradeNode(int grade, String labelEn, String labelAr, List<StructureClassNode> classes) {}

    public record StructureAcademyNode(Academy academy, List<StructureGradeNode> grades) {}

    /** `GET /admin/structure`: academy → grades → classes → teachers per subject, in one read. */
    public record StructureTreeView(List<StructureAcademyNode> academies) {}

    // ---------------------------------------------------------------- moving a teacher

    /**
     * `PUT /admin/structure/assignments` — the drag. The teacher is the one who holds the source slot, so the
     * request names slots rather than people and cannot move somebody else by accident.
     */
    public record MoveAssignmentRequest(String fromClassId, String fromSubject, String toClassId, String toSubject,
                                        Boolean replace) {}

    public record MoveAssignmentResult(String classId, String subject, StructureStaffRef teacher,
                                       StructureStaffRef replaced) {}

    // ---------------------------------------------------------------- bulk tools

    public record NextYearClass(String fromClassId, String name, int fromGrade, Integer toGrade, String toLabelEn,
                               String toLabelAr, int children, boolean graduating) {}

    /** `POST /admin/structure/next-year/preview` — what `/apply` would create, listed before anything is written. */
    public record NextYearPreview(String academyKey, boolean carryChildren, List<NextYearClass> classes,
                                  int classesCreated, int childrenMoved, int graduating) {}

    public record NextYearRequest(String academyKey, Boolean carryChildren) {}

    public record GradeNamesRequest(String academyKey, String style, Integer offset, String wordEn, String wordAr) {}

    /** The labels every grade of the academy now reads — one line per grade, which is what the dialog shows. */
    public record GradeLabel(int grade, String labelEn, String labelAr) {}

    public record GradeNamesResult(String academyKey, GradeNaming naming, List<GradeLabel> grades) {}

    /**
     * `POST /admin/structure/classes/{id}/move/preview` — everything that changes, in the spec's own order: the
     * class's curriculum, its children, the assignments whose subject the target academy does not offer, the
     * director and coordinators who will see it, and its grading scheme.
     */
    public record MoveClassPreview(String classId, String name, String fromAcademyKey, String toAcademyKey,
                                   int children, List<StructureSubjectSlot> droppedAssignments,
                                   StructureStaffRef fromDirector, StructureStaffRef toDirector,
                                   List<StructureStaffRef> fromCoordinators, List<StructureStaffRef> toCoordinators,
                                   GradingScheme fromScheme, GradingScheme toScheme, int releasedResults,
                                   int rebandedResults) {}

    public record MoveClassRequest(String toAcademyKey) {}
}
