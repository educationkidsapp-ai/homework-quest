package quest.api.staff

import kotlinx.serialization.Serializable

/**
 * Feature 23 `remote study days` (docs/features/23-remote-study-days.md): the school, one academy, or chosen grades
 * study from home for a day. It **replaces** feature 18's school-wide remote day — `GET`/`POST`
 * `/admin/remote-study-days` and `DELETE /admin/remote-study-days/{id}`, behind the `remoteStudyDays` flag and the
 * `attendance.settings.read` / `attendance.settings.write` permissions feature 18's routes carried.
 *
 * **Who is in scope.** [academy] null is every academy and [grades] empty is every grade, so a row with neither is
 * the whole school. The two narrow different people: **staff** are in scope by the academy alone — a grade does not
 * say who teaches it — and check in from anywhere that day, while **children and their parents** are in scope by the
 * academy *and* the grade.
 *
 * **What creating one does.** The parents of every class in scope are sent a bilingual announcement that expires at
 * the end of that day, with the push every announcement carries. Deleting the day expires that announcement and
 * sends nothing new. Feature 18's per-employee remote-day request ([AttendanceRequestType.REMOTE_DAY]) is untouched
 * and goes on working whether this flag is on or off.
 */
@Serializable
data class RemoteStudyDay(
    val id: String,
    /** The day, `yyyy-MM-dd` in the school's zone. */
    val date: String,
    /** A curriculum such as `british`, lower-cased; null is every academy. */
    val academy: String? = null,
    /** The grades that study from home; empty is every grade of [academy]. */
    val grades: List<Int> = emptyList(),
    val reason: String,
)

/** `GET /admin/remote-study-days`: today's and the coming days, in date order. A past day is history. */
@Serializable
data class RemoteStudyDayList(val days: List<RemoteStudyDay>)

/**
 * `POST /admin/remote-study-days`. [date] is today or later in the school's zone, [academy] one of the school's
 * curricula (or null for all of them), [grades] a subset of the school's grades (or empty for all of them), and
 * [reason] 1–200 characters — it is what the parents' announcement says.
 */
@Serializable
data class CreateRemoteStudyDay(
    val date: String,
    val academy: String? = null,
    val grades: List<Int> = emptyList(),
    val reason: String,
)
