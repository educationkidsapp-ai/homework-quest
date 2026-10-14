package quest.api.dashboard

import kotlinx.serialization.Serializable

/**
 * Feature 17 `attendance-board-report` (docs/features/17-attendance-board-report.md): the leaders' Staff attendance
 * page — today's board, one employee's day, and the monthly report. Behind the `staffAttendance` flag;
 * `attendance.board` is ADMIN, MANAGERIAL, COORDINATOR and TEACHER, and the server narrows the rows to the people the
 * caller leads ([BoardScope]).
 *
 * Times are `HH:mm` in the school's time zone, dates `yyyy-MM-dd`, months `yyyy-MM`. An employee is (`kind`, `id`) as
 * in [StaffAttendanceProfile]: `USER` keys a user, `WORKER` a worker.
 */

/** A board row's one status. `noCheckOut` is a separate flag a present row can also carry. */
object BoardStatus {
    const val PRESENT = "PRESENT"
    const val LATE = "LATE"
    const val NOT_ARRIVED = "NOT_ARRIVED"
    const val REMOTE = "REMOTE"
    const val ABSENT = "ABSENT"
    const val UPCOMING = "UPCOMING"
    const val OFF = "OFF"
    const val HOLIDAY = "HOLIDAY"
}

/** How wide the caller's view is: the whole school (Admin), her academy and reports (manager), her reports only. */
object BoardScope {
    const val SCHOOL = "SCHOOL"
    const val ACADEMY = "ACADEMY"
    const val REPORTS = "REPORTS"
}

@Serializable
data class BoardRow(
    val kind: String,
    val id: String,
    val fullName: String,
    val role: String,
    val category: String,
    val academies: List<String> = emptyList(),
    val checkIn: String? = null,
    val checkOut: String? = null,
    val status: String,
    val lateMinutes: Int? = null,
    val noCheckOut: Boolean = false,
    val mode: String? = null,
)

/** The five cards; each is the number of rows its filter shows. */
@Serializable
data class BoardCounts(val present: Int, val late: Int, val notArrived: Int, val remote: Int, val noCheckOut: Int)

/** `GET /staff-attendance/board`. */
@Serializable
data class AttendanceBoard(
    val day: String,
    val timeZone: String,
    val scope: String,
    val counts: BoardCounts,
    val rows: List<BoardRow>,
)

/** One check-in or check-out call, as the server logged it. [reason] is a `CheckInRefusal` code when refused. */
@Serializable
data class BoardAttempt(val at: String, val action: String, val result: String, val reason: String? = null, val distanceMeters: Int? = null)

/** A notification feature 16 sent about the day (`staff_attendance_alerts.type`). */
@Serializable
data class BoardAlert(val type: String, val sentAt: String)

/** One day of the month calendar; [mode] `manual` is a mark taken by hand before the feature was switched on. */
@Serializable
data class AttendanceCalendarDay(
    val date: String,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val status: String,
    val lateMinutes: Int? = null,
    val mode: String? = null,
)

/** `GET /staff-attendance/employees/{kind}/{id}?day=`. */
@Serializable
data class EmployeeDay(
    val kind: String,
    val id: String,
    val fullName: String,
    val category: String,
    val day: String,
    val attempts: List<BoardAttempt> = emptyList(),
    val alerts: List<BoardAlert> = emptyList(),
    val month: String,
    val calendar: List<AttendanceCalendarDay> = emptyList(),
)

/** One employee's month, ready for payroll. Minutes are whole minutes; corrections arrive with feature 18. */
@Serializable
data class ReportRow(
    val kind: String,
    val id: String,
    val fullName: String,
    val category: String,
    val academies: List<String> = emptyList(),
    val workingDays: Int,
    val presentOnsite: Int,
    val presentRemote: Int,
    val lateCount: Int,
    val lateMinutes: Int,
    val absentDays: Int,
    val earlyLeaveMinutes: Int,
    val missingCheckOuts: Int,
    val corrections: Int,
)

/** `GET /staff-attendance/report?month=yyyy-MM`; `.csv` and `.xlsx` are the same rows as files. */
@Serializable
data class AttendanceReport(val month: String, val scope: String, val rows: List<ReportRow>)
