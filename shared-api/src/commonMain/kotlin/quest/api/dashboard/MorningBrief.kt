package quest.api.dashboard

import kotlinx.serialization.Serializable

/**
 * Feature 25 `morning-brief` (docs/features/25-morning-brief.md): the few lines an Admin or a department manager
 * reads first thing — `GET /me/morning-brief`, permission `brief.read`, behind the `morningBrief` flag.
 *
 * **The server counts; the model only phrases.** Every number in [MorningBriefFacts] is computed by the server out of
 * what features 16, 17, 18, 12 and 04 already store, and narrowed to the caller exactly as feature 21's Home is: the
 * Admin reads the school, a manager reads her academy ([MorningBriefFacts.scope]). The AI is handed those numbers and
 * nothing else — **no child, parent or employee is ever named to it** — and asked for three to five short lines in
 * Arabic and in English. A line that carries a number the facts do not hold is rejected, and the brief falls back to
 * a plain bilingual template built from the very same facts ([MorningBrief.source] is then `template`).
 *
 * **One brief per leader per school day.** A job writes them from 07:15 in the school's own zone; a leader who opens
 * her Home first has hers built on the spot. The route answers `204 No Content` on a day the school does not teach.
 *
 * [MorningBrief.day] is `yyyy-MM-dd` in the school's zone — never the server's and never the browser's.
 */

/**
 * What the brief is about, in numbers only. Each one is already narrowed to the caller's reach, so a manager's
 * `lateYesterday` counts her academy's people and the Admin's counts the school's.
 *
 * - [scope] is `SCHOOL` for the Admin and `ACADEMY` for a manager, as on the staff attendance board.
 * - [yesterday] is the school's previous *teaching* day, which is what the staff numbers are about.
 * - [childAttendanceRate] is `(present + late) / marked` as a whole percentage over yesterday's registers in her
 *   reach, and is **null** when no register was taken — a missing number, never a zero.
 * - [remoteStudyDayToday] is feature 23's remote study day covering an academy she reaches, with
 *   [remoteStudyDayReason] the school's own words for why. A leader is staff, so the academy alone decides whether it
 *   is hers; the grades on the day narrow children and parents. False, and the reason null, when `remoteStudyDays` is
 *   off.
 */
@Serializable
data class MorningBriefFacts(
    val scope: String,
    val yesterday: String,
    val lateYesterday: Int = 0,
    val absentYesterday: Int = 0,
    val missingCheckoutYesterday: Int = 0,
    val requestsWaiting: Int = 0,
    val overdueComplaints: Int = 0,
    val eventsToday: Int = 0,
    val childAttendanceRate: Int? = null,
    val remoteStudyDayToday: Boolean = false,
    val remoteStudyDayReason: String? = null,
)

/**
 * `GET /me/morning-brief`. [linesAr] and [linesEn] say the same things in the same order, three to five lines each,
 * and a client shows the list for the reader's own language. [source] is `ai` when the model's wording was accepted
 * and `template` when the deterministic fallback was used — the dashboard does not have to show it, but QA does.
 */
@Serializable
data class MorningBrief(
    val day: String,
    val linesAr: List<String> = emptyList(),
    val linesEn: List<String> = emptyList(),
    val source: String = SOURCE_TEMPLATE,
    val facts: MorningBriefFacts,
) {
    companion object {
        /** The model phrased it. */
        const val SOURCE_AI: String = "ai"
        /** The deterministic bilingual template, from the same facts: the model failed or invented a number. */
        const val SOURCE_TEMPLATE: String = "template"
    }
}
