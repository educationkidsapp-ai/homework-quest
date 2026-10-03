package quest.feature.content.domain

import kotlinx.datetime.LocalDate
import quest.api.dto.Subject

/**
 * M4 (D4): a released exam result as the **student** sees it — the level the teacher gave and her comment, and no
 * number. §7 keeps a score out of 100 off every child screen (and `ReleasedResultsTest` guards it), so the score
 * the parent sees is dropped here, before anything in child mode can read it.
 */
data class ChildResult(
    val lessonId: String,
    val title: String?,
    val subject: Subject,
    val date: LocalDate,
    /** `emerging`, `developing`, `secure` or `exceeding`, or null when the teacher's release carries none. */
    val band: String?,
    val comment: String?,
    val releasedAt: Long,
)

/** The released results of [childId], without their scores; null when the server could not be asked. */
class ChildResultsUseCase(private val journey: JourneyRepository) {
    suspend operator fun invoke(childId: String): List<ChildResult>? =
        journey.progressReport(childId)?.results?.map { ChildResult(it.lessonId, it.title, it.subject, it.date, it.band, it.comment?.takeIf { c -> c.isNotBlank() }, it.releasedAt) }
}
