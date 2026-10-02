package quest.feature.map.presentation

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import quest.api.dto.Island
import quest.api.dto.IslandState
import quest.feature.journey.presentation.LessonStrings

/**
 * §8: where one exam stands for this student, as the home card says it. Only [OPEN] can be entered.
 *
 * [UNAVAILABLE] is an exam the device knows about but has no window for — the map came from the cache — and an exam
 * is sat against the server's clock, so it stays shut until the app is online again.
 */
enum class ExamStatus { OPEN, NOT_OPEN, CLOSED, SUBMITTED, UNAVAILABLE }

/** What the card shows: its [status], one formal [line] under the title, and — only while open — the time [left]. */
data class ExamCard(val status: ExamStatus, val line: String, val left: String? = null)

/**
 * The window is half-open, as the server reads it: open from `opensAt` up to but not including `closesAt`. A sitting
 * that was handed in is [ExamStatus.SUBMITTED] whatever the clock says — one sitting, no way back in.
 */
fun examStatus(island: Island, nowMillis: Long): ExamStatus {
    val window = island.examWindow
    return when {
        island.state == IslandState.DONE -> ExamStatus.SUBMITTED
        window == null -> ExamStatus.UNAVAILABLE
        nowMillis < window.opensAt -> ExamStatus.NOT_OPEN
        nowMillis >= window.closesAt -> ExamStatus.CLOSED
        else -> ExamStatus.OPEN
    }
}

fun examCard(island: Island, nowMillis: Long, zone: TimeZone, s: LessonStrings, months: List<String>, shortMonths: Boolean): ExamCard {
    val status = examStatus(island, nowMillis)
    val window = island.examWindow
    fun time(millis: Long) = examTime(millis, nowMillis, zone, months, shortMonths)
    return when (status) {
        ExamStatus.SUBMITTED -> ExamCard(status, s.examSubmittedNote)
        ExamStatus.UNAVAILABLE -> ExamCard(status, s.examNeedsConnection)
        ExamStatus.NOT_OPEN -> ExamCard(status, s.examOpensAt.replace("{time}", time(window!!.opensAt)))
        ExamStatus.CLOSED -> ExamCard(status, s.examClosed)
        ExamStatus.OPEN -> ExamCard(status, s.examOpenUntil.replace("{time}", time(window!!.closesAt)), examTimeLeft(window.closesAt - nowMillis, s))
    }
}

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
