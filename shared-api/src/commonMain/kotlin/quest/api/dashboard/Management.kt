package quest.api.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import quest.api.dto.Curriculum
import quest.api.dto.Subject

/**
 * RM1 `backend/manager-scope-reads` — `docs/plan.md` phase R, decision DR5.
 *
 * A **department manager** runs one curriculum (British or American) across every grade of her school: every class,
 * every teacher, every coordinator and every child of that track, and nothing of the other one. She is
 * `Role.MANAGERIAL`, and RM1 is read-only — her writes are the weekly plan, announcements and chat (RM2) and staff
 * attendance (RM5). Her scope rows live in the same `staff_scopes` table a coordinator's do, with the halves swapped:
 * `subject` is null and `curriculum` is the department.
 *
 * [CoordinatorScopeRow] is her mirror image, one axis over — a coordinator is narrow in subject and wide in grade, a
 * manager wide in subject and narrow in track — which is why three of her screens answer the coordinator's own types:
 * `GET /management/teachers` is a list of [CoordinatorTeacher], the cards inside [ManagementGradeGroup] are
 * [CoordinatorClass], and `GET /management/calendar` is a [CoordinatorCalendar]. They are the same screens with a wider
 * scope, so they are the same types.
 */

/**
 * `GET /management/me`: her departments and how big they are — the five numbers her Home leads with, so the landing
 * screen costs one request.
 */
@Serializable
data class ManagerMe(
    val userId: String,
    val email: String,
    val displayName: String,
    val departments: List<Curriculum> = emptyList(),
    val grades: Int = 0,
    val sections: Int = 0,
    val teachers: Int = 0,
    val coordinators: Int = 0,
    val children: Int = 0,
)

/**
 * `GET /management/coordinators`: one of the people she manages. [curricula] carries a `null` entry where a
 * coordinator's row names no track, which DR5 reads as both of them — such a coordinator reports to both managers.
 * [sections] is how much of the department she covers.
 */
@Serializable
data class ManagerCoordinator(
    val userId: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val subjects: List<Subject> = emptyList(),
    val curricula: List<Curriculum?> = emptyList(),
    val sections: Int = 0,
)

/** `GET /management/classes`: one grade of the department, with its section cards already resolved. */
@Serializable
data class ManagementGradeGroup(
    val curriculum: Curriculum,
    val grade: Int,
    val sections: Int = 0,
    val children: Int = 0,
    val classes: List<CoordinatorClass> = emptyList(),
)

/** A teacher of the department who published nothing inside the window — the dashboard's `teacher.quiet` row. */
@Serializable
data class QuietTeacher(val userId: String, val email: String, val displayName: String)

/**
 * One grade of the department over the window — or the department itself, which [ManagementStats.total] carries in the
 * same shape with [grade] 0 and no [curriculum].
 *
 * [attendanceRate] is absent when nothing was marked in the window rather than 100, so an empty register reads as "no
 * answer" instead of a perfect one. [examAverage] and [examPassRate] are absent where the grade has no exam anyone sat;
 * they are a statistics screen's numbers, folded out of the stars a child earned per stop, and the exact released
 * figures are one click away on `GET /management/exams/{id}/results`.
 */
@Serializable
data class ManagementGradeStats(
    val curriculum: Curriculum? = null,
    val grade: Int = 0,
    val sections: Int = 0,
    val children: Int = 0,
    val attendanceRate: Double? = null,
    val lessonsPublished: Int = 0,
    val lessonsPlayed: Int = 0,
    val exams: Int = 0,
    val examAverage: Int? = null,
    val examPassRate: Int? = null,
    val quietTeachers: List<QuietTeacher> = emptyList(),
)

/** `GET /management/stats?from&to`: a row per grade and the department's own total, in one response. */
@Serializable
data class ManagementStats(
    val from: LocalDate,
    val to: LocalDate,
    val grades: List<ManagementGradeStats> = emptyList(),
    val total: ManagementGradeStats,
)

// ---------------------------------------------------------------------------------------------------------------
// Admin: creating a manager and changing her department (`/admin/managers/` routes, ADMIN only)
// ---------------------------------------------------------------------------------------------------------------

/** A manager as the Admin's screen sees her, with every department she holds. */
@Serializable
data class ManagerAccount(
    val userId: String,
    val email: String,
    val fullName: String,
    val status: UserStatus = UserStatus.ACTIVE,
    val departments: List<Curriculum> = emptyList(),
)

/** `POST /admin/managers`: one department to start with, the shape `managers.csv` has. */
@Serializable
data class CreateManagerRequest(val fullName: String, val email: String, val curriculum: Curriculum)

/** 201, with the one and only sight of the password — the same contract [CoordinatorCreated] has. */
@Serializable
data class ManagerCreated(val manager: ManagerAccount, val temporaryPassword: String)

/** `PUT /admin/managers/{id}/scopes`: the complete set of departments she should hold afterwards. */
@Serializable
data class ManagerDepartmentsRequest(val curricula: List<Curriculum> = emptyList())
