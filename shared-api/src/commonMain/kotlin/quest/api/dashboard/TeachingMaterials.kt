package quest.api.dashboard

import kotlinx.serialization.Serializable

/**
 * Feature 31 `teaching material tracking` (docs/features/31-teaching-material-tracking.md): each teacher hands in her
 * weekly teaching material — دفتر التحضير — for every class and subject she teaches, and the leaders of feature 17's
 * board see who handed in on time, late or not at all, and mark it reviewed.
 *
 * Behind the `teachingMaterials` flag. `materials.self` is TEACHER (her own page) and `materials.board` is ADMIN,
 * MANAGERIAL, COORDINATOR, FLOOR_SUPERVISOR and TEACHER — the permission only opens the door, and which teachers a
 * caller sees is the attendance board's own scope ([BoardScope]), narrowed to the employees who teach.
 *
 * Weeks are the first teaching day of the week the material is **for** (`yyyy-MM-dd`, the school's week and zone);
 * `dueAt` and the times are ISO-8601 instants, with [MaterialBoard.timeZone] saying which zone to render them in.
 */

/** A row's one status, computed from the deadline and the review — never stored. */
object MaterialStatus {
    const val ON_TIME = "ON_TIME"
    const val LATE = "LATE"
    const val MISSING = "MISSING"
    const val REVIEWED = "REVIEWED"
}

/**
 * One (class, subject) a teacher is assigned to for that week. [submissionId] is absent while nothing has been
 * handed in, which is also the only time [status] is [MaterialStatus.MISSING]; the leaders' Mark reviewed names it.
 */
@Serializable
data class MaterialRow(
    val submissionId: String? = null,
    val classId: String,
    val className: String,
    val subject: String,
    val status: String,
    val attachmentId: String? = null,
    val attachmentName: String? = null,
    val note: String? = null,
    val submittedAt: String? = null,
    val reviewedBy: String? = null,
    val reviewedByName: String? = null,
    val reviewedAt: String? = null,
    val reviewComment: String? = null,
)

/** The four cards. Each is the number of rows its filter shows, so a card and the table never disagree. */
@Serializable
data class MaterialCounts(val onTime: Int, val late: Int, val missing: Int, val reviewed: Int)

/** `GET /teacher/materials?week=`: her own rows for one teaching week, and when they were due. */
@Serializable
data class TeacherMaterials(
    val week: String,
    val dueAt: String,
    val timeZone: String,
    val counts: MaterialCounts,
    val rows: List<MaterialRow> = emptyList(),
)

/** One teacher on the leaders' board, expandable to her (class, subject) rows. */
@Serializable
data class MaterialBoardRow(
    val teacherId: String,
    val fullName: String,
    val counts: MaterialCounts,
    val rows: List<MaterialRow> = emptyList(),
)

/** `GET /materials/board?week=`; `.csv` is the same rows as a file. [scope] is [BoardScope]'s. */
@Serializable
data class MaterialBoard(
    val week: String,
    val dueAt: String,
    val timeZone: String,
    val scope: String,
    val counts: MaterialCounts,
    val rows: List<MaterialBoardRow> = emptyList(),
)

/** `PUT /teacher/materials/{classId}/{subject}?week=`: at least one of the two is required. */
@Serializable
data class SubmitMaterialRequest(val attachmentId: String? = null, val note: String? = null)

/** `POST /materials/{id}/review`: a review is a decision, so a second one on the same row answers 409. */
@Serializable
data class ReviewMaterialRequest(val comment: String? = null)

/**
 * `GET`/`PUT /admin/material-settings`, on the Attendance settings page under `attendance.settings.read`/`.write`.
 * [dueWeekday] is a `DayOfWeek` name and [dueTime] `HH:mm`, both read in the school's zone; the material for a week
 * is due at that weekday and time in the week **before** it. No row is the defaults — Thursday 12:00, four hours.
 */
@Serializable
data class MaterialSettings(
    val dueWeekday: String,
    val dueTime: String,
    val remindHoursBefore: Int,
    val timeZone: String,
)

/** An absent field keeps what the school has; the whole card is sent by the dashboard either way. */
@Serializable
data class UpdateMaterialSettingsRequest(
    val dueWeekday: String? = null,
    val dueTime: String? = null,
    val remindHoursBefore: Int? = null,
)

/**
 * Feature 31: the query of a material reminder's link, `/notifications?material=reminder&week=…&missing=…`. Both
 * clients read it to word the row in the reader's language, as they do feature 16's `AttendanceLink`.
 */
object MaterialLink {
    const val TYPE = "material"
    const val WEEK = "week"
    const val MISSING = "missing"
    const val REMINDER = "reminder"
}
