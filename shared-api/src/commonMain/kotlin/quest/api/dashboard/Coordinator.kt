package quest.api.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import quest.api.dto.Curriculum
import quest.api.dto.Subject

/**
 * R2 `backend/coordinator-role` — `docs/plan.md` phase R, decisions DR1 and DR2.
 *
 * A **coordinator** supervises one subject for a track (American / British) or for both, across every grade and every
 * class of her school. She is [Role.COORDINATOR], and DR2 makes her **read-only on teaching data**: everything under
 * everything under `/coordinator` delegates to the services the teacher's own screens read, narrowed to the sections her
 * `staff_scopes` rows cover. There is no write route in this file at all — her writes are communication (chat,
 * complaints, announcements) and arrive with R4.
 *
 * Kept in its own file for the reason [Teacher] is: one role's area, read in one place.
 */

/**
 * One line of her scope: a subject, and a track — or both tracks when [curriculum] is absent. A coordinator may hold
 * several, and the pair is unique per person (`staff_scopes`).
 */
@Serializable
data class CoordinatorScopeRow(val subject: Subject, val curriculum: Curriculum? = null)

/**
 * `GET /coordinator/me`: what she coordinates and how big it is — the three numbers her Home leads with, so the
 * landing screen costs one request. [sections] counts the classes in scope, [teachers] the people who teach her
 * subject in them, and [children] the pupils sitting in them.
 */
@Serializable
data class CoordinatorMe(
    val userId: String,
    val email: String,
    val displayName: String,
    val scopes: List<CoordinatorScopeRow> = emptyList(),
    val sections: Int = 0,
    val teachers: Int = 0,
    val children: Int = 0,
)

/** One (section, subject) a teacher holds inside the coordinator's scope. */
@Serializable
data class CoordinatorAssignmentRef(val classId: String, val className: String, val subject: Subject)

/**
 * `GET /coordinator/teachers`: the people she supervises — every teacher holding at least one assignment in scope,
 * with the subjects and sections that put her there. Read-only: nothing here edits a teacher.
 */
@Serializable
data class CoordinatorTeacher(
    val userId: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    /** MH1 (owner's item 4): her mobile number, so the Teachers screen can show it and dial it. */
    val phone: String? = null,
    val subjects: List<Subject> = emptyList(),
    val sections: List<CoordinatorAssignmentRef> = emptyList(),
    /**
     * MH1: the coordinator(s) above her — one per (subject, track) of her slots that a coordinator's scope covers.
     * Answered on `GET /management/teachers` and the manager's people directory; empty on `GET /coordinator/teachers`,
     * where the reader is the person on this list.
     */
    val coordinators: List<TeacherCoordinator> = emptyList(),
)

/** One coordinator above a teacher, and the subject that puts her there — enough to name her and open a thread. */
@Serializable
data class TeacherCoordinator(val userId: String, val displayName: String, val subject: Subject)

/**
 * `GET /coordinator/classes`: one card per (section, subject) in scope, with today's lesson already resolved — the
 * same shape the teacher's "My classes" uses ([TeacherClassCard]), plus the teacher's name, because a coordinator is
 * looking across people rather than at her own week.
 */
@Serializable
data class CoordinatorClass(
    val classId: String,
    val className: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject,
    val teacherId: String? = null,
    val teacherName: String? = null,
    val childrenCount: Int = 0,
    val todayLessonId: String? = null,
    val todayStatus: WeekLessonStatus = WeekLessonStatus.NONE,
)

/** One lesson on one day of one class, as the coordinator's calendar shows it. */
@Serializable
data class CoordinatorCalendarLesson(
    val classId: String,
    val className: String,
    val subject: Subject,
    val lessonId: String,
    val title: String? = null,
    val status: WeekLessonStatus = WeekLessonStatus.NONE,
    val type: LessonType = LessonType.HOMEWORK,
    val playedCount: Int = 0,
    val childrenCount: Int = 0,
)

/** One day of the window. [schoolDay] comes from the school's own week, so a weekend renders as a weekend. */
@Serializable
data class CoordinatorCalendarDay(
    val date: LocalDate,
    val schoolDay: Boolean = true,
    val lessons: List<CoordinatorCalendarLesson> = emptyList(),
)

/**
 * `GET /coordinator/calendar?from&to`: every class in scope, day by day, in one response — the cross-class view the
 * owner's spec asks for. The window defaults to the current school week and is capped at 62 days.
 */
@Serializable
data class CoordinatorCalendar(
    val from: LocalDate,
    val to: LocalDate,
    val days: List<CoordinatorCalendarDay> = emptyList(),
)

