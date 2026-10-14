package quest.api.dashboard

import kotlinx.serialization.Serializable

/**
 * Feature 14 `attendance-settings` (docs/features/14-attendance-settings.md): where and when the school's staff must be
 * present, and who hears about each employee. Behind the `staffAttendance` flag; `attendance.settings.read` is ADMIN
 * and MANAGERIAL, `attendance.settings.write` ADMIN only.
 *
 * Times are `HH:mm` and dates `yyyy-MM-dd`, both in the school's time zone ([AttendanceSettings.timeZone], which is
 * `schools.timezone` and is shown, not edited, here). Weekdays are `java.time.DayOfWeek` names (`SUNDAY` …).
 *
 * Kept in its own file for the reason [Worker] is: one screen's contract, read in one place.
 */

/** The three staff categories. Every employee has one; the default comes from the role. */
object StaffCategory {
    const val TEACHER = "TEACHER"
    const val ADMINISTRATIVE = "ADMINISTRATIVE"
    const val WORKER = "WORKER"
    val ALL = listOf(TEACHER, ADMINISTRATIVE, WORKER)
}

/** Who the academy director and the Admin hear from, each set separately. The direct manager is always immediate. */
object NotifyPolicy {
    const val IMMEDIATE = "IMMEDIATE"
    const val DIGEST = "DIGEST"
    const val OFF = "OFF"
}

/**
 * One category's hours. [ramadanFrom]..[ramadanTo] (inclusive) with [ramadanStart]..[ramadanEnd] replace
 * [start]..[end] inside the range; all four are present or all four are absent.
 */
@Serializable
data class WorkSchedule(
    val category: String,
    val weekdays: List<String> = listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY"),
    val start: String = "07:00",
    val end: String = "14:00",
    val ramadanFrom: String? = null,
    val ramadanTo: String? = null,
    val ramadanStart: String? = null,
    val ramadanEnd: String? = null,
)

/** A named date range (inclusive) on which no attendance is expected. */
@Serializable
data class AttendanceHoliday(val name: String, val from: String, val to: String)

/** `GET /admin/attendance-settings` — the whole page, with defaults where the school has set nothing. */
@Serializable
data class AttendanceSettings(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = 150,
    val graceMinutes: Int = 10,
    val notArrivedMinutes: Int = 60,
    val missingCheckOutMinutes: Int = 60,
    val directorNotify: String = NotifyPolicy.DIGEST,
    val adminNotify: String = NotifyPolicy.DIGEST,
    val digestTime: String = "07:30",
    val timeZone: String = "Asia/Riyadh",
    val schedules: List<WorkSchedule> = emptyList(),
    val holidays: List<AttendanceHoliday> = emptyList(),
)

/** `PUT /admin/attendance-settings` — replaces the page. The time zone is not part of it. */
@Serializable
data class UpdateAttendanceSettingsRequest(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = 150,
    val graceMinutes: Int = 10,
    val notArrivedMinutes: Int = 60,
    val missingCheckOutMinutes: Int = 60,
    val directorNotify: String = NotifyPolicy.DIGEST,
    val adminNotify: String = NotifyPolicy.DIGEST,
    val digestTime: String = "07:30",
    val schedules: List<WorkSchedule> = emptyList(),
    val holidays: List<AttendanceHoliday> = emptyList(),
)

/** An employee: `USER` is a teacher, coordinator or manager (`users.id`), `WORKER` a worker (`workers.id`). */
@Serializable
data class StaffRef(val kind: String, val id: String)

/**
 * `GET /admin/staff/attendance-profiles` — one row per active employee. [category] is the effective one and
 * [categorySet] says whether the Admin chose it or it is the role's default. [directManagerId] is the one set on the
 * employee, absent when the fallback applies.
 */
@Serializable
data class StaffAttendanceProfile(
    val kind: String,
    val id: String,
    val fullName: String,
    val role: String,
    val category: String,
    val categorySet: Boolean = false,
    val directManagerId: String? = null,
    val directManagerName: String? = null,
)

/**
 * `PUT /admin/staff/attendance-profiles` — one employee from the edit form, or several from the list. Only what is
 * present is written: [directManagerId] sets the manager, [clearDirectManager] removes it, [category] sets the
 * category and [clearCategory] returns it to the role's default.
 */
@Serializable
data class UpdateStaffAttendanceProfilesRequest(
    val staff: List<StaffRef>,
    val directManagerId: String? = null,
    val clearDirectManager: Boolean = false,
    val category: String? = null,
    val clearCategory: Boolean = false,
)
