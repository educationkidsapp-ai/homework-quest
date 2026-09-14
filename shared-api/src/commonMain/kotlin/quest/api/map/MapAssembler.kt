package quest.api.map

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import quest.api.dto.Child
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.MapResponse
import quest.api.dto.PublishedLessonSummary
import quest.api.dto.Subject

/**
 * The §7 map rule, shared verbatim by `FakeContentApi` and the server:
 *  - one island per published lesson of the child's course in [from, to], ordered by date then math < english
 *  - done / today / waiting by completion and date; dates with nothing published show nothing
 *  - review islands (weak skills' Level-1 variant) only on a day that has a lesson, listed before it
 *  - exactly one locked island on the day after the last published date (or tomorrow)
 */
object MapAssembler {
    data class ReviewCandidate(val skillId: String, val skillName: String, val lessonId: String, val playId: String)

    fun assemble(
        child: Child,
        published: List<PublishedLessonSummary>,
        completions: List<LessonCompletionInfo>,
        reviewCandidates: List<ReviewCandidate>,
        parentUnlocked: Map<String, List<Int>>,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
    ): MapResponse {
        val course = child.course
        val inRange = published.filter { it.course == course && it.date >= from && it.date <= to }.sortedWith(compareBy({ it.date }, { if (it.subject == Subject.MATH) 0 else 1 }))
        val byLesson = completions.groupBy { it.lessonId }
        val islands = mutableListOf<Island>()

        if (inRange.any { it.date == today }) {
            reviewCandidates.forEach { r ->
                islands += Island(
                    id = "review-${r.skillId}", kind = IslandKind.REVIEW, date = today, state = IslandState.TODAY, title = r.skillName,
                    lessonId = r.lessonId, skillId = r.skillId, playId = r.playId,
                )
            }
        }

        inRange.forEach { lesson ->
            val done = byLesson[lesson.id].orEmpty()
            val levels = unlockedLevels(done, parentUnlocked[lesson.id].orEmpty())
            val state = when {
                done.isNotEmpty() -> IslandState.DONE
                lesson.date == today -> IslandState.TODAY
                else -> IslandState.WAITING
            }
            val best = done.maxByOrNull { it.starsEarned }
            islands += Island(
                id = "lesson-${lesson.id}", kind = IslandKind.LESSON, date = lesson.date, state = state, title = lesson.title, subject = lesson.subject,
                lessonId = lesson.id, lessonVersion = lesson.version, levelsUnlocked = levels, completedLevels = done.map { it.level }.distinct().sorted(),
                starsEarned = best?.starsEarned, starsTotal = best?.starsTotal ?: (lesson.stopsPerPlay * 3),
            )
        }

        val lastDate = published.filter { it.course == course }.maxOfOrNull { it.date }
        val nextDate = maxOf(lastDate ?: today, today).plus(1, DateTimeUnit.DAY)
        islands += Island(id = "locked-$nextDate", kind = IslandKind.LOCKED, date = nextDate, state = IslandState.LOCKED, title = "Still asleep")

        return MapResponse(child.id, course, from, to, today, islands)
    }

    /** Level 1 always; Level 2 after Level 1 with ≥ 2 stars on most stops (or parent unlock); Level 3 after Level 2 (or parent unlock). */
    fun unlockedLevels(completions: List<LessonCompletionInfo>, parentUnlocked: List<Int>): List<Int> {
        val levels = mutableSetOf(1)
        if (completions.any { it.level == 1 && it.mostStopsTwoStars } || 2 in parentUnlocked) levels += 2
        if (completions.any { it.level == 2 } || 3 in parentUnlocked) levels += 3
        if (3 in levels) levels += 2
        return levels.sorted()
    }
}
