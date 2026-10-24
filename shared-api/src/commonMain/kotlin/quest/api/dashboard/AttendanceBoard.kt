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
    /**
     * Feature 34: she is on approved leave that day. With no check-in it wins over every other status — she is never
     * `NOT_ARRIVED` or `ABSENT` on it and no attendance alert is sent about it — and the board and the report show
     * "إجازة / On leave".
     */
    const val ON_LEAVE = "ON_LEAVE"
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
    /** Feature 34: the days of approved leave inside the month, counted as school days of her own schedule. */
    val leaveDays: Int = 0,
    /** Feature 34: how many approved permissions fell inside the month. */
    val permissionCount: Int = 0,
    /** Feature 34: the minutes of lateness and early leaving an approved permission excused. */
    val excusedMinutes: Int = 0,
)

/** `GET /staff-attendance/report?month=yyyy-MM`; `.csv` and `.xlsx` are the same rows as files. */
@Serializable
data class AttendanceReport(val month: String, val scope: String, val rows: List<ReportRow>)

// ---------------------------------------------------------------- feature 34: the two leave reports

/**
 * Feature 34, one employee's balance of one leave type for the year. [allowanceDays] and [remainingDays] are null for
 * a type with no balance.
 */
@Serializable
data class LeaveBalanceCell(
    val type: String,
    val allowanceDays: Int? = null,
    val usedDays: Int = 0,
    val remainingDays: Int? = null,
)

/** Feature 34, one row of the balance report: one employee and her balance of every enabled leave type. */
@Serializable
data class LeaveBalanceRow(
    val kind: String,
    val id: String,
    val fullName: String,
    val category: String,
    val academies: List<String> = emptyList(),
    val balances: List<LeaveBalanceCell> = emptyList(),
)

/**
 * `GET /staff-attendance/leave/balances?year=`; `.csv` and `.xlsx` are the same rows as files. [types] is the order
 * the cells come in, so a file's columns and a table's are the same.
 */
@Serializable
data class LeaveBalanceReport(
    val year: Int,
    val scope: String,
    val types: List<String> = emptyList(),
    val rows: List<LeaveBalanceRow> = emptyList(),
)

/**
 * Feature 34, one row of the monthly requests report: every request of the month with its employee, type, dates or
 * times, days or minutes, status and decider. [date] / [toDate] are `yyyy-MM-dd`, [fromTime] / [toTime] `HH:mm`.
 */
@Serializable
data class LeaveRequestRow(
    val id: String,
    val kind: String,
    val staffId: String,
    val fullName: String,
    val category: String,
    val type: String,
    val leaveType: String? = null,
    val date: String,
    val toDate: String? = null,
    val fromTime: String? = null,
    val toTime: String? = null,
    val days: Int? = null,
    val minutes: Int? = null,
    val status: String,
    val stage: String? = null,
    val reason: String,
    val rejectReason: String? = null,
    val decidedBy: String? = null,
    val decidedOn: String? = null,
)

/** `GET /staff-attendance/leave/requests?month=yyyy-MM`; `.csv` and `.xlsx` are the same rows as files. */
@Serializable
data class LeaveRequestReport(val month: String, val scope: String, val rows: List<LeaveRequestRow> = emptyList())

// ---------------------------------------------------------------- feature 34: the Admin's Leave settings

/** Feature 34, the Admin's row per leave type: whether the school offers it and when it needs a document. */
@Serializable
data class LeaveTypeSetting(val type: String, val enabled: Boolean = true, val documentAfterDays: Int? = null)

/** Feature 34, the Admin's yearly allowance of one leave type for one staff category, in days. */
@Serializable
data class LeaveBalanceSetting(val type: String, val category: String, val yearlyDays: Int)
