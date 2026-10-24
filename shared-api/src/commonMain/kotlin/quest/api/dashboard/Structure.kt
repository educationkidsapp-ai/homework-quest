package quest.api.dashboard

import kotlinx.serialization.Serializable

/**
 * Feature 36 `school structure builder` (docs/features/36-school-structure-builder.md): the Admin models the school as
 * it really is — academies such as British and American, each with its own curriculum settings — and reads one visual
 * tree of who is responsible for what, editing it in place.
 *
 * Behind the `schoolStructure` flag, and `structure.manage` is the **Admin's alone**: every `/admin/structure` route
 * requires her, and the other roles keep the read-only views they already have.
 *
 * **The academies are milestone 06's departments.** [Academy.academyKey] *is* the `curriculum` value already carried by
 * `courses`, `classes`, `children`, `teachers` and the managers' `staff_scopes` rows — [quest.api.dto.Curriculum]'s own
 * `british` / `american`. There is no second concept and **no new key**: `Curriculum` and [quest.api.dto.Subject] are
 * closed enums here and in the released app builds, so adding a third academy or a brand-new subject key is a contract
 * change and a leftover of this feature, not a setting. An academy's "curriculum type" ([Academy.curriculumType]) is a
 * descriptive setting, and its subjects are a choice among the existing `Subject` keys under the academy's own names.
 *
 * **Bands.** Every DTO that carries a band keeps its existing key and gains an additive `bandLabel`, which is the band
 * key of the class's academy scheme; the scheme itself travels beside the rows so a client can show its two names. An
 * academy with no scheme reads the **default**, which is exactly today's four bands (emerging, developing, secure,
 * exceeding at 0, 40, 60 and 85), so nothing that reads a band today changes. [quest.api.progress.ProgressBands] — what
 * a parent and a child see in the app — is untouched.
 *
 * Dates are `yyyy-MM-dd`.
 */

/** An academy's descriptive curriculum type. Never the wire enum, which stays closed. */
object AcademyCurriculumType {
    const val BRITISH = "BRITISH"
    const val AMERICAN = "AMERICAN"
    const val MINISTRY = "MINISTRY"
    const val OTHER = "OTHER"
    val ALL = listOf(BRITISH, AMERICAN, MINISTRY, OTHER)
}

/** How grades are named: "Year 7" or "Grade 6". */
object GradeStyle {
    const val YEAR = "YEAR"
    const val GRADE = "GRADE"
    val ALL = listOf(YEAR, GRADE)
}

/** A grading scheme is letters (A*–U) or numbers; a parent reads the band's name either way, never the score. */
object GradingSchemeKind {
    const val LETTER = "LETTER"
    const val NUMERIC = "NUMERIC"
    val ALL = listOf(LETTER, NUMERIC)
}

/**
 * How a grade is called in one academy: the word plus `grade + offset`. British `YEAR` with [offset] 1 shows grade 1
 * as "Year 2" where American `GRADE` with [offset] 0 shows it as "Grade 1".
 */
@Serializable
data class GradeNaming(
    val style: String = GradeStyle.GRADE,
    val offset: Int = 0,
    val wordEn: String = "Grade",
    val wordAr: String = "الصف",
)

/**
 * A member of staff on the structure: who she is, not what she may do.
 *
 * The generic names in this file carry the `Structure` prefix and are **identical to the server's record names**,
 * which are the OpenAPI schema names the Angular client is generated from. Without the prefix this one collided with
 * feature 14's `StaffRef(kind, id)` in `server/openapi.json`.
 */
@Serializable
data class StructureStaffRef(val userId: String, val name: String, val email: String? = null)

/** One of the existing [quest.api.dto.Subject] keys, offered by one academy under its own two names. */
@Serializable
data class AcademySubject(
    val subject: String,
    val nameEn: String,
    val nameAr: String,
    val ministry: Boolean = false,
)

/** A term of one academy inside one school year. No two terms of an academy overlap inside a year (`409`). */
@Serializable
data class AcademyTerm(
    val id: String? = null,
    val schoolYear: String,
    val nameEn: String,
    val nameAr: String,
    val startsOn: String,
    val endsOn: String,
)

/** One band of a scheme. [lower] is its lower threshold over 0–100, and the lowest band's is always 0. */
@Serializable
data class GradingBand(val band: String, val lower: Int, val nameEn: String, val nameAr: String)

/**
 * An academy's scheme as every reader of a band gets it. [custom] is false for the default — exactly today's four
 * bands — so a client can tell "the school has not chosen" from "the school chose these".
 */
@Serializable
data class GradingScheme(
    val academyKey: String? = null,
    val kind: String = GradingSchemeKind.LETTER,
    val custom: Boolean = false,
    val bands: List<GradingBand> = emptyList(),
)

/** One academy: `GET /admin/structure/academies`, and the settings panel of a node in the tree. */
@Serializable
data class Academy(
    val academyKey: String,
    val nameEn: String,
    val nameAr: String,
    val curriculumType: String = AcademyCurriculumType.OTHER,
    val naming: GradeNaming = GradeNaming(),
    val director: StructureStaffRef? = null,
    val scheme: GradingScheme = GradingScheme(),
    val subjects: List<AcademySubject> = emptyList(),
    val terms: List<AcademyTerm> = emptyList(),
)

/** `PUT /admin/structure/academies/{key}`: every field is optional and only what is sent changes. */
@Serializable
data class UpdateAcademyRequest(
    val nameEn: String? = null,
    val nameAr: String? = null,
    val curriculumType: String? = null,
    val gradeStyle: String? = null,
    val gradeOffset: Int? = null,
    val gradeWordEn: String? = null,
    val gradeWordAr: String? = null,
    /** The department manager who is the academy director; `""` takes the academy's director away. */
    val directorId: String? = null,
)