/** T1: which kind of job [StaffJobParts] describes, so the client picks the sentence rather than parsing one. */
@Serializable
enum class StaffJobKind { @SerialName("coordinator") COORDINATOR, @SerialName("manager") MANAGER }

/**
 * T1 (owner's list, 2026-10-01): a job title **in parts**, so the dashboard writes it in the reader's own language
 * instead of translating an English sentence. A coordinator's parts are the ones that put her on *this* caller's
 * list — the grades of the caller's own sections she covers, the subject and the track — so "Coordinator · Grade 1 ·
 * Math · British"; a manager's are her department alone, so "British department manager". [grades] is empty on a
 * manager's row and carries every grade of the caller's that the coordinator covers **in [curriculum]**, ascending.
 *
 * One of these per track, never flattened across them ([StaffContact.jobParts] is a list): a coordinator who covers
 * British grade 1 and American grade 3 of one teacher's is two entries, because "Grades 1, 3" of either track would
 * name a grade she does not coordinate for her.
 */
@Serializable
data class StaffJobParts(
    val kind: StaffJobKind,
    val grades: List<Int> = emptyList(),
    val subject: String? = null,
    val curriculum: Curriculum? = null,
)

/**
 * T1: one person in the staff directory — `GET /teacher/coordinators`, `GET /teacher/managers` and
 * `GET /coordinator/managers` all answer this shape, because they are one screen ("who may I write to, and who is
 * she?") asked from two roles. It is RM1's `CoordinatorManager` grown the contact details the owner asked for:
 * [userId], [displayName] and [curriculum] are the fields that chooser already carried, so a client written against
 * it reads this row unchanged.
 *
 * [job] is the English sentence to fall back on; [jobParts] is the same thing localisable, **one entry per track**,
 * and [job] joins their sentences with "; ". [curriculum] is the first of those tracks and [subjects] the union across
 * them, kept for the RM1 client that reads one word of each. [online] is presence (T1): true while she holds at least
 * one live `/ws/chat` socket, the same signal [quest.api.dto.ChatThread.peerOnline] carries on a thread row, and it
 * goes stale only until the next `presence` frame.
 */
@Serializable
data class StaffContact(
    val userId: String,
    val displayName: String,
    val email: String,
    val role: String,
    val job: String,
    val jobParts: List<StaffJobParts> = emptyList(),
    val phone: String? = null,
    val curriculum: Curriculum? = null,
    val subjects: String? = null,
    val online: Boolean = false,
)

// ---------------------------------------------------------------------------------------------------------------
// Admin: creating a coordinator and changing what she coordinates (`/admin/coordinators/` routes, ADMIN only)
// ---------------------------------------------------------------------------------------------------------------

/** A coordinator as the Admin's screen sees her, with her whole scope. */
@Serializable
data class CoordinatorAccount(
    val userId: String,
    val email: String,
    val fullName: String,
    val phone: String? = null,
    val status: UserStatus = UserStatus.ACTIVE,
    val scopes: List<CoordinatorScopeRow> = emptyList(),
)

/** `POST /admin/coordinators`. At least one scope; a subject named twice for the same track is refused. */
@Serializable
data class CreateCoordinatorRequest(
    val fullName: String,
    val email: String,
    val phone: String? = null,
    val scopes: List<CoordinatorScopeRow> = emptyList(),
)

/** 201, with the one and only sight of the password — the same contract [TeacherCreated] has. */
@Serializable
data class CoordinatorCreated(val coordinator: CoordinatorAccount, val temporaryPassword: String)

/** `PUT /admin/coordinators/{id}/scopes`: the complete set she should hold afterwards, as with her assignments. */
@Serializable
data class CoordinatorScopesRequest(val scopes: List<CoordinatorScopeRow> = emptyList())

/**
 * `PATCH /admin/coordinators/{id}` (MA1, the owner's item 3 — "coordinators page like the teachers page"): only the
 * fields that are present are written. [UpdateTeacherRequest] narrowed to what a coordinator holds; her scope is
 * [CoordinatorScopesRequest]'s.
 *
 * `active = false` disables the account *and* revokes its refresh tokens, so a session already open dies with it, and
 * nobody may disable her own account. `POST /admin/coordinators/{id}/reset-password` answers a [TemporaryPassword]
 * once, exactly as a teacher's does.
 */
@Serializable
data class UpdateCoordinatorRequest(
    val fullName: String? = null,
    val phone: String? = null,
    val active: Boolean? = null,
)
