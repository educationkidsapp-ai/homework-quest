package quest.feature.parent.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import quest.api.dto.Child
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.ReleasedResult
import quest.api.progress.Band
import quest.api.progress.ProgressBands
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.content.domain.MapRepository

class VerifyPinUseCase(private val repo: ParentRepository) { suspend operator fun invoke(pin: String): Boolean = pin.length == 4 && repo.verifyPin(pin) }
class SetPinUseCase(private val repo: ParentRepository) { suspend operator fun invoke(pin: String) { require(pin.length == 4 && pin.all { it.isDigit() }); repo.setPin(pin) } }

/** Every skill the child has met — server bands when online, local first-try results otherwise. Words, never percentages. */
class ProgressReportUseCase(private val journey: JourneyRepository, private val lessons: LessonRepository) {
    suspend operator fun invoke(child: Child): List<SkillReport> {
        val local = lessons.cachedSummaries().flatMap { s -> lessons.cached(s.id)?.skills.orEmpty() }.distinctBy { it.id }.map { skill ->
            val results = journey.firstTryResults(child.id, skill.id)
            val acc = ProgressBands.accuracy(results)
            SkillReport(skill.id, skill.name, skill.subject, acc?.let(ProgressBands::band), acc?.let(ProgressBands::accuracyWords), results.size, null)
        }
        val remote = journey.progressReport(child.id) ?: return local
        // The server is the source of truth once it has the attempts; until they are uploaded, the local record wins.
        val localById = local.associateBy { it.skillId }
        val merged = remote.skills.map { r ->
            val l = localById[r.skillId]
            if (l != null && l.attempts > r.attempts) l else SkillReport(r.skillId, r.name, r.subject, r.band?.let { b -> Band.valueOf(b) }, r.firstTryAccuracyWords, r.attempts, r.lastPractised)
        }
        return merged + local.filter { l -> remote.skills.none { it.skillId == l.skillId } }
    }
}

/**
 * Step 9's last line: the results the teacher has **released**, newest first, as the parent sees them.
 *
 * Nothing is here before she releases — the server sends `results` empty and says nothing about a lesson she has not
 * released — so there is no "not marked yet" state to render, and no way for the app to leak a score early.
 *
 * Parent mode only. §6 is unchanged and is the reason this is a use case of its own rather than something the map or
 * the journey could reach: the child sees stars, a sticker and a certificate, never a number.
 */
class ReleasedResultsUseCase(private val journey: JourneyRepository) {
    suspend operator fun invoke(child: Child): List<ReleasedResult> =
        journey.progressReport(child.id)?.results.orEmpty().sortedByDescending { it.releasedAt }

    /** The one released result for a lesson, for the lesson panel; null while the teacher has not released it. */
    suspend fun forLesson(child: Child, lessonId: String): ReleasedResult? =
        invoke(child).firstOrNull { it.lessonId == lessonId }
}

/** Month view: which dates have published lessons for the child's course and whether they were played. */
class CalendarUseCase(private val maps: MapRepository) {
    suspend operator fun invoke(child: Child, year: Int, month: Int, today: LocalDate): List<CalendarDay> {
        val first = LocalDate(year, month, 1)
        val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        val map = maps.map(child, first, last, today)
        return map.islands.filter { it.kind == IslandKind.LESSON }.groupBy { it.date }.map { (date, islands) ->
            CalendarDay(date, islands.mapNotNull { it.subject }, islands.mapNotNull { it.lessonId }, islands.filter { it.state == IslandState.DONE }.mapNotNull { it.lessonId })
        }.sortedBy { it.date }
    }
}

fun epochToDate(epochMillis: Long): LocalDate = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault()).date
