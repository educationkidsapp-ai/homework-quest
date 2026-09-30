package quest.api.dashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import quest.api.dto.Curriculum
import quest.api.dto.Subject

/**
 * Sections, teaching assignments and class rosters — `docs/teacher-flow.md` §1–§2 and D14 of `docs/plan.md`.
 *
 * A **class** is a section inside a curriculum and grade (`1A`, `1B`), with a join code parents type. A **teaching
 * assignment** links one teacher to one class for one subject, and the pair is unique: 1A cannot have a second Math
 * teacher, and the Admin API answers a taken pair with 409 naming the teacher who holds it.
 *
 * Kept apart from `DashboardApi.kt` because the routes here are the Admin half of the one-school build (N1.1) and
 * the file is the whole of it; [SchoolClass] itself stays there, where every other school-shaped DTO lives.
 */

/** Who teaches one subject in one class. [className] and the course travel with it so no screen has to look them up. */
@Serializable
data class TeachingAssignment(
    val id: String,
    val classId: String,
    val className: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject,
    val teacherId: String,
    val teacherName: String? = null,
)

/** `POST /admin/classes`: a new section of a curriculum and grade. [name] is what the school calls it — "1A". */
@Serializable
data class CreateSectionRequest(val curriculum: Curriculum, val grade: Int, val name: String)

/**
 * `PATCH /admin/classes/{id}`. Every field is optional and only the present ones are written: [active] false retires
 * the section without deleting the lessons and children that point at it, and [joinCodeEnabled] false stops new
 * parents joining while leaving the ones who already did.
 */
@Serializable
data class UpdateSectionRequest(val name: String? = null, val active: Boolean? = null, val joinCodeEnabled: Boolean? = null)

/**
 * `POST /admin/teachers`: the account and the teaching profile in one call (`docs/teacher-flow.md` §1). The reply
 * carries [TeacherCreated.temporaryPassword] once and the server never stores, logs or shows it again.
 */
@Serializable
data class CreateTeacherRequest(
    val fullName: String,
    val email: String,
    val subjects: List<Subject> = emptyList(),
    val curriculum: Curriculum? = null,
    val photoUrl: String? = null,
    /** MH1: her mobile number, which the manager's Teachers screen shows beside her name (owner's item 4). */
    val phone: String? = null,
)

/** The one and only sight of a new teacher's password: read it out, then it is gone. */
@Serializable
data class TeacherCreated(val teacher: TeacherAccount, val temporaryPassword: String)

/** `POST /admin/teachers/{id}/reset-password`: the same one-time secret for an account that already exists. */
@Serializable
data class TemporaryPassword(val temporaryPassword: String)

/** `PATCH /admin/teachers/{id}`; an absent field is left as it is. [active] false disables the account. */
@Serializable
data class UpdateTeacherRequest(
    val fullName: String? = null,
    val subjects: List<Subject>? = null,
    val curriculum: Curriculum? = null,
    val photoUrl: String? = null,
    val active: Boolean? = null,
    val phone: String? = null,
)

/** A teacher as the Admin's Teachers screen sees her, with everything she is assigned to teach. */
@Serializable
data class TeacherAccount(
    val userId: String,
    val email: String,
    val fullName: String,
    val photoUrl: String? = null,
    val phone: String? = null,
    val status: UserStatus = UserStatus.ACTIVE,
    val subjects: List<Subject> = emptyList(),
    val curriculum: Curriculum? = null,
    val assignments: List<TeachingAssignment> = emptyList(),
)

/** One line of `PUT /admin/teachers/{id}/assignments`. */
@Serializable
data class AssignmentInput(val classId: String, val subject: Subject)

/** `PUT /admin/teachers/{id}/assignments`: the complete set she should hold afterwards, not a delta. */
@Serializable
data class AssignmentsRequest(val assignments: List<AssignmentInput> = emptyList())

/**
 * A child on a class roster (`docs/prompts/dashboard-first-one-school.md` §3). She exists before anybody has an
 * account for her: [hasParent] says whether a parent has since joined and linked to this row.
 */
@Serializable
data class RosterChild(
    val id: String,
    val classId: String? = null,
    val name: String,
    val parentEmail: String? = null,
    val photoUrl: String? = null,
    val active: Boolean = true,
    val hasParent: Boolean = false,
)

@Serializable
data class CreateRosterChildRequest(val name: String, val parentEmail: String? = null, val photoUrl: String? = null)

/**
 * `PATCH /admin/children/{id}`; [classId] moves her to another section of the same school and her grade follows it.
 *
 * MA1 adds [parentPhone] (the owner's item 5): it is the *parent account's* number (MH1's `parents.phone`), not a
 * column on the roster row, so sending one for a child no parent has registered yet is a 400 naming the reason rather
 * than a silently dropped field.
 */
@Serializable
data class UpdateRosterChildRequest(
    val name: String? = null,
    val parentEmail: String? = null,
    val photoUrl: String? = null,
    val active: Boolean? = null,
    val classId: String? = null,
    val parentPhone: String? = null,
)

// ---------------------------------------------------------------------------------------------------------------
// MA1: the Admin's Children & parents page (the owner's item 5)
// ---------------------------------------------------------------------------------------------------------------