/** `PUT /admin/structure/academies/{key}/subjects`: the complete list the academy offers afterwards. */
@Serializable
data class SaveSubjectsRequest(val subjects: List<AcademySubject> = emptyList())

/** `PUT /admin/structure/academies/{key}/terms`: the complete list of the academy's terms afterwards. */
@Serializable
data class SaveTermsRequest(val terms: List<AcademyTerm> = emptyList())

/**
 * `PUT /admin/structure/academies/{key}/grading-scheme`. A scheme that would show a released result in a different
 * band answers `409 confirm_released` with the count, until [confirm] is true.
 */
@Serializable
data class SaveSchemeRequest(
    val kind: String = GradingSchemeKind.LETTER,
    val bands: List<GradingBand> = emptyList(),
    val confirm: Boolean = false,
)

// ---------------------------------------------------------------------------------------------- the tree

/** A (class, subject) slot. A null [teacher] is a subject nobody teaches — one of the spec's visible empty spots. */
@Serializable
data class StructureSubjectSlot(
    val subject: String,
    val nameEn: String,
    val nameAr: String,
    val teacher: StructureStaffRef? = null,
    val coordinators: List<StructureStaffRef> = emptyList(),
)

@Serializable
data class StructureClassNode(
    val classId: String,
    val name: String? = null,
    val grade: Int = 0,
    val children: Int = 0,
    /** From feature 20, and only while its own `floorSupervisor` switch is on. */
    val floorSupervisors: List<StructureStaffRef> = emptyList(),
    val subjects: List<StructureSubjectSlot> = emptyList(),
)

/** A grade of an academy, drawn even with no class in it, under the academy's own naming. */
@Serializable
data class StructureGradeNode(
    val grade: Int,
    val labelEn: String,
    val labelAr: String,
    val classes: List<StructureClassNode> = emptyList(),
)

@Serializable
data class StructureAcademyNode(val academy: Academy, val grades: List<StructureGradeNode> = emptyList())

/** `GET /admin/structure`: academy → grades → classes → teachers per subject, in one read. */
@Serializable
data class StructureTreeView(val academies: List<StructureAcademyNode> = emptyList())

/**
 * `PUT /admin/structure/assignments` — the drag. The teacher moved is whoever holds the source slot, so the request
 * names slots rather than people. Dropping on a slot another teacher already holds answers `409 replace_required`
 * naming her, until [replace] is true.
 */
@Serializable
data class MoveAssignmentRequest(
    val fromClassId: String,
    val fromSubject: String,
    val toClassId: String,
    val toSubject: String,
    val replace: Boolean = false,
)

@Serializable
data class MoveAssignmentResult(
    val classId: String,
    val subject: String,
    val teacher: StructureStaffRef? = null,
    /** The teacher whose assignment was replaced, when [MoveAssignmentRequest.replace] was sent. */
    val replaced: StructureStaffRef? = null,
)

// ---------------------------------------------------------------------------------------------- bulk tools

/** One line of the next-year preview. A class in the academy's top grade is [graduating] and nothing is created. */
@Serializable
data class NextYearClass(
    val fromClassId: String,
    val name: String? = null,
    val fromGrade: Int = 0,
    val toGrade: Int? = null,
    val toLabelEn: String? = null,
    val toLabelAr: String? = null,
    val children: Int = 0,
    val graduating: Boolean = false,
)

/**
 * `POST /admin/structure/next-year/preview` and `/apply`: each class moves up one grade with the same name pattern,
 * the children optionally carried over, and **the teachers not carried over**. `/apply` answers the same shape as the
 * preview it just wrote.
 */
@Serializable
data class NextYearPreview(
    val academyKey: String,
    val carryChildren: Boolean = false,
    val classes: List<NextYearClass> = emptyList(),
    val classesCreated: Int = 0,
    val childrenMoved: Int = 0,
    val graduating: Int = 0,
)

@Serializable
data class NextYearRequest(val academyKey: String, val carryChildren: Boolean = false)

/** `PUT /admin/structure/grade-names`: one academy's naming, applied in one go. */
@Serializable
data class GradeNamesRequest(
    val academyKey: String,
    val style: String? = null,
    val offset: Int? = null,
    val wordEn: String? = null,
    val wordAr: String? = null,
)

@Serializable
data class GradeLabel(val grade: Int, val labelEn: String, val labelAr: String)

@Serializable
data class GradeNamesResult(
    val academyKey: String,
    val naming: GradeNaming = GradeNaming(),
    val grades: List<GradeLabel> = emptyList(),
)

@Serializable
data class MoveClassRequest(val toAcademyKey: String)

/**
 * `POST /admin/structure/classes/{id}/move/preview` and `/apply?confirm=`: everything that changes, in the spec's own
 * order. [rebandedResults] of [releasedResults] released results would be shown in a different band, and while that is
 * above zero `/apply` answers `409 confirm_released` until `confirm=true`. No score is ever changed.
 */
@Serializable
data class MoveClassPreview(
    val classId: String,
    val name: String? = null,
    val fromAcademyKey: String,
    val toAcademyKey: String,
    val children: Int = 0,
    /** The assignments whose subject the target academy does not offer; `/apply` takes them away. */
    val droppedAssignments: List<StructureSubjectSlot> = emptyList(),
    val fromDirector: StructureStaffRef? = null,
    val toDirector: StructureStaffRef? = null,
    val fromCoordinators: List<StructureStaffRef> = emptyList(),
    val toCoordinators: List<StructureStaffRef> = emptyList(),
    val fromScheme: GradingScheme = GradingScheme(),
    val toScheme: GradingScheme = GradingScheme(),
    val releasedResults: Int = 0,
    val rebandedResults: Int = 0,
)
