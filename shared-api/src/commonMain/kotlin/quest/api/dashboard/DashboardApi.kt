package quest.api.dashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import quest.api.dto.Curriculum
import quest.api.dto.PlatformSettings
import quest.api.dto.SchoolTheme
import quest.api.dto.Subject

/**
 * The Schools Dashboard contract (§2 tenancy, §5 roles). Kept apart from `AdminApi` on purpose: `webAdmin/`
 * implements that interface and must keep compiling until the Angular dashboard replaces it.
 */

/** Who a dashboard user is. Parents are not here — they stay in Firebase Auth. */
@Serializable
enum class Role {
    /** Platform owner: `schoolId` is null, sees every school and scopes with the `X-School-Id` header. */
    @SerialName("ADMIN") ADMIN,
    @SerialName("TEACHER") TEACHER,
    @SerialName("MANAGERIAL") MANAGERIAL,
}

@Serializable
enum class UserStatus { @SerialName("active") ACTIVE, @SerialName("disabled") DISABLED, @SerialName("invited") INVITED }

@Serializable
enum class SchoolStatus { @SerialName("active") ACTIVE, @SerialName("suspended") SUSPENDED }

/**
 * One tenant. `theme` is the raw `schools.theme_json`; the typed theme is `GET /schools/{id}/theme`
 * ([SchoolTheme]). `featureFlags` is the unused `schools.feature_flags_json` column kept from P1.1 — since P2.1 the
 * per-school overrides live in `school_feature_flags` and are read through `GET /schools/{id}/flags`.
 */
@Serializable
data class School(
    val id: String,
    val name: String,
    val code: String,                                   // 6 characters, what a parent types to join
    val curriculumOptions: List<Curriculum> = emptyList(),
    val gradeOptions: List<Int> = emptyList(),
    val theme: String? = null,
    val featureFlags: String = "{}",
    val status: SchoolStatus = SchoolStatus.ACTIVE,
    val createdAt: Long = 0,
    /** What the School page's Overview tab counts (§6 screen 5); live children, teachers who are not disabled. */
    val children: Int = 0,
    val teachers: Int = 0,
    val lessons: Int = 0,
    val classes: Int = 0,
)

/** A school in a list: no theme, no flags. */
@Serializable
data class SchoolSummary(
    val id: String,
    val name: String,
    val code: String,
    val status: SchoolStatus = SchoolStatus.ACTIVE,
    val teachers: Int = 0,
    val children: Int = 0,
    val lessons: Int = 0,
)

/**
 * What a parent sees after typing a school code, before confirming (§2). [theme] travels with it so the confirm
 * step can run its colour transition without a second request (§3).
 */
@Serializable
data class JoinSchoolInfo(
    val name: String,
    val logoUrl: String? = null,
    val curriculumOptions: List<Curriculum> = emptyList(),
    val gradeOptions: List<Int> = emptyList(),
    val theme: SchoolTheme? = null,
)

/**
 * A **section** since V7 (D14): "1A" inside a curriculum and grade, with the join code parents type. Who teaches
 * what is [TeachingAssignment], not a field here; [subject] and [teacherId] are the pre-V7 shape, still answered for
 * `webAdmin` and null on every section created from V7 onwards.
 *
 * [children] and [assignments] are the counts the Admin's Classes screen shows, filled by `GET /admin/classes`.
 */
@Serializable
data class SchoolClass(
    val id: String,
    val schoolId: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject? = null,
    val teacherId: String? = null,
    val teacherName: String? = null,
    val createdAt: Long = 0,
    val name: String? = null,
    val joinCode: String? = null,
    val active: Boolean = true,
    val joinCodeEnabled: Boolean = true,
    val children: Int = 0,
    val assignments: Int = 0,
)

@Serializable
data class DashboardUser(
    val id: String,
    val email: String,
    val role: Role,
    val schoolId: String? = null,
    val status: UserStatus = UserStatus.ACTIVE,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val language: String = "en",
    val mustChangePassword: Boolean = false,
    val lastLoginAt: Long? = null,
    val createdAt: Long = 0,
    /** Set on `GET /me` while an Admin is viewing the dashboard as this user (§5 "View as…"); the id of that Admin. */
    val impersonatedBy: String? = null,
    /**
     * `GET /me` only: what to call the product for this user (§A) — the school's `theme.appName`, else the platform's
     * name, else the name seeded by `V5__flags_themes.sql`. The dashboard's title, heading and footer read it.
     */
    val platformName: String? = null,
    /** The name of [schoolId], so the Admin's cross-school Users list (§6 screen 6) needs no second request. */
    val schoolName: String? = null,
    /**
     * `GET /me` only: what this teacher is assigned to teach (`docs/teacher-flow.md` §2). The dashboard builds her
     * whole navigation from it, so it arrives with the account rather than as a second request. Absent for ADMIN and
     * MANAGERIAL, who are not assignment-scoped.
     */
    val assignments: List<TeachingAssignment>? = null,
)

