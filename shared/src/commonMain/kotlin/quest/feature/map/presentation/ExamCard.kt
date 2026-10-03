package quest.feature.map.presentation

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import quest.api.dto.Island
import quest.feature.content.domain.ChildResult
import quest.api.dto.IslandState
import quest.feature.journey.presentation.LessonStrings

/**
 * §8: where one exam stands for this student, as the home card says it.
 *
 * **The server decides whether an exam can be sat, not this device.** It puts an exam on the map only while this
 * student may sit it, so an island that carries `examWindow` is open — whatever the tablet's clock says. A slow clock
 * must never show "Not open yet" on a paper the server sent as open, and a fast one must never shut it; if the window
 * really has closed since the page was loaded, the server refuses the first answer and the sitting says so. The
 * device clock is used for one thing only: the words about how long is left.
 *
 * [UNAVAILABLE] is an exam the device knows about but has no window for — the map came from the cache — so there is
 * nothing from the server saying it may be sat; it stays shut until the app is online again.
 */
enum class ExamStatus { OPEN, REOPENED, SUBMITTED, RELEASED, UNAVAILABLE }

/** Whether the card lets the student in. */
val ExamStatus.canSit: Boolean get() = this == ExamStatus.OPEN || this == ExamStatus.REOPENED

/**
 * What the card shows: its [status], one formal [line] under the title, — only while open — the time [left], and the
 * label of its button: "Start exam", "Continue exam" once a question is answered (M4, D9), "See result" once released.
 */
data class ExamCard(val status: ExamStatus, val line: String, val left: String? = null, val action: String? = null)

/**
 * [loadedAt] is the device's clock when the server sent this island. `examWindow` carries the exam's own times, not
 * the extra sitting a teacher gives one student, so an island that arrives with a closing time already behind the
 * device's clock is shown as [ExamStatus.REOPENED]: open, with no closing time claimed. (A device whose clock runs
 * fast lands here too — it is equally open, and equally without a time the app could truthfully show.)
 */
fun examStatus(island: Island, loadedAt: Long, released: Boolean = false): ExamStatus {
    val window = island.examWindow
    return when {
        // M4 (D4): the teacher released the result — the card says so and opens it, whatever else it says.
        released -> ExamStatus.RELEASED
        island.state == IslandState.DONE -> ExamStatus.SUBMITTED
        window == null -> ExamStatus.UNAVAILABLE
        loadedAt >= window.closesAt -> ExamStatus.REOPENED
        else -> ExamStatus.OPEN
    }
}

/** [marked] is the released result of this exam, if any; [started] is "at least one question answered" (M4, D9). */
fun examCard(
    island: Island, loadedAt: Long, zone: TimeZone, s: LessonStrings, months: List<String>, shortMonths: Boolean,
    marked: ChildResult? = null, started: Boolean = false,
): ExamCard {
    val status = examStatus(island, loadedAt, released = marked != null)
    val window = island.examWindow
    val sit = if (started) s.continueExam else s.startExam
    return when (status) {
        ExamStatus.RELEASED -> ExamCard(status, examResultLine(marked!!, s), action = s.examSeeResult)
        ExamStatus.SUBMITTED -> ExamCard(status, s.examSubmittedNote)
        ExamStatus.UNAVAILABLE -> ExamCard(status, s.examNeedsConnection)
        ExamStatus.REOPENED -> ExamCard(status, s.examReopened, action = sit)
        ExamStatus.OPEN -> ExamCard(status, s.examOpenUntil.replace("{time}", examTime(window!!.closesAt, loadedAt, zone, months, shortMonths)), examTimeLeft(window.closesAt - loadedAt, s), action = sit)
    }
}

/** "Marked by your teacher · Secure" — the level in words, never a number (§7); just "marked" when there is no band. */
fun examResultLine(marked: ChildResult, s: LessonStrings): String =
    marked.band?.let { s.examResultLine.replace("{band}", examBand(it, s)) } ?: s.examResultNoBand

fun examBand(key: String, s: LessonStrings): String = s.examBands[key.lowercase()] ?: key

/**
 * How long the window stays open, in words and rounded down — a statement made when the home page loads, not a clock
 * that counts on screen (§7 allows no timer in front of a student).
 */
fun examTimeLeft(millis: Long, s: LessonStrings): String {
    val minutes = millis / 60_000
    return when {
        minutes >= 120 -> s.examHoursLeft.replace("{n}", "${minutes / 60}")
        minutes >= 60 -> s.examOneHourLeft
        minutes >= 10 -> s.examMinutesLeft.replace("{n}", "$minutes")
        else -> s.examLastMinutes
    }
}

/** `14:30` for a time today, `3 Oct, 14:30` otherwise — in the device's zone, 24-hour, as the dashboard writes it. */
fun examTime(millis: Long, nowMillis: Long, zone: TimeZone, months: List<String>, shortMonths: Boolean): String {
    val at = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val clock = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    if (at.date == today) return clock
    val month = months.getOrNull(at.monthNumber - 1).orEmpty().let { if (shortMonths) it.take(3) else it }
    return "${at.dayOfMonth} $month, $clock"
}