/**
 * `POST /admin/children`: a child, her section and **her parent's login**, from one form.
 *
 * The parent's account does not live in this database — she signs in to the app with Firebase email/password — so the
 * server creates it there first and then writes the local `parents` row. [parentInitialPassword] is the Admin's to
 * choose and to read out in the room; at least eight characters, never the address itself, trimmed once and stored
 * nowhere on the server. It is applied **only** when this call mints the login — see [ChildAdmission.passwordApplied].
 *
 * [grade] and [curriculum] are the section's own and may be sent for confirmation only: a disagreement is a 400,
 * because a Grade 1 British child in a Grade 1 American class would be shown lessons for a syllabus she is not taught.
 *
 * An address the school already has is **reused** — a second child of one family is one account — while an address
 * whose family belongs to another school is a 409.
 */
@Serializable
data class AdmitChildRequest(
    val name: String,
    val classId: String,
    val parentName: String,
    val parentEmail: String,
    val parentInitialPassword: String,
    val grade: Int? = null,
    val curriculum: Curriculum? = null,
    val parentPhone: String? = null,
)

/**
 * 201 from [AdmitChildRequest]. [parentCreated] is false when the address already had a `parents` row;
 * [passwordApplied] is true **only** when this call minted the Firebase login with the [AdmitChildRequest.parentInitialPassword]
 * that was typed.
 *
 * A login that already exists keeps the password its owner chose — overwriting a stranger's password is not this
 * route's to do — so the two flags answer two different questions and **the screen must say which happened in words**:
 * `passwordApplied == false` means "she already has an account; her existing password still works", and the paper the
 * Admin just wrote the typed password on is worthless.
 */
@Serializable
data class ChildAdmission(
    val childId: String,
    val parentId: String,
    val parentCreated: Boolean,
    val passwordApplied: Boolean = false,
)

/**
 * One line of the Children &amp; parents page. [parentName] is null for a parent the app created on first sight of her
 * token (V25 gave `parents` a name and did not backfill one), and [parentEmail] then falls back to the address the
 * roster itself holds.
 */
@Serializable
data class FamilyRow(
    val childId: String,
    val name: String,
    val grade: Int,
    val curriculum: Curriculum? = null,
    val classId: String? = null,
    val className: String? = null,
    val parentId: String? = null,
    val parentName: String? = null,
    val parentEmail: String? = null,
    val parentPhone: String? = null,
    val active: Boolean = true,
)

/**
 * `GET /admin/children/search?q&page&size`: a page of [FamilyRow], matched on the child's name, either address the
 * school holds, or the parent's name and telephone number. [total] is every row the search matches.
 *
 * The path is not `/admin/children`, which has answered a flat `List<RosterChild>` since §3 and is what the attach
 * picker reads with `?unassigned=true`; a page envelope with parent columns is a different answer to the same
 * question, so it got a route of its own rather than breaking that one.
 */
@Serializable
data class FamilyPage(val page: Int = 0, val size: Int = 0, val total: Int = 0, val rows: List<FamilyRow> = emptyList())

/**
 * `POST /admin/classes/{id}/roster/attach` and its teacher twin: a child who already has an account — one a parent
 * registered in the app with the school's join code — put onto this section's roster. Until she is on one she is
 * shown every section of her curriculum and grade, so a lesson copied to a sibling section reaches her twice.
 */
@Serializable
data class AttachChildRequest(val childId: String)

/** What the import made of one line. Only [ImportStatus.NEW] rows are inserted when the import is committed. */
@Serializable
enum class ImportStatus {
    @SerialName("new") NEW,

    /** A child of the same normalised name is already on this class's roster. */
    @SerialName("duplicate") DUPLICATE,

    @SerialName("invalid") INVALID,
}

/** One line of the uploaded file; [line] is the 1-based row number in the file, header included. */
@Serializable
data class ImportRow(
    val line: Int,
    val name: String? = null,
    val parentEmail: String? = null,
    val status: ImportStatus = ImportStatus.NEW,
    val reason: String? = null,
)

@Serializable
data class ImportSummary(val total: Int = 0, val added: Int = 0, val duplicate: Int = 0, val invalid: Int = 0)

/**
 * `POST /admin/classes/{id}/children/import`. With `?dryRun=true` nothing is written and [rows] is the preview the
 * Admin confirms; without it the same answer describes what was inserted.
 */
@Serializable
data class ImportPreview(val dryRun: Boolean = true, val rows: List<ImportRow> = emptyList(), val summary: ImportSummary = ImportSummary())

/**
 * The body of `POST /classes/lookup`. A join code is a credential, so it travels in a body rather than a query
 * string, which the access log, the load balancer and the browser's own history all keep by default.
 */
@Serializable
data class ClassLookupRequest(val code: String)

/**
 * Public `POST /classes/lookup`: what a parent sees after typing a join code, and nothing more — no roster, no
 * teacher, no school id. An unknown, disabled or inactive code is the same uniform 404.
 */
@Serializable
data class ClassLookup(
    val classId: String,
    val name: String,
    val grade: Int,
    val curriculum: Curriculum,
    val schoolName: String,
)