/** The public half of a teacher account: what the teacher island and the school page show. */
@Serializable
data class TeacherProfile(
    val userId: String,
    val displayName: String,
    val photoUrl: String? = null,
    val subjects: List<Subject> = emptyList(),
    val curriculum: Curriculum? = null,
    val grades: List<Int> = emptyList(),
    val bioEn: String? = null,
    val bioAr: String? = null,
)

/** A one-time email invite into a school (Admin invites Managerial, Managerial invites Teachers). */
@Serializable
data class Invite(
    val id: String,
    val email: String,
    val role: Role,
    val schoolId: String? = null,
    val invitedBy: String,
    val expiresAt: Long,
    val acceptedAt: Long? = null,
    val createdAt: Long = 0,
)

/**
 * `POST /auth/sign-in` (and `POST /admin/auth/sign-in`, the alias `webAdmin/` still calls). `token` is the 15-minute
 * access token; `refreshToken` is the 30-day one and is empty on the legacy alias, which issues a long session instead.
 */
@Serializable
data class SignInResponse(
    val token: String,
    val email: String,
    val expiresAt: Long,
    val role: Role = Role.ADMIN,
    val schoolId: String? = null,
    val displayName: String? = null,
    val mustChangePassword: Boolean = false,
    val refreshToken: String? = null,
)

/** A fresh access/refresh pair. `POST /auth/refresh` rotates: the refresh token that was presented is revoked here. */
@Serializable
data class TokenPair(val token: String, val refreshToken: String, val expiresAt: Long)

