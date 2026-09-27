package quest.api.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
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

/**
 * `GET /management/admins` (RM2, DR5): a platform admin she may open a chat thread with — "the manager reports to and
 * chats with the admin". Two fields, because an admin has no department and no school to name.
 */
@Serializable
data class ManagerAdmin(val userId: String, val displayName: String)

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

// ---------------------------------------------------------------------------------------------------------------
// RM5 `backend/staff-attendance-people` — DR7: the staff register, and the department's people directory
// ---------------------------------------------------------------------------------------------------------------

/** The four things a day can say about a member of staff. V21's `CHECK` constraint holds exactly these. */
@Serializable
enum class StaffAttendanceStatus {
    @SerialName("present") PRESENT,
    @SerialName("absent") ABSENT,
    @SerialName("late") LATE,
    @SerialName("leave") LEAVE,
}

/** Which of the two roles of a department puts somebody on its register. */
@Serializable
enum class StaffRole { @SerialName("TEACHER") TEACHER, @SerialName("COORDINATOR") COORDINATOR }

/**
 * One person on the staff register. [status] is absent while nobody has marked her — never `present` by default, the
 * rule [ManagementGradeStats.attendanceRate] follows: an unmarked day is no answer rather than a good one.
 */
@Serializable
data class StaffAttendanceRow(
    val userId: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val role: StaffRole,
    val status: StaffAttendanceStatus? = null,
    val note: String? = null,
    val markedBy: String? = null,
    val markedAt: Long? = null,
)

/**
 * `GET /management/staff-attendance?day=` and the body the `PUT` answers: the department's teachers and coordinators
 * for one day. [editable] is false on a day nobody may mark — a non-teaching day, or one still to come — and is what
 * a screen offers the marking on; the server refuses such a day with a 400 either way.
 */
@Serializable
data class StaffAttendanceDay(
    val day: LocalDate,
    val schoolDay: Boolean = true,
    val editable: Boolean = false,
    val people: List<StaffAttendanceRow> = emptyList(),
    val present: Int = 0,
    val absent: Int = 0,
    val late: Int = 0,
    val leave: Int = 0,
    val unmarked: Int = 0,
)

/** One line of the `PUT` body: whom, what, and an optional note of at most 500 characters. */
@Serializable
data class MarkStaffAttendance(val userId: String, val status: StaffAttendanceStatus, val note: String? = null)

/**
 * One person's month: the four counts, the teaching days nobody marked, and [rate] — `(present + late) / marked`,
 * absent where nothing was marked at all, so an empty register is no answer rather than a perfect one.
 */
@Serializable
data class StaffAttendanceSummaryRow(
    val userId: String,
    val email: String,
    val displayName: String,
    val role: StaffRole,
    val present: Int = 0,
    val absent: Int = 0,
    val late: Int = 0,
    val leave: Int = 0,
    val unmarked: Int = 0,
    val rate: Double? = null,
)

/**
 * `GET /management/staff-attendance/summary?month=YYYY-MM`: a row per person over the teaching days of that month up
 * to today, so a month still running is not scored as though its remaining days were missed.
 */
@Serializable
data class StaffAttendanceSummary(
    val month: String,
    val from: LocalDate,
    val to: LocalDate,
    val schoolDays: Int = 0,
    val people: List<StaffAttendanceSummaryRow> = emptyList(),
)

/** One marked day of one person's history. */
@Serializable
data class StaffAttendanceMark(
    val day: LocalDate,
    val status: StaffAttendanceStatus,
    val note: String? = null,
    val markedBy: String? = null,
    val markedAt: Long = 0,
)

/** `GET /management/staff-attendance/{userId}?from&to`: one person's marked days, newest first. */
@Serializable
data class StaffAttendanceHistory(
    val userId: String,
    val displayName: String,
    val role: StaffRole,
    val from: LocalDate,
    val to: LocalDate,
    val marks: List<StaffAttendanceMark> = emptyList(),
)

/**
 * `GET /management/people/children`: a child of the department with the contact the school actually holds.
 *
 * Two addresses, because the school has two: [parentEmail] is the account a parent signed up with (absent until she
 * does) and [rosterEmail] is what the imported roster carries. **No telephone number is returned because no table
 * holds one** — neither the roster nor a staff account has the column. [placedAt] is when the child joined the section,
 * in epoch millis like every other timestamp here.
 */
@Serializable
data class DirectoryChild(
    val childId: String,
    val name: String,
    val classId: String? = null,
    val className: String? = null,
    val grade: Int = 0,
    val curriculum: Curriculum,
    val parentEmail: String? = null,
    val rosterEmail: String? = null,
    val placedAt: Long = 0,
)

/** One page of a directory list. [total] is every match, not the page; `size` 0 asks for the default of 25. */
@Serializable
data class ChildDirectory(
    val page: Int = 0,
    val size: Int = 25,
    val total: Int = 0,
    val rows: List<DirectoryChild> = emptyList(),
)

/**
 * `GET /management/people/teachers`. The rows are [CoordinatorTeacher] — her subjects and her sections are exactly
 * what `GET /management/teachers` already answers, so they are the same type rather than a copy that can drift.
 */
@Serializable
data class TeacherDirectory(
    val page: Int = 0,
    val size: Int = 25,
    val total: Int = 0,
    val rows: List<CoordinatorTeacher> = emptyList(),
)

/** `GET /management/people/coordinators`, in [ManagerCoordinator]'s shape for the same reason. */
@Serializable
data class CoordinatorDirectory(
    val page: Int = 0,
    val size: Int = 25,
    val total: Int = 0,
    val rows: List<ManagerCoordinator> = emptyList(),
)