@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class ForgotPasswordRequest(val email: String)
@Serializable data class ResetPasswordRequest(val token: String, val newPassword: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/** `code` is generated (6 characters, A–Z0–9) when it is left out. */
@Serializable
data class CreateSchoolRequest(
    val name: String,
    val code: String? = null,
    val curriculumOptions: List<Curriculum> = emptyList(),
    val gradeOptions: List<Int> = emptyList(),
)

/** Every field is optional: only the ones that are present are written. */
@Serializable
data class UpdateSchoolRequest(
    val name: String? = null,
    val curriculumOptions: List<Curriculum>? = null,
    val gradeOptions: List<Int>? = null,
    val status: SchoolStatus? = null,
)

/** The teacher half of an invite: filled in when `role` is TEACHER, so the account is complete the moment it is accepted. */
@Serializable
data class TeacherProfileInput(
    val displayName: String? = null,
    val photoUrl: String? = null,
    val subjects: List<Subject> = emptyList(),
    val curriculum: Curriculum? = null,
    val grades: List<Int> = emptyList(),
    val bioEn: String? = null,
    val bioAr: String? = null,
)

@Serializable
data class CreateInviteRequest(val email: String, val role: Role, val teacherProfile: TeacherProfileInput? = null)

/**
 * `POST /admin/schools/{id}/users` (ADMIN only): an account created outright instead of invited by email. It is
 * active immediately, signs in with [password] — at least 10 characters — and must replace it at that first sign-in.
 */
@Serializable
data class CreateUserRequest(
    val email: String,
    val role: Role,
    val password: String,
    val displayName: String? = null,
    val teacherProfile: TeacherProfileInput? = null,
)

/** What the public accept-invite page shows before the person picks a password. */
@Serializable
data class InviteInfo(val email: String, val role: Role, val schoolName: String? = null, val expiresAt: Long = 0)

@Serializable data class AcceptInviteRequest(val password: String, val displayName: String? = null)

/**
 * `PATCH /me`: the three things any dashboard user may change about herself, only the present ones written.
 * Everything else about an account — role, status, school, email — is somebody else's to change
 * ([UpdateUserRequest]), and a teacher's teaching profile is [UpdateTeacherProfileRequest].
 *
 * [photoUrl] must be an `https://` URL: it is rendered into an `img src` by the dashboard and by the app's teacher
 * island. [language] is `en` or `ar` — the two catalogues §6 ships.
 */
@Serializable
data class UpdateMeRequest(val displayName: String? = null, val photoUrl: String? = null, val language: String? = null)

/** `GET /me/permissions`: the keys of `permissions.json` the caller's role holds. */
@Serializable
data class MePermissions(val role: Role, val permissions: List<String> = emptyList(), val readOnly: Boolean = false)

/** `GET /admin/users` filters; an absent field does not filter. */
@Serializable
data class UserFilter(val role: Role? = null, val schoolId: String? = null, val status: UserStatus? = null)

/** `PATCH /admin/users/{id}`: status (disable/enable), display name, or role within the same school. */
@Serializable
data class UpdateUserRequest(val status: UserStatus? = null, val displayName: String? = null, val role: Role? = null)

/**
 * The Schools Dashboard's view of the backend (P1.3). The Angular client is generated from `server/openapi.json`
 * and the server is the only implementation, so nothing in this module implements the interface — it is the
 * contract both sides are checked against.
 */
interface DashboardApi {
    // ---- auth (public except change-password)
    suspend fun signIn(email: String, password: String): SignInResponse
    /** Rotation: the old refresh token is revoked and a new pair issued; presenting a revoked one kills the family. */
    suspend fun refresh(request: RefreshRequest): TokenPair
    suspend fun signOut(request: RefreshRequest)
    /** Always succeeds, whether or not the address belongs to an account. */
    suspend fun forgotPassword(request: ForgotPasswordRequest)
    suspend fun resetPassword(request: ResetPasswordRequest)
    /** Clears `mustChangePassword` and revokes every other session of the user. */
    suspend fun changePassword(request: ChangePasswordRequest)

    // ---- the caller
    suspend fun me(): DashboardUser
    /** Her own display name, photo and language; answers the same shape [me] does. */
    suspend fun updateMe(request: UpdateMeRequest): DashboardUser
    suspend fun myPermissions(): MePermissions

    // ---- schools (Admin)
    suspend fun schools(): List<SchoolSummary>
    suspend fun createSchool(request: CreateSchoolRequest): School
    suspend fun school(schoolId: String): School
    suspend fun updateSchool(schoolId: String, request: UpdateSchoolRequest): School

    // ---- dashboard users and invites
    suspend fun users(filter: UserFilter = UserFilter()): List<DashboardUser>
    suspend fun updateUser(userId: String, request: UpdateUserRequest): DashboardUser
    /** Emails the person a reset link; the account itself is untouched. */
    suspend fun resetUserPassword(userId: String)
    suspend fun createInvite(schoolId: String, request: CreateInviteRequest): Invite
    /** ADMIN only: creates the account there and then, with `mustChangePassword` set; 409 when the address is taken. */
    suspend fun createUser(schoolId: String, request: CreateUserRequest): DashboardUser
    /** Public: what the accept-invite page shows. */
    suspend fun inviteInfo(token: String): InviteInfo
    /** Public: sets the password, activates the account and signs the person in. */
    suspend fun acceptInvite(token: String, request: AcceptInviteRequest): SignInResponse

    // ---- View as… (§5): a read-only token for a Teacher or Managerial user, audit-logged on every request
    suspend fun impersonate(userId: String): SignInResponse

    /** Public: what a parent sees after typing a school code in Add child. */
    suspend fun schoolByCode(code: String): JoinSchoolInfo

    // ---- feature flags (§4). `flag.read` is ADMIN and MANAGERIAL (her own school's row), `flag.write` is ADMIN.
    /** The definitions and a row per school the caller may see. */
    suspend fun flags(): FlagMatrix
    /** One cell of the matrix; answers with the school's whole flag set. */
    suspend fun setSchoolFlag(schoolId: String, key: String, request: UpdateFlagRequest): Map<String, Boolean>
    /** §4's "enable for all / disable for all" on a column; one audit row with `schoolId` null records it. */
    suspend fun setFlagEverywhere(key: String, request: UpdateFlagRequest): FlagMatrix
    /** Who flipped what and when, newest first. */
    suspend fun flagAudit(limit: Int = 50): List<FlagAuditEntry>

    // ---- themes (§3) and platform settings (§A)
    suspend fun schoolTheme(schoolId: String): SchoolTheme
    /** 400 `bad_request` naming the failing pair and its ratio when a text/background pair is below 4.5:1. */
    suspend fun saveSchoolTheme(schoolId: String, theme: SchoolTheme): SchoolTheme
    /** Every field, the platform-wide default theme included. */
    suspend fun platformSettings(): PlatformSettings
    suspend fun savePlatformSettings(request: UpdatePlatformSettingsRequest): PlatformSettings

    // ---- P3.0: what the Angular dashboard's Homes, School page and usage screens read (§6 screens 2, 4-6, 8, 10, 19)

    /** §6 screen 2: the caller's Home, shaped by their role. Sections that do not belong to that role are null. */
    suspend fun home(): HomeResponse

    /** The School page's Classes tab (§6 screen 5). A Teacher or Managerial caller may only name her own school. */
    // ------------------------------------------------------------------ sections, teachers and rosters (N1.1)

    /** `GET /admin/classes`, narrowed by curriculum and grade when they are given. */
    suspend fun classes(curriculum: Curriculum? = null, grade: Int? = null): List<SchoolClass>
    suspend fun createSection(request: CreateSectionRequest): SchoolClass
    suspend fun updateSection(classId: String, request: UpdateSectionRequest): SchoolClass

    /** A new random join code; the old one stops working the moment this returns. */
    suspend fun regenerateJoinCode(classId: String): SchoolClass

    /** `GET /admin/classes/{id}/join-card.pdf` — the A5 card a school prints and hands to parents. */
    suspend fun joinCard(classId: String): ByteArray

    suspend fun classAssignments(classId: String): List<TeachingAssignment>

    suspend fun teachers(): List<TeacherAccount>

    /** The reply carries the temporary password once; it cannot be read again. */
    suspend fun createTeacher(request: CreateTeacherRequest): TeacherCreated
    suspend fun updateTeacher(userId: String, request: UpdateTeacherRequest): TeacherAccount
    suspend fun resetTeacherPassword(userId: String): TemporaryPassword

    /** Replaces her whole set. A (class, subject) another teacher holds is 409 naming her. */
    suspend fun setAssignments(userId: String, request: AssignmentsRequest): List<TeachingAssignment>

    suspend fun classChildren(classId: String): List<RosterChild>
    suspend fun addChildToClass(classId: String, request: CreateRosterChildRequest): RosterChild
    /** The school's children; `unassigned = true` is the ones on no section's roster yet. */
    suspend fun schoolChildren(unassigned: Boolean = false): List<RosterChild>
    suspend fun updateRosterChild(childId: String, request: UpdateRosterChildRequest): RosterChild

    /** Puts an app-registered child on a section's roster, and takes her off it again; both answer the child. */
    suspend fun attachChildToClass(classId: String, request: AttachChildRequest): RosterChild
    suspend fun detachChildFromClass(classId: String, childId: String): RosterChild

    /**
     * The same two, named as the dashboard's roster screen calls them and taking the child's id rather than the
     * one-field request body, plus the two routes this interface had no name for at all — `GET /admin/children`
     * with `unassigned=true`, and the hard `DELETE /admin/children/{id}`. Together they are every roster route in
     * `server/openapi.json`, which is what the generated Angular client is built from: a route with no method here
     * is one the two clients can disagree about.
     */
    suspend fun attachRosterChild(classId: String, childId: String): RosterChild =
        attachChildToClass(classId, AttachChildRequest(childId))

    suspend fun detachRosterChild(classId: String, childId: String): RosterChild =
        detachChildFromClass(classId, childId)

    /** The children a parent registered with the school's join code and nobody has put in a section yet. */
    suspend fun listUnassignedChildren(): List<RosterChild> = schoolChildren(unassigned = true)

    /**
     * `GET /admin/classes/{id}/children/unassigned` — the same children narrowed to one section: on no roster at
     * all, and of that section's curriculum and grade, so every row is one [attachChildToClass] would accept
     * rather than refuse with a 409. [unassignedChildrenForMyClass] is the teacher's half, behind
     * `teacher.rosterEdit`; it is the only list of unplaced children a TEACHER can read, because the school-wide
     * [schoolChildren] is `roster.read`.
     */
    suspend fun unassignedChildrenForClass(classId: String): List<RosterChild>

    /**
     * `DELETE /admin/children/{id}` — the row and everything that was only ever hers (attempts, completions,
     * unlocks, stickers, streak, recordings, drawings). It is how acceptance data is cleaned out; a child who has
     * simply left the school is retired with [updateRosterChild] and `active = false` instead, which keeps her work.
     */
    suspend fun deleteRosterChild(childId: String)

    /** The teacher's aliases for the two the `teacher.rosterEdit` flag gives her; there is no teacher-side delete. */
    suspend fun attachRosterChildToMyClass(classId: String, childId: String): RosterChild =
        attachChildToMyClass(classId, AttachChildRequest(childId))

    suspend fun detachRosterChildFromMyClass(classId: String, childId: String): RosterChild =
        detachChildFromMyClass(classId, childId)

    /** CSV or XLSX with `name,parentEmail`; `dryRun` returns the preview without writing anything. */
    suspend fun importRoster(classId: String, fileName: String, bytes: ByteArray, dryRun: Boolean = true): ImportPreview

    /** The teacher's own roster, behind the `teacher.rosterEdit` flag and scoped to her assignments. */
    suspend fun myClassChildren(classId: String): List<RosterChild>

    /** `GET /teacher/classes/{classId}/children/unassigned` — what her Children tab's "add a child" sheet lists. */
    suspend fun unassignedChildrenForMyClass(classId: String): List<RosterChild>
    suspend fun addChildToMyClass(classId: String, request: CreateRosterChildRequest): RosterChild
    suspend fun updateMyRosterChild(classId: String, childId: String, request: UpdateRosterChildRequest): RosterChild
    suspend fun attachChildToMyClass(classId: String, request: AttachChildRequest): RosterChild
    suspend fun detachChildFromMyClass(classId: String, childId: String): RosterChild

    /** Public: what a parent sees after typing a join code. Unknown or disabled is a uniform 404. */
    suspend fun classByJoinCode(request: ClassLookupRequest): ClassLookup

    suspend fun schoolClasses(schoolId: String): List<SchoolClass>
    suspend fun createClass(schoolId: String, request: CreateClassRequest): SchoolClass
    /** Assigns the class's teacher, or hands it back to nobody with `clearTeacher`. */
    suspend fun updateClass(schoolId: String, classId: String, request: UpdateClassRequest): SchoolClass

    /** The School page's Usage tab (§6 screen 5); the window defaults to the last 30 days. */
    suspend fun schoolUsage(schoolId: String, from: String? = null, to: String? = null): SchoolUsage
    /** The School page's Billing tab: what this school's lessons cost in model tokens, per month. */
    suspend fun schoolBilling(schoolId: String, months: Int = 6): SchoolBilling

    /** §6 screen 19, Managerial: her own school's usage, with no school id to pass. */
    suspend fun mySchoolUsage(from: String? = null, to: String? = null): SchoolUsage
    /** §6 screen 20, Managerial: her school's teachers and their classes, read-only. */
    suspend fun mySchoolTeachers(): List<TeacherSummary>

    /** §6 screen 10, Admin: the platform's own numbers and what each school cost. */
    suspend fun platformUsage(from: String? = null, to: String? = null): PlatformUsage

    /** §6 screen 4: the New school wizard's submit — school, theme, flags and the first Managerial user in one go. */
    suspend fun createSchoolWithWizard(request: SchoolWizardRequest): SchoolWizardResponse

    /**
     * Public (§6 screen 1): the logo to fade in once the person has typed their address. 204 when nothing matches.
     * `POST /schools/logo` with the address in the body — see [SchoolLogo] for why it is not a query parameter.
     */
    suspend fun schoolLogoByEmail(request: SchoolLogoRequest): SchoolLogo?

    // ---- P4.0: the Teacher's own screens (§5, §6 screens 11–16). `Teacher.kt` holds the types.

    /** §5: the caller's own teacher profile. TEACHER only — an Admin uses [teacherProfileOf]. */
    suspend fun myTeacherProfile(): TeacherProfile
    suspend fun saveMyTeacherProfile(request: UpdateTeacherProfileRequest): TeacherProfile

    /** §6 screen 13: what the New lesson chooser may offer this teacher, and nothing else. */
    suspend fun teacherOptions(): TeacherOptions

    /** ADMIN: read and write any teacher's profile, from the Users screen (§6 screen 6). */
    suspend fun teacherProfileOf(userId: String): TeacherProfile
    suspend fun saveTeacherProfileOf(userId: String, request: UpdateTeacherProfileRequest): TeacherProfile

    /** §6 screen 12: the class's month (`yyyy-MM`), with the days that have no lesson flagged as gaps. */
    suspend fun classCalendar(classId: String, month: String? = null): ClassCalendar

    // ---- Questions to students (§6 screen 14). Every route here is 404 while `teacherQuestions` is off.

    /** Her questions, newest first, each with its results summary. */
    suspend fun teacherQuestions(): List<TeacherQuestion>
    suspend fun createTeacherQuestion(request: CreateTeacherQuestionRequest): TeacherQuestion
    /** 409 once the question has been sent: the stops a child has already answered are not rewritten. */
    suspend fun updateTeacherQuestion(questionId: String, request: UpdateTeacherQuestionRequest): TeacherQuestion
    /** Validates the stops against the shared schema, stamps `sentAt`; 409 if it was already sent. */
    suspend fun sendTeacherQuestion(questionId: String): TeacherQuestion
    suspend fun teacherQuestionResults(questionId: String): TeacherQuestionResults

    // ---- Announcements (§6 screen 16). 404 while `announcements` is off.

    suspend fun teacherAnnouncements(): List<Announcement>
    suspend fun createAnnouncement(request: CreateAnnouncementRequest): Announcement
    suspend fun deleteAnnouncement(announcementId: String)

    // ---- My students (§6 screen 15)

    /** Per child of one of her classes: stars this week, level reached, weak skills, when they last played. */
    suspend fun classStudents(classId: String): List<ClassStudent>
    /** What one child played in the window, and the retells and drawings she saved. */
    suspend fun studentTimeline(childId: String, from: String? = null, to: String? = null): StudentTimeline

    // ---- N2.1: This week, My classes and her lessons (`docs/teacher-flow.md` §4, §7, §8)

    /**
     * §4: every assignment she holds against one school week. [start] is any day of the week wanted, ISO
     * `yyyy-MM-dd`; the server snaps it back to the week's first teaching day and answers `null` as "this week".
     */
    suspend fun teacherWeek(start: String? = null): TeacherWeek

    /** §7: a card per assignment — today's lesson, how many children, how many have played it. */
    suspend fun teacherClasses(): List<TeacherClassCard>

    /**
     * §8: a new lesson in one of her classes. The `(classId, subject)` pair must be one of her teaching
     * assignments — 403 otherwise, whatever the chooser offered — and the row is stamped with her as the teacher.
     */
    suspend fun createTeacherLesson(request: CreateTeacherLessonRequest): quest.api.AdminLesson

    /** Moves the lesson to another day. 409 once it is published: unpublish it first. */
    suspend fun moveTeacherLesson(lessonId: String, request: MoveLessonRequest): quest.api.AdminLesson

    /**
     * A full copy — plays, stops with ids of their own, skills and the parent panel — into another class of the same
     * grade and subject she teaches. The copy keeps the source's file hash, so its cache badge still reads
     * "Analyzed before"; its results are its own from the first attempt.
     */
    suspend fun copyTeacherLesson(lessonId: String, request: CopyLessonRequest): quest.api.AdminLesson

    /** Publishes the lesson into every class named, copying it into the ones that are not its own. */
    suspend fun publishTeacherLesson(lessonId: String, request: PublishToClassesRequest): List<PublishedCopy>

    suspend fun unpublishTeacherLesson(lessonId: String): quest.api.AdminLesson

    /** Draft or error only; 409 for anything published or in flight. */
    suspend fun deleteTeacherLesson(lessonId: String)

    /** The lesson she is editing, with its plays, skills, panel and pipeline ledger. */
    suspend fun teacherLesson(lessonId: String): quest.api.AdminLesson
}

/** `PUT /admin/platform-settings` (§A): only the fields that are present are written. */
@Serializable
data class UpdatePlatformSettingsRequest(
    val name: String? = null,
    val shortName: String? = null,
    val logoUrl: String? = null,
    val supportEmail: String? = null,
    val defaultTheme: SchoolTheme? = null,
    /** N2.1: three-letter `DayOfWeek` names in the order the week runs, and an IANA zone. Both validated. */
    val schoolWeek: List<String>? = null,
    val timezone: String? = null,
)

// ---------------------------------------------------------------------------------------------------------------
// P3.0 `backend/dashboard-endpoints` — Homes, School page data, the wizard, platform usage and the sign-in logo.
// ---------------------------------------------------------------------------------------------------------------

/**
 * One of the three big numbers a Home counts up on load (§6 screen 2).
 *
 * **No prose crosses this boundary.** §6 requires EN/AR to switch without a reload and the server has no
 * `Accept-Language` — so [key] is a message id the dashboard's catalogue resolves (`home.card.<key>`) and [value] is
 * the number, typed rather than pre-formatted because the count-up animation interpolates it and the digits are the
 * browser's business.
 *
 * Keys by role: ADMIN `schools`, `children`, `lessonsThisWeek`; TEACHER `playedYesterday`, `lessonsThisWeek`,
 * `needsReview`; MANAGERIAL `children`, `activeFamilies`, `teachers`.
 */
@Serializable
data class HomeCard(val key: String, val value: Long)

/**
 * One row of a Home's "what needs you" list, as a message id and its values rather than a sentence (see [HomeCard]).
 *
 * [kind] resolves as `home.needs.<kind>` and is what the dashboard groups and icons by; [targetId] is the row's
 * subject; [params] are interpolated into the message; [href] is the route that does something about it, already
 * carrying whatever the target screen needs preselected. An unknown [kind] should render as nothing rather than as
 * the raw id — a later phase will add rows an older dashboard build has no string for.
 *
 * | kind | targetId | params |
 * |---|---|---|
 * | `lesson.error` | lesson id | `lessonTitle`, `errorCode`?, `schoolName`? (Admin) |
 * | `lesson.needs_review` | lesson id | `lessonTitle`, `schoolName`? (Admin) |
 * | `school.noTeacher` | school id | `schoolName` |
 * | `user.staleInvite` | user id | `email`, `role`, `days` |
 * | `class.noLessonToday` | class id | `curriculum`, `grade`, `subject`, `date` |
 * | `skill.weak` | skill id | `skillName`, `band` |
 * | `teacher.quiet` | user id | `teacherName`, `days` |
 *
 * The only [params] values that are not themselves message ids are the ones that *are* the data — a school's name, a
 * lesson's title, a person's name, a skill's name. Those are what the school typed, and not the server's to translate.
 *
 * A param listed above can be **absent**, and absent is not the same as empty: `lessonTitle` is left out entirely
 * for a lesson that has no title yet, because the server has no wording of its own to put there — an English
 * "Untitled lesson" would be printed verbatim into an Arabic page. The catalogue owns the fallback:
 * `home.needs.<kind>` should resolve a variant that reads without the param when it is missing.
 */
@Serializable
data class NeedsYouItem(
    val kind: String,
    val targetId: String? = null,
    val params: Map<String, String> = emptyMap(),
    val href: String? = null,
)

/** A teacher's class on her Home, with the state of today's lesson for it (§6 screen 11). */
@Serializable
data class TeacherClassInfo(
    val classId: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject,
    val todayLessonId: String? = null,
    /** The lesson's `LessonStatus` value, or null when the class has no lesson dated today at all. */
    val todayStatus: String? = null,
)

/** A skill the children are weakest at, banded by `ProgressBands` — words, never a percentage. */
@Serializable
data class WeakSkill(val skillId: String, val name: String, val band: String)

/**
 * `GET /me/home` (§6 screen 2): one shape for all three roles, with the sections a role does not have left null.
 *
 * - ADMIN: [cards] schools / children / lessons published this week; [needsYou] lessons in error or awaiting review
 *   across schools, schools with no teacher, accounts still `invited` after seven days. [schoolName] and
 *   [schoolLogoUrl] are null until the Admin picks a school with `X-School-Id`.
 * - TEACHER: [classes] with today's lesson per class, [cards] children who played yesterday / lessons published this
 *   week / open `needs_review`, [weakSkills] the three weakest across her classes.
 * - MANAGERIAL: [cards] children / families active this week / teachers; [needsYou] teachers who have not published
 *   in seven days. Complaints arrive in phase 5 — until then those fields are absent rather than zero.
 */
@Serializable
data class HomeResponse(
    val role: Role,
    val displayName: String? = null,
    val schoolId: String? = null,
    val schoolName: String? = null,
    val schoolLogoUrl: String? = null,
    val platformName: String? = null,
    val cards: List<HomeCard> = emptyList(),
    val needsYou: List<NeedsYouItem> = emptyList(),
    /** TEACHER only. */
    val classes: List<TeacherClassInfo>? = null,
    /** TEACHER only. */
    val weakSkills: List<WeakSkill>? = null,
)

/** `POST /admin/schools/{id}/classes`: a class the school runs, with the teacher who owns it. */
@Serializable
data class CreateClassRequest(
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject,
    val teacherId: String? = null,
)

/**
 * `PATCH /admin/schools/{id}/classes/{classId}`: who teaches it. A null [teacherId] alone means "leave it as it is";
 * [clearTeacher] is how the class is handed back to nobody, since JSON cannot tell absent from null here.
 */
@Serializable
data class UpdateClassRequest(val teacherId: String? = null, val clearTeacher: Boolean = false)

/** A day of the plays series; [date] is ISO `yyyy-MM-dd`. */
@Serializable
data class DayCount(val date: String, val count: Int)

/** A week of the publishing series; [week] is the ISO Monday of that week, `yyyy-MM-dd`. */
@Serializable
data class WeekCount(val week: String, val count: Int)

/**
 * §6 screen 19: how steadily one teacher publishes — [weeksWithALesson] out of [weeks] in the window, and the
 * moment her last lesson was published (null when she has published none).
 */
@Serializable
data class TeacherConsistency(
    val teacherId: String,
    val displayName: String? = null,
    val lessonsPublished: Int = 0,
    val weeks: Int = 0,
    val weeksWithALesson: Int = 0,
    val lastPublishedAt: Long? = null,
)

/** `GET /admin/schools/{id}/usage` and `GET /school/usage` (§6 screens 5 and 19). */
@Serializable
data class SchoolUsage(
    val schoolId: String,
    val schoolName: String? = null,
    val from: String,
    val to: String,
    val children: Int = 0,
    /** Families with at least one child who answered something inside the window. */
    val activeFamilies: Int = 0,
    val playsPerDay: List<DayCount> = emptyList(),
    val lessonsPublishedPerWeek: List<WeekCount> = emptyList(),
    val teacherConsistency: List<TeacherConsistency> = emptyList(),
)

/** One month of a school's model bill; [month] is `yyyy-MM`. [costUsd] is [tokens] at the configured price. */
@Serializable
data class MonthCost(val month: String, val tokens: Long = 0, val tokensSaved: Long = 0, val costUsd: Double = 0.0)

/**
 * `GET /admin/schools/{id}/billing` (§6 screen 5, Billing tab). The price is `quest.llm.price-per-1k-tokens`;
 * [pricePer1kTokens] travels with the answer so the screen can show the assumption it is built on.
 */
@Serializable
data class SchoolBilling(
    val schoolId: String,
    val schoolName: String? = null,
    val currency: String = "USD",
    val pricePer1kTokens: Double = 0.0,
    val months: List<MonthCost> = emptyList(),
    val totalTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
)

/** One school's share of the platform's model bill (§6 screen 10). */
@Serializable
data class SchoolCost(
    val schoolId: String,
    val schoolName: String? = null,
    val tokens: Long = 0,
    val tokensSaved: Long = 0,
    val costUsd: Double = 0.0,
    val lessons: Int = 0,
)

/**
 * `GET /admin/usage/platform` (§6 screen 10). [aiCalls] is how many analyses and generations actually reached the
 * model — every cache row is one call that was paid for — and [cacheHits] how often a later lesson reused one, so
 * [cacheHitRate] is `hits / (hits + calls)`, the §6 "> 90 %" target.
 */
@Serializable
data class PlatformUsage(
    val from: String,
    val to: String,
    val schools: Int = 0,
    val children: Int = 0,
    val playsPerDay: List<DayCount> = emptyList(),
    val aiCalls: Long = 0,
    val cacheHits: Long = 0,
    val cacheHitRate: Double = 0.0,
    val pricePer1kTokens: Double = 0.0,
    val costPerSchool: List<SchoolCost> = emptyList(),
    val totalCostUsd: Double = 0.0,
)

/**
 * The first Managerial user of a brand-new school (§6 screen 4, last step). With a [password] the account exists and
 * can sign in at once and must change it; without one an invitation is emailed instead.
 */
@Serializable
data class WizardManagerInput(val email: String, val displayName: String? = null, val password: String? = null)

/** `POST /admin/schools/wizard`: everything the four wizard steps collected, applied in one transaction. */
@Serializable
data class SchoolWizardRequest(
    val school: CreateSchoolRequest,
    val theme: SchoolTheme? = null,
    val flags: Map<String, Boolean> = emptyMap(),
    val manager: WizardManagerInput,
)

/**
 * What the wizard made. [user] is the Managerial account — `invited` when no password was given, in which case
 * [invited] is true and the one-time link is on its way by email.
 */
@Serializable
data class SchoolWizardResponse(
    val school: School,
    val user: DashboardUser,
    val invited: Boolean = false,
    val theme: SchoolTheme? = null,
    val flags: Map<String, Boolean> = emptyMap(),
)

/** §6 screen 20: a teacher of the school, read-only, with the classes she owns. */
@Serializable
data class TeacherSummary(
    val userId: String,
    val email: String,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val status: UserStatus = UserStatus.ACTIVE,
    val subjects: List<Subject> = emptyList(),
    val curriculum: Curriculum? = null,
    val grades: List<Int> = emptyList(),
    val classes: List<SchoolClass> = emptyList(),
    val lessonsPublished: Int = 0,
    val lastPublishedAt: Long? = null,
)

/**
 * `POST /schools/logo` (§6 screen 1): the logo and name of the school the address already belongs to, and nothing
 * else. It answers only when the domain belongs to exactly one school, so a shared domain (`gmail.com`) tells a
 * caller nothing, and it is rate-limited like sign-in.
 *
 * It is a POST although it reads: the address is a named person's, typed before anyone has signed in, and a query
 * string is logged by the access log, the load balancer, every forward proxy and the browser's own history. The
 * body is logged by none of those.
 */
@Serializable
data class SchoolLogo(val name: String, val logoUrl: String? = null)

/** The body of `POST /schools/logo`. A blank address answers 204, exactly as a domain no school owns does. */
@Serializable
data class SchoolLogoRequest(val email: String)
